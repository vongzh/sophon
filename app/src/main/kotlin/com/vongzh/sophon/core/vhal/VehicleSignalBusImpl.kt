package com.vongzh.sophon.core.vhal

import android.content.Context
import android.util.Log
import com.vongzh.sophon.domain.contract.IVehicleSignalBus
import com.vongzh.sophon.domain.model.DoorState
import com.vongzh.sophon.domain.model.GearState
import com.vongzh.sophon.domain.model.HardKeyEvent
import com.vongzh.sophon.domain.model.TurnSignalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 车身信号反应式监听总线实现 (VehicleSignalBusImpl)
 *
 * 【座舱级全域信号总线】
 * 支持接入 Android CarPropertyManager 及车身专用通信信道。
 *
 * 【安全红线 - 底线三 (Zero Tampering)】
 * 100% 只读订阅者 (Read-Only Subscriber)，绝不向总线发送控制指令。
 */
class VehicleSignalBusImpl(
    private val context: Context
) : IVehicleSignalBus {

    companion object {
        private const val TAG = "Sophon.VehicleSignalBus"
    }

    private val busScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _batteryVoltage = MutableStateFlow(0.0f)
    override val batteryVoltage: StateFlow<Float> = _batteryVoltage.asStateFlow()

    private val _isBraking = MutableStateFlow(false)
    override val isBraking: StateFlow<Boolean> = _isBraking.asStateFlow()

    private val _turnSignalState = MutableStateFlow(TurnSignalState.OFF)
    override val turnSignalState: StateFlow<TurnSignalState> = _turnSignalState.asStateFlow()

    private val _vehicleSpeed = MutableStateFlow(0f)
    override val vehicleSpeed: StateFlow<Float> = _vehicleSpeed.asStateFlow()

    private val _outsideTemperature = MutableStateFlow<Float?>(null)
    override val outsideTemperature: StateFlow<Float?> = _outsideTemperature.asStateFlow()

    private val _gearState = MutableStateFlow(GearState.PARK)
    override val gearState: StateFlow<GearState> = _gearState.asStateFlow()

    private val _steeringAngle = MutableStateFlow(0f)
    override val steeringAngle: StateFlow<Float> = _steeringAngle.asStateFlow()

    private val _isDriverSeatbeltFastened = MutableStateFlow(true)
    override val isDriverSeatbeltFastened: StateFlow<Boolean> = _isDriverSeatbeltFastened.asStateFlow()

    private val _isAvmActive = MutableStateFlow(false)
    override val isAvmActive: StateFlow<Boolean> = _isAvmActive.asStateFlow()

    private val _doorState = MutableStateFlow(DoorState())
    override val doorState: StateFlow<DoorState> = _doorState.asStateFlow()

    private val _hardKeyEvents = MutableSharedFlow<HardKeyEvent>(extraBufferCapacity = 8)
    override val hardKeyEvents: SharedFlow<HardKeyEvent> = _hardKeyEvents.asSharedFlow()

    /**
     * 车规信号授权失败原因 (null 表示正常)
     *
     * 当 CarPropertyManager 因缺少 android.car.permission.* 抛出 SecurityException 时上抛，
     * 供 UI / 通知显式提示，避免车速/挡位/温度"静默恒为默认值"却被误认为真数据。
     */
    private val _vhalAuthError = MutableStateFlow<String?>(null)
    override val vhalAuthError: StateFlow<String?> = _vhalAuthError.asStateFlow()

    private val _sentryTriggerEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    override val sentryTriggerEvents: SharedFlow<Long> = _sentryTriggerEvents.asSharedFlow()

    private val _emergencyCollisionEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val emergencyCollisionEvents: SharedFlow<String> = _emergencyCollisionEvents.asSharedFlow()

    private val _gnssTelemetry = MutableStateFlow(com.vongzh.sophon.domain.model.GnssTelemetry())
    override val gnssTelemetry: StateFlow<com.vongzh.sophon.domain.model.GnssTelemetry> = _gnssTelemetry.asStateFlow()

    private val _isHornActive = MutableStateFlow(false)
    override val isHornActive: StateFlow<Boolean> = _isHornActive.asStateFlow()

    private var nativeObserver: NativeVehiclePropertyObserver? = null
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var isListening = false

    override fun startListening() {
        if (isListening) return
        isListening = true
        Log.i(TAG, "VehicleSignalBus started. Multi-source read-only pipeline active.")

        // 监听系统供电/蓄电池真实电压广播，零假数据
        try {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    if (intent?.action == android.content.Intent.ACTION_BATTERY_CHANGED) {
                        val voltageRaw = intent.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)
                        if (voltageRaw > 0) {
                            val volts = if (voltageRaw > 100) voltageRaw / 1000f else voltageRaw.toFloat()
                            dispatchBatteryVoltageUpdate(volts)
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val sticky = context.registerReceiver(receiver, filter)
            sticky?.let { intent ->
                val voltageRaw = intent.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1)
                if (voltageRaw > 0) {
                    val volts = if (voltageRaw > 100) voltageRaw / 1000f else voltageRaw.toFloat()
                    dispatchBatteryVoltageUpdate(volts)
                }
            }
            batteryReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery receiver", e)
        }

        // 直连底层车规 CarPropertyManager 观察者
        nativeObserver = NativeVehiclePropertyObserver(context, this).apply {
            start()
        }
    }

    override fun stopListening() {
        if (!isListening) return
        isListening = false
        nativeObserver?.stop()
        nativeObserver = null
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // ignore
            }
            batteryReceiver = null
        }
        Log.i(TAG, "VehicleSignalBus stopped.")
    }

    fun dispatchEmergencyCollisionEvent(reason: String) {
        busScope.launch {
            _emergencyCollisionEvents.emit(reason)
        }
    }

    // --- 内部只读分发接口 (供底层 HAL / 车辆总线观察者更新内存状态) ---

    fun dispatchBatteryVoltageUpdate(voltage: Float) {
        _batteryVoltage.value = voltage
    }

    fun dispatchBrakeUpdate(isBraking: Boolean) {
        _isBraking.value = isBraking
    }

    fun dispatchTurnSignalUpdate(state: TurnSignalState) {
        _turnSignalState.value = state
    }

    fun dispatchSpeedUpdate(speed: Float) {
        _vehicleSpeed.value = speed
    }

    fun dispatchOutsideTemperatureUpdate(tempCelsius: Float) {
        _outsideTemperature.value = tempCelsius
    }

    fun dispatchGearUpdate(gear: GearState) {
        _gearState.value = gear
        if (gear == GearState.REVERSE) {
            dispatchAvmStateUpdate(true)
        }
    }

    fun dispatchSteeringAngleUpdate(angle: Float) {
        _steeringAngle.value = angle
    }

    fun dispatchSeatbeltUpdate(fastened: Boolean) {
        _isDriverSeatbeltFastened.value = fastened
    }

    fun dispatchAvmStateUpdate(active: Boolean) {
        if (_isAvmActive.value != active) {
            Log.d(TAG, "AVM active state: $active (50ms Evasion Channel)")
            _isAvmActive.value = active
        }
    }

    fun dispatchDoorStateUpdate(door: DoorState) {
        _doorState.value = door
    }

    override fun dispatchHardKeyEvent(event: HardKeyEvent) {
        busScope.launch {
            _hardKeyEvents.emit(event)
        }
    }

    fun dispatchSentryVibrationEvent(timestampMs: Long) {
        busScope.launch {
            _sentryTriggerEvents.emit(timestampMs)
        }
    }

    fun dispatchGnssTelemetry(telemetry: com.vongzh.sophon.domain.model.GnssTelemetry) {
        _gnssTelemetry.value = telemetry
    }

    /**
     * 上报车规信号授权失败 (权限未授予 / 非系统应用)
     */
    fun dispatchVhalAuthFailure(reason: String) {
        Log.e(TAG, "VHAL authorization failure: $reason")
        _vhalAuthError.value = reason
    }

    override fun dispatchHornEvent(active: Boolean) {
        val wasActive = _isHornActive.value
        _isHornActive.value = active
        if (active && !wasActive) {
            Log.i(TAG, "Horn activated -> dispatching HORN_QUICK_SAVE")
            dispatchHardKeyEvent(HardKeyEvent.HORN_QUICK_SAVE)
        }
    }
}
