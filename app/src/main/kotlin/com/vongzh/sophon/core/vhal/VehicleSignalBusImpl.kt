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

    private val _batteryVoltage = MutableStateFlow(12.6f)
    override val batteryVoltage: StateFlow<Float> = _batteryVoltage.asStateFlow()

    private val _isBraking = MutableStateFlow(false)
    override val isBraking: StateFlow<Boolean> = _isBraking.asStateFlow()

    private val _turnSignalState = MutableStateFlow(TurnSignalState.OFF)
    override val turnSignalState: StateFlow<TurnSignalState> = _turnSignalState.asStateFlow()

    private val _vehicleSpeed = MutableStateFlow(0f)
    override val vehicleSpeed: StateFlow<Float> = _vehicleSpeed.asStateFlow()

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

    private val _sentryTriggerEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    override val sentryTriggerEvents: SharedFlow<Long> = _sentryTriggerEvents.asSharedFlow()

    private val _emergencyCollisionEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val emergencyCollisionEvents: SharedFlow<String> = _emergencyCollisionEvents.asSharedFlow()

    private var nativeObserver: NativeVehiclePropertyObserver? = null
    private var isListening = false

    override fun startListening() {
        if (isListening) return
        isListening = true
        Log.i(TAG, "VehicleSignalBus started. Multi-source read-only pipeline active.")

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
}
