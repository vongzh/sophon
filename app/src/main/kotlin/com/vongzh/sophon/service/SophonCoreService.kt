package com.vongzh.sophon.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.core.camera.MultiCameraManager
import com.vongzh.sophon.core.codec.ContinuousRecorder
import com.vongzh.sophon.core.gl.GpuCompositorImpl
import com.vongzh.sophon.core.notification.SophonNotificationManager
import com.vongzh.sophon.core.power.PowerState
import com.vongzh.sophon.core.remote.bark.BarkNotifier
import com.vongzh.sophon.core.storage.RamRingBuffer
import com.vongzh.sophon.domain.model.CameraPosition
import com.vongzh.sophon.domain.model.GearState
import com.vongzh.sophon.domain.model.RecordCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抓拍结果载荷数据
 */
data class SnapshotResult(
    val success: Boolean,
    val filePath: String?,
    val isExternalUsb: Boolean,
    val message: String
)

/**
 * 极视核心视觉与录制守护服务 (SophonCoreService)
 */
class SophonCoreService : Service() {

    companion object {
        private const val TAG = "Sophon.CoreService"

        const val ACTION_TRIGGER_SNAPSHOT = "com.vongzh.sophon.action.TRIGGER_SNAPSHOT"
        const val ACTION_TRIGGER_LOCK = "com.vongzh.sophon.action.TRIGGER_LOCK"
        const val ACTION_EMERGENCY_FINALIZE = "com.vongzh.sophon.action.EMERGENCY_FINALIZE"

        var instance: SophonCoreService? = null
            private set

        /**
         * 局域网快传服务的真实运行状态。
         *
         * 设置页「局域网快传」卡片的状态胶囊必须读这里，而不能写死「已就绪」——
         * 服务可能因端口被占用而启动失败，写死就是假状态。
         */
        private val _lanShareRunning = MutableStateFlow(false)
        val lanShareRunningFlow: StateFlow<Boolean> = _lanShareRunning.asStateFlow()

        /** 当前实际监听的端口（启动失败时为 null） */
        private val _lanSharePort = MutableStateFlow<Int?>(null)
        val lanSharePortFlow: StateFlow<Int?> = _lanSharePort.asStateFlow()

        /** 抓拍事件通知流，供前台 UI 与相册实时捕获 */
        private val _snapshotEventFlow = MutableSharedFlow<SnapshotResult>(replay = 0, extraBufferCapacity = 8)
        val snapshotEventFlow: SharedFlow<SnapshotResult> = _snapshotEventFlow.asSharedFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ramRingBuffer = RamRingBuffer()

    private val _isRecording = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSecFlow: StateFlow<Long> = _recordingDurationSec.asStateFlow()

    val availableCamerasFlow: StateFlow<Set<CameraPosition>>?
        get() = if (::multiCameraManager.isInitialized) multiCameraManager.availableCameras else null

    private lateinit var notifManager: SophonNotificationManager
    private lateinit var gpuCompositor: GpuCompositorImpl
    private lateinit var multiCameraManager: MultiCameraManager
    private lateinit var continuousRecorder: ContinuousRecorder
    private lateinit var crashSensorDetector: com.vongzh.sophon.core.sensor.CrashSensorDetector
    private var gnssLocationObserver: com.vongzh.sophon.core.sensor.GnssLocationObserver? = null
    private var lanShareServer: com.vongzh.sophon.core.lan.LanShareServer? = null

    private var isRecordingActive = false

    /** 「车机启动时」配额检查的一次性闸门，避免每次 U 盘重新挂载都重复清一遍 */
    private var bootQuotaChecked = false
    private var recordingDurationSec = 0L
    private val pendingSurfaces = java.util.concurrent.ConcurrentHashMap<CameraPosition, android.view.Surface>()

    fun attachPreviewSurface(position: CameraPosition, surface: android.view.Surface?) {
        if (surface != null && surface.isValid) {
            pendingSurfaces[position] = surface
        } else {
            pendingSurfaces.remove(position)
        }
        if (::multiCameraManager.isInitialized) {
            multiCameraManager.setPreviewSurface(position, surface)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "SophonCoreService onCreate in PID: ${android.os.Process.myPid()}")

        notifManager = SophonNotificationManager(this)
        startForegroundServiceInternal()

        initVisualAndRecordingPipeline()
        startLanShareServerInternal()
        initCrashSensorDetector()
        initGnssLocationObserver()
        observeStorageHealth()
        observeVehicleTelemetryForOsd()
        observeEmergencyCollisionEvents()
        observePowerStateMachine()
        observeParkRecordingPolicy()
        observeBatterySafety()
        observeHardKeyEvents()
        startNotificationUpdateLoop()
    }

    private fun initCrashSensorDetector() {
        crashSensorDetector = com.vongzh.sophon.core.sensor.CrashSensorDetector(this) { impactG ->
            val bus = SophonApp.vehicleSignalBus as? com.vongzh.sophon.core.vhal.VehicleSignalBusImpl
            val powerState = SophonApp.powerStateMachine.currentState.value
            val isSentry = powerState == PowerState.SENTRY_STANDBY ||
                (powerState == PowerState.AWAKE_PARKED && SophonApp.preferences.isSentryModeEnabled)
            if (isSentry) {
                Log.w(TAG, "Sentry shock alarm triggered at %.2f G (dispatching sentry vibration event)".format(impactG))
                bus?.dispatchSentryVibrationEvent(System.currentTimeMillis())
            } else {
                bus?.dispatchEmergencyCollisionEvent("加速度计剧烈碰撞 (%.1fG)".format(impactG))
            }
        }
        crashSensorDetector.start()
    }

    private fun initGnssLocationObserver() {
        gnssLocationObserver = com.vongzh.sophon.core.sensor.GnssLocationObserver(this) { telemetry ->
            val bus = SophonApp.vehicleSignalBus as? com.vongzh.sophon.core.vhal.VehicleSignalBusImpl
            bus?.dispatchGnssTelemetry(telemetry)
        }.apply {
            start()
        }
    }

    /**
     * 影像参数热更新入口（设置中心修改曝光 / 降噪后立即下发到各路摄像头）
     */
    fun refreshImageTuning() {
        if (!::multiCameraManager.isInitialized) return
        multiCameraManager.refreshImageTuning()
    }

    /** 切片时长热生效：改完即刻按新时长切片，无需重启录制 */
    fun refreshChunkDuration() {
        if (!::continuousRecorder.isInitialized) return
        continuousRecorder.applyChunkDuration()
    }

    /**
     * 座舱录音开关热生效：起停麦克风采集并在下一个切片增减音轨。
     *
     * @return 音轨是否真的已开工。false 表示麦克风未授权或被占用，
     *         调用方须如实告知用户，不能让开关显示「开」而文件里没有音轨。
     */
    fun refreshCabinAudio(): Boolean {
        if (!::continuousRecorder.isInitialized) return false
        return continuousRecorder.applyAudioTrackState()
    }

    /**
     * 座舱音轨此刻是否真的在采集。UI 状态文案一律读它，不读偏好原值：
     * 开关开了却拿不到麦克风时，按偏好显示「录音开启」就是新的假状态。
     */
    fun isCabinAudioActive(): Boolean {
        if (!::continuousRecorder.isInitialized) return false
        return continuousRecorder.isCabinAudioActive()
    }

    /**
     * 雨夜增强模式热切换入口。
     *
     * 联动 Camera2 ISP 硬件采集参数与离屏合成器，立即热重载四路摄像头采集参数，
     * 无需重启录制管线。
     */
    fun refreshRainyNightMode() {
        if (::gpuCompositor.isInitialized) {
            gpuCompositor.setRainyNightMode(SophonApp.preferences.isRainyNightModeEnabled)
        }
        if (::multiCameraManager.isInitialized) {
            multiCameraManager.refreshImageTuning()
        }
    }

    private fun startLanShareServerInternal() {
        val port = SophonApp.preferences.lanSharePort
        try {
            lanShareServer = com.vongzh.sophon.core.lan.LanShareServer(SophonApp.storageVault, port).apply {
                start()
            }
            _lanShareRunning.value = true
            _lanSharePort.value = port
            Log.i(TAG, "LanShareServer started on port $port.")
        } catch (e: Exception) {
            lanShareServer = null
            _lanShareRunning.value = false
            _lanSharePort.value = null
            Log.w(TAG, "Failed to start LanShareServer on port $port", e)
        }
    }

    /**
     * LAN 快传端口热生效。
     *
     * 端口此前只在服务启动时读一次，用户在设置页改完依旧只能等重启应用——又一条
     * 「改了没反应」。这里直接停掉旧监听并按新端口重开；新端口若起不来（被占用），
     * 状态流会如实变成未运行，不会留下「改了设置却还在旧端口上跑」的错觉。
     */
    fun restartLanShareServer() {
        serviceScope.launch(Dispatchers.IO) {
            runCatching { lanShareServer?.stop() }
            lanShareServer = null
            _lanShareRunning.value = false
            _lanSharePort.value = null
            startLanShareServerInternal()
        }
    }

    private fun startForegroundServiceInternal() {
        val initialNotif = notifManager.buildOngoingNotification(
            isRecording = false,
            durationSec = 0L,
            isUsbMounted = SophonApp.storageVault.isUsbMounted.value
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasCamera) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                val hasAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasAudio) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(SophonNotificationManager.NOTIFICATION_ID, initialNotif, types)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SophonNotificationManager.NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(SophonNotificationManager.NOTIFICATION_ID, initialNotif)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Starting FGS with camera/mic type restricted by OS, falling back to dataSync: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(SophonNotificationManager.NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(SophonNotificationManager.NOTIFICATION_ID, initialNotif)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground also failed", e2)
            }
        }
    }

    private fun initVisualAndRecordingPipeline() {
        try {
            // 1. 初始化 GPU 离屏渲染合成器 (1920x1080)
            gpuCompositor = GpuCompositorImpl().apply {
                initialize(1920, 1080)
                setRainyNightMode(SophonApp.preferences.isRainyNightModeEnabled)
            }

            // 2. 初始化 0 掉帧连续硬件编码器并绑定 Input Surface
            continuousRecorder = ContinuousRecorder(
                storageVault = SophonApp.storageVault,
                ramRingBuffer = ramRingBuffer,
                width = 1920,
                height = 1080
            )
            val encoderInputSurface = continuousRecorder.prepare()
            gpuCompositor.setCodecInputSurface(encoderInputSurface)

            // 3. 按照 150ms 串行避让并发栅栏启动四路物理摄像头
            multiCameraManager = MultiCameraManager(this, gpuCompositor)
            pendingSurfaces.forEach { (pos, surf) ->
                if (surf.isValid) {
                    multiCameraManager.setPreviewSurface(pos, surf)
                }
            }
            multiCameraManager.startAllCameras()

            Log.i(TAG, "Core visual & recording pipeline fully initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize visual pipeline", e)
        }
    }

    private fun observeStorageHealth() {
        val storage = SophonApp.storageVault
        serviceScope.launch {
            storage.isUsbMounted.collectLatest { isMounted ->
                if (!isMounted) {
                    Log.w(TAG, "USB storage unmounted! Halting disk writer. RAM Ring Buffer enters standby.")
                    ramRingBuffer.clear()
                    isRecordingActive = false
                    continuousRecorder.stopRecording()
                } else {
                    Log.i(TAG, "USB storage mounted. Starting continuous recording stream...")
                    isRecordingActive = true
                    continuousRecorder.startRecording()
                    // 「车机启动时」触发时机：U 盘就绪后执行一次配额检查。
                    // 不能在 onCreate 直接跑——那时 U 盘往往还没挂载，检查会空转。
                    if (!bootQuotaChecked) {
                        bootQuotaChecked = true
                        storage.enforceStorageQuotas(
                            com.vongzh.sophon.domain.contract.IStorageVault.CLEANUP_TRIGGER_BOOT
                        )
                    }
                }
                notifManager.updateNotification(isRecordingActive, recordingDurationSec, isMounted)
            }
        }
    }

    private fun observeVehicleTelemetryForOsd() {
        val bus = SophonApp.vehicleSignalBus
        serviceScope.launch {
            bus.vehicleSpeed.collectLatest { speed ->
                val gearText = when (bus.gearState.value) {
                    GearState.PARK -> "P"
                    GearState.REVERSE -> "R"
                    GearState.NEUTRAL -> "N"
                    GearState.DRIVE -> "D"
                    GearState.UNKNOWN -> "--"
                }
                val turnText = when (bus.turnSignalState.value) {
                    com.vongzh.sophon.domain.model.TurnSignalState.LEFT -> "左"
                    com.vongzh.sophon.domain.model.TurnSignalState.RIGHT -> "右"
                    com.vongzh.sophon.domain.model.TurnSignalState.HAZARD -> "双闪"
                    else -> "--"
                }
                gpuCompositor.updateOsdMetadata(
                    speedKmh = speed,
                    gearText = gearText,
                    isBraking = bus.isBraking.value,
                    turnSignalText = turnText
                )
            }
        }
    }

    private fun observePowerStateMachine() {
        serviceScope.launch {
            SophonApp.powerStateMachine.currentState.collectLatest { state ->
                Log.i(TAG, "PowerState transition observed: $state")
                when (state) {
                    PowerState.AWAKE_DRIVING, PowerState.AWAKE_PARKED -> {
                        continuousRecorder.setBaseCategory(RecordCategory.NORMAL)
                        continuousRecorder.updateBitrate(10_000_000)
                        if (!continuousRecorder.isRecording.value && SophonApp.storageVault.isUsbMounted.value) {
                            continuousRecorder.startRecording()
                        }
                    }
                    PowerState.SENTRY_STANDBY -> {
                        Log.i(TAG, "Entering SENTRY_STANDBY: 3Mbps low bitrate encoding mode.")
                        continuousRecorder.setBaseCategory(RecordCategory.SENTRY)
                        continuousRecorder.updateBitrate(3_000_000)
                        if (!continuousRecorder.isRecording.value && SophonApp.storageVault.isUsbMounted.value) {
                            continuousRecorder.startRecording()
                        }
                    }
                    PowerState.SENTRY_ALERT -> {
                        Log.w(TAG, "Entering SENTRY_ALERT: 10Mbps bitrate boosted, triggering event lock.")
                        continuousRecorder.updateBitrate(10_000_000)
                        continuousRecorder.triggerEventLock("Sentry Shock Alarm")
                        if (SophonApp.preferences.isSentryDeterrentEnabled) {
                            try {
                                val intent = Intent(this@SophonCoreService, com.vongzh.sophon.ui.sentry.SentryDeterrentActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch SentryDeterrentActivity", e)
                            }
                        }
                        SophonApp.remoteDispatcher.sendMultiChannelAlert(
                            title = "哨兵异常震动报警",
                            body = "侦测到车身震动/异常晃动，已锁定四路全维视频切片并亮屏威慑！"
                        )
                    }
                    PowerState.EMERGENCY_POWER_CUT -> {
                        Log.w(TAG, "EMERGENCY_POWER_CUT: Finalizing chunk and stopping recorder.")
                        continuousRecorder.emergencyFinalizeCurrentChunk("Low 12V Battery / Vehicle STR Suspend")
                    }
                }
            }
        }
    }

    /**
     * 车身信号联动录制策略
     *
     * 依据 CAN 车身信号（挡位 / 主驾车门）自动暂停行车循环录制，避免录满大量无效驻车影像。
     * 两个开关默认关闭，需用户在设置中显式启用；哨兵模式（SENTRY_*）下本策略失效，驻车监控优先。
     */
    private fun observeParkRecordingPolicy() {
        val bus = SophonApp.vehicleSignalBus
        val prefs = SophonApp.preferences
        serviceScope.launch {
            combine(
                bus.gearState,
                bus.doorState,
                SophonApp.powerStateMachine.currentState
            ) { gear, door, power ->
                if (!prefs.isStopRecordOnParkEnabled && !prefs.isStopRecordOnDriverDoorEnabled) return@combine false
                // 哨兵模式开启时车身信号联动策略失效
                if (power == PowerState.SENTRY_STANDBY || power == PowerState.SENTRY_ALERT) return@combine false
                val parkHit = prefs.isStopRecordOnParkEnabled && gear == GearState.PARK
                val doorHit = prefs.isStopRecordOnDriverDoorEnabled && door.isDriverAjar
                parkHit || doorHit
            }.distinctUntilChanged().collectLatest { shouldPause ->
                if (!::continuousRecorder.isInitialized) return@collectLatest
                if (shouldPause) {
                    if (continuousRecorder.isRecording.value) {
                        Log.i(TAG, "Park/driver-door policy hit: pausing loop recording.")
                        continuousRecorder.stopRecording()
                    }
                } else {
                    if (!continuousRecorder.isRecording.value && SophonApp.storageVault.isUsbMounted.value) {
                        Log.i(TAG, "Park/driver-door policy released: resuming loop recording.")
                        continuousRecorder.startRecording()
                    }
                }
            }
        }
    }

    private fun observeBatterySafety() {
        val bus = SophonApp.vehicleSignalBus
        val prefs = SophonApp.preferences
        serviceScope.launch {
            bus.batteryVoltage.collectLatest { volts ->
                if (prefs.isBatteryProtectionEnabled && volts > 0f && volts < prefs.batteryProtectionThresholdVolts) {
                    Log.e(TAG, "CRITICAL: 12V Battery Voltage below safety threshold (%.2fV < %.1fV). Emergency sentry power cut!".format(volts, prefs.batteryProtectionThresholdVolts))
                    SophonApp.remoteDispatcher.sendMultiChannelAlert(
                        title = "蓄电池低压保护触发",
                        body = "车辆12V蓄电池电压降至 %.1fV (保护阈值 %.1fV)，已主动休眠哨兵模式避免小电瓶亏电趴窝！".format(volts, prefs.batteryProtectionThresholdVolts)
                    )
                }
            }
        }
    }

    private fun observeEmergencyCollisionEvents() {
        val bus = SophonApp.vehicleSignalBus
        serviceScope.launch {
            bus.emergencyCollisionEvents.collectLatest { reason ->
                if (SophonApp.preferences.isAutoCrashLockEnabled) {
                    Log.w(TAG, "CRITICAL: Triggering auto lock due to collision/emergency decel: $reason")
                    continuousRecorder.triggerEventLock(reason)
                    SophonApp.remoteDispatcher.sendMultiChannelAlert(
                        title = "车辆急刹/碰撞警报",
                        body = "检测到: $reason，已紧急锁死当前与前序关键视频分段！"
                    )
                }
            }
        }
    }

    private fun observeHardKeyEvents() {
        val bus = SophonApp.vehicleSignalBus
        serviceScope.launch {
            bus.hardKeyEvents.collectLatest { event ->
                when (event) {
                    com.vongzh.sophon.domain.model.HardKeyEvent.SHORT_PRESS -> {
                        Log.i(TAG, "Hard key SHORT_PRESS -> Triggering instant snapshot")
                        takeManualSnapshot()
                    }
                    com.vongzh.sophon.domain.model.HardKeyEvent.LONG_PRESS -> {
                        Log.w(TAG, "Hard key LONG_PRESS -> Triggering emergency event lock")
                        continuousRecorder.triggerEventLock("Steering Wheel Custom Key Long Press")
                        SophonApp.remoteDispatcher.sendMultiChannelAlert("方向盘加锁", "驾驶员长按方向盘自定义键锁定了当前视频。")
                    }
                    com.vongzh.sophon.domain.model.HardKeyEvent.HORN_QUICK_SAVE -> {
                        Log.w(TAG, "Hard key HORN_QUICK_SAVE -> Triggering horn event lock (Tesla-style)")
                        continuousRecorder.triggerEventLock("Horn Honked Quick Save")
                        SophonApp.remoteDispatcher.sendMultiChannelAlert("鸣笛加锁", "侦测到车辆鸣笛，已自动锁定该时段前后关键事件切片。")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun startNotificationUpdateLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (isRecordingActive) {
                    recordingDurationSec++
                    _recordingDurationSec.value = recordingDurationSec
                    _isRecording.value = true
                } else {
                    _isRecording.value = false
                }
                notifManager.updateNotification(
                    isRecording = isRecordingActive,
                    durationSec = recordingDurationSec,
                    isUsbMounted = SophonApp.storageVault.isUsbMounted.value
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_SNAPSHOT -> {
                Log.i(TAG, "Handling snapshot trigger...")
                takeManualSnapshot()
            }
            ACTION_TRIGGER_LOCK -> {
                Log.i(TAG, "Handling manual event lock trigger...")
                continuousRecorder.triggerEventLock("Manual User Trigger")
                SophonApp.remoteDispatcher.sendMultiChannelAlert("手动加锁", "驾驶员已手动加锁当前行车切片。")
            }
            ACTION_EMERGENCY_FINALIZE -> {
                Log.w(TAG, "EMERGENCY FINALIZE: Vehicle power-cut or STR suspend detected!")
                continuousRecorder.emergencyFinalizeCurrentChunk("System Suspend / Power-down")
            }
        }
        return START_STICKY
    }

    private fun takeManualSnapshot() {
        val storage = SophonApp.storageVault
        val isUsb = storage.isUsbMounted.value && storage.currentUsbRoot.value != null

        // 车规安全红线二：严格执行 No USB, No Write，严禁向车机内部闪存写入高频抓拍图片
        if (!isUsb) {
            Log.w(TAG, "Manual snapshot rejected: External USB drive is not mounted. Prohibiting write to internal flash.")
            _snapshotEventFlow.tryEmit(
                SnapshotResult(
                    success = false,
                    filePath = null,
                    isExternalUsb = false,
                    message = "未检测到外接 U 盘，为保护车机闪存寿命已禁止写入"
                )
            )
            return
        }

        val usbRoot = storage.currentUsbRoot.value!!
        val snapshotDir = File(File(usbRoot, "SophonRecord"), "Snapshot")
        try {
            if (!snapshotDir.exists()) {
                snapshotDir.mkdirs()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare snapshot dir: ${snapshotDir.absolutePath}", e)
        }

        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val photoFile = File(snapshotDir, "SNAP_${timeStr}.jpg")

        gpuCompositor.captureSnapshot(photoFile) { success ->
            if (success) {
                Log.i(TAG, "Snapshot taken successfully to external storage: ${photoFile.name}")
                _snapshotEventFlow.tryEmit(
                    SnapshotResult(
                        success = true,
                        filePath = photoFile.absolutePath,
                        isExternalUsb = true,
                        message = "四路原图已抓拍并存入外接相册"
                    )
                )
                SophonApp.remoteDispatcher.sendMultiChannelAlert("抓拍成功", "四路全景原图已保存至外接存储 Snapshot/${photoFile.name}", photoFile)
            } else {
                Log.e(TAG, "Failed to capture snapshot.")
                _snapshotEventFlow.tryEmit(
                    SnapshotResult(
                        success = false,
                        filePath = null,
                        isExternalUsb = true,
                        message = "抓拍合成失败，请重试"
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "SophonCoreService onDestroy")
        if (instance == this) instance = null
        serviceScope.cancel()
        _lanShareRunning.value = false
        _lanSharePort.value = null
        try {
            gnssLocationObserver?.stop()
            crashSensorDetector.stop()
            lanShareServer?.stop()
            continuousRecorder.stopRecording()
            multiCameraManager.stopAllCameras()
            gpuCompositor.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up pipeline", e)
        }
        ramRingBuffer.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
