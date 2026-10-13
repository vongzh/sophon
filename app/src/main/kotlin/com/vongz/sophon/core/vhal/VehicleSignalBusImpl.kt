package com.vongz.sophon.core.vhal

import android.content.Context
import android.util.Log
import com.vongz.sophon.domain.contract.IVehicleSignalBus
import com.vongz.sophon.domain.model.DoorState
import com.vongz.sophon.domain.model.GearState
import com.vongz.sophon.domain.model.TurnSignalState
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
 * 【安全红线 - 底线三 (Zero Tampering)】
 * 1. 严格作为 Read-Only Subscriber 监听车身数据；
 * 2. 绝不向车身总线发送任何写报文；
 * 3. 毫秒级分发原厂 AVM (360全景) 信号，支撑 50ms 悬浮窗极速避让。
 */
class VehicleSignalBusImpl(
    private val context: Context
) : IVehicleSignalBus {

    companion object {
        private const val TAG = "Sophon.VehicleSignalBus"
    }

    private val busScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _turnSignalState = MutableStateFlow(TurnSignalState.OFF)
    override val turnSignalState: StateFlow<TurnSignalState> = _turnSignalState.asStateFlow()

    private val _vehicleSpeed = MutableStateFlow(0f)
    override val vehicleSpeed: StateFlow<Float> = _vehicleSpeed.asStateFlow()

    private val _gearState = MutableStateFlow(GearState.PARK)
    override val gearState: StateFlow<GearState> = _gearState.asStateFlow()

    private val _isAvmActive = MutableStateFlow(false)
    override val isAvmActive: StateFlow<Boolean> = _isAvmActive.asStateFlow()

    private val _doorState = MutableStateFlow(DoorState())
    override val doorState: StateFlow<DoorState> = _doorState.asStateFlow()

    private val _sentryTriggerEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    override val sentryTriggerEvents: SharedFlow<Long> = _sentryTriggerEvents.asSharedFlow()

    private var isListening = false

    override fun startListening() {
        if (isListening) return
        isListening = true
        Log.i(TAG, "VehicleSignalBus started. Read-Only subscriber mode ACTIVE.")
    }

    override fun stopListening() {
        if (!isListening) return
        isListening = false
        Log.i(TAG, "VehicleSignalBus stopped.")
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

    fun dispatchAvmStateUpdate(active: Boolean) {
        if (_isAvmActive.value != active) {
            Log.d(TAG, "AVM active state changed: $active (Priority: Emergency)")
            _isAvmActive.value = active
        }
    }

    fun dispatchDoorStateUpdate(door: DoorState) {
        _doorState.value = door
    }

    fun dispatchSentryVibrationEvent(timestampMs: Long) {
        busScope.launch {
            _sentryTriggerEvents.emit(timestampMs)
        }
    }
}
