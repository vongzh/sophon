package com.vongz.sophon.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.vongz.sophon.SophonApp
import com.vongz.sophon.core.camera.MultiCameraManager
import com.vongz.sophon.core.codec.ContinuousRecorder
import com.vongz.sophon.core.gl.GpuCompositorImpl
import com.vongz.sophon.core.notification.SophonNotificationManager
import com.vongz.sophon.core.remote.bark.BarkNotifier
import com.vongz.sophon.core.storage.RamRingBuffer
import com.vongz.sophon.domain.model.GearState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极视核心视觉与录制守护服务 (SophonCoreService)
 *
 * 【架构隔离】
 * 运行于独立的 :core 后台守护进程中。
 * 串联驱动：四路相机采集 -> GPU 广角畸变校正与 OSD 水印 -> MediaCodec 零掉帧热切片 -> 外置 U 盘写入。
 */
class SophonCoreService : Service() {

    companion object {
        private const val TAG = "Sophon.CoreService"

        const val ACTION_TRIGGER_SNAPSHOT = "com.vongz.sophon.action.TRIGGER_SNAPSHOT"
        const val ACTION_TRIGGER_LOCK = "com.vongz.sophon.action.TRIGGER_LOCK"
        const val ACTION_EMERGENCY_FINALIZE = "com.vongz.sophon.action.EMERGENCY_FINALIZE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ramRingBuffer = RamRingBuffer()
    private val barkNotifier = BarkNotifier()

    private lateinit var notifManager: SophonNotificationManager
    private lateinit var gpuCompositor: GpuCompositorImpl
    private lateinit var multiCameraManager: MultiCameraManager
    private lateinit var continuousRecorder: ContinuousRecorder
    private var lanShareServer: com.vongz.sophon.core.lan.LanShareServer? = null

    private var isRecordingActive = false
    private var recordingDurationSec = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SophonCoreService onCreate in PID: ${android.os.Process.myPid()}")

        notifManager = SophonNotificationManager(this)
        startForegroundServiceInternal()

        initVisualAndRecordingPipeline()
        startLanShareServerInternal()
        observeStorageHealth()
        observeVehicleTelemetryForOsd()
        observeEmergencyCollisionEvents()
        observeHardKeyEvents()
        startNotificationUpdateLoop()
    }

    private fun startLanShareServerInternal() {
        try {
            lanShareServer = com.vongz.sophon.core.lan.LanShareServer(SophonApp.storageVault, 8088).apply {
                start()
            }
            Log.i(TAG, "LanShareServer started on port 8088.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start LanShareServer", e)
        }
    }

    private fun startForegroundServiceInternal() {
        val initialNotif = notifManager.buildOngoingNotification(
            isRecording = false,
            durationSec = 0L,
            isUsbMounted = SophonApp.storageVault.isUsbMounted.value
        )
        startForeground(SophonNotificationManager.NOTIFICATION_ID, initialNotif)
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
                gpuCompositor.updateOsdMetadata(speed, gearText)
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
                    barkNotifier.sendAlert(
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
                    com.vongz.sophon.domain.model.HardKeyEvent.SHORT_PRESS -> {
                        Log.i(TAG, "Hard key SHORT_PRESS -> Triggering instant snapshot")
                        takeManualSnapshot()
                    }
                    com.vongz.sophon.domain.model.HardKeyEvent.LONG_PRESS -> {
                        Log.w(TAG, "Hard key LONG_PRESS -> Triggering emergency event lock")
                        continuousRecorder.triggerEventLock("Steering Wheel Custom Key Long Press")
                        barkNotifier.sendAlert("方向盘加锁", "驾驶员长按方向盘自定义键锁定了当前视频。")
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
                barkNotifier.sendAlert("手动加锁", "驾驶员已手动加锁当前行车切片。")
            }
            ACTION_EMERGENCY_FINALIZE -> {
                Log.w(TAG, "EMERGENCY FINALIZE: Vehicle power-cut or STR suspend detected!")
                continuousRecorder.emergencyFinalizeCurrentChunk("System Suspend / Power-down")
            }
        }
        return START_STICKY
    }

    private fun takeManualSnapshot() {
        val usbRoot = SophonApp.storageVault.currentUsbRoot.value
        if (usbRoot == null || !SophonApp.storageVault.isUsbMounted.value) {
            Log.w(TAG, "Cannot take snapshot: USB not mounted.")
            return
        }

        val snapshotDir = File(File(usbRoot, "SophonRecord"), "Snapshot")
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val photoFile = File(snapshotDir, "SNAP_${timeStr}.jpg")

        gpuCompositor.captureSnapshot(photoFile) { success ->
            if (success) {
                Log.i(TAG, "Snapshot taken successfully: ${photoFile.name}")
                barkNotifier.sendAlert("抓拍成功", "四路全景原图已保存至 U 盘 Snapshot/${photoFile.name}")
            } else {
                Log.e(TAG, "Failed to capture snapshot.")
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "SophonCoreService onDestroy")
        serviceScope.cancel()
        try {
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
