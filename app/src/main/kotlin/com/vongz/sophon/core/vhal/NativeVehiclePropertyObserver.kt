package com.vongz.sophon.core.vhal

import android.car.Car
import android.car.VehicleAreaType
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.vongz.sophon.domain.model.GearState
import com.vongz.sophon.domain.model.TurnSignalState

/**
 * Android 车规原生 VHAL 信号观察者 (NativeVehiclePropertyObserver)
 *
 * 【自立自强 - 0 外部应用依赖】
 * 彻底摒弃对第三方 EVCC 应用的依赖，直连车机底层 CarPropertyManager。
 *
 * 【安全红线 - 底线三 (Zero Tampering)】
 * 仅调用 registerCallback 监听属性，绝不调用 setProperty 下发控制指令。
 */
class NativeVehiclePropertyObserver(
    private val context: Context,
    private val signalBus: VehicleSignalBusImpl
) {

    companion object {
        private const val TAG = "Sophon.NativeVhalObserver"
    }

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private var isConnected = false

    /**
     * 启动原生车身信号监听
     */
    fun start() {
        try {
            Log.i(TAG, "Connecting to Android Car Service...")
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { carInstance, ready ->
                if (ready) {
                    onCarServiceReady(carInstance)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Standard CarService initialization failed. Entering fallback mode.", e)
        }
    }

    private fun onCarServiceReady(carInstance: Car) {
        try {
            carPropertyManager = carInstance.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            val mgr = carPropertyManager ?: return

            // 1. 监听车速 (PERF_VEHICLE_SPEED, 频率 10Hz)
            mgr.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)

            // 2. 监听变速箱挡位 (GEAR_SELECTION, 状态变化触发)
            mgr.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)

            // 3. 监听转向灯 (TURN_SIGNAL_STATE, 状态变化触发)
            mgr.registerCallback(turnSignalCallback, VehiclePropertyIds.TURN_SIGNAL_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE)

            isConnected = true
            Log.i(TAG, "Native CarPropertyManager callbacks registered successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register CarPropertyManager callbacks", e)
        }
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val speedMps = (value.value as? Float) ?: return
            val speedKmh = speedMps * 3.6f
            signalBus.dispatchSpeedUpdate(speedKmh)
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "Car speed property error: propId=$propId")
        }
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val gearInt = (value.value as? Int) ?: return
            val gear = when (gearInt) {
                1 -> GearState.PARK
                2 -> GearState.REVERSE
                4 -> GearState.NEUTRAL
                8 -> GearState.DRIVE
                else -> GearState.UNKNOWN
            }
            signalBus.dispatchGearUpdate(gear)
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "Gear property error: propId=$propId")
        }
    }

    private val turnSignalCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val stateInt = (value.value as? Int) ?: return
            val state = when (stateInt) {
                1 -> TurnSignalState.RIGHT
                2 -> TurnSignalState.LEFT
                else -> TurnSignalState.OFF
            }
            signalBus.dispatchTurnSignalUpdate(state)
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "Turn signal property error: propId=$propId")
        }
    }

    /**
     * 停止监听并注销回调
     */
    fun stop() {
        try {
            carPropertyManager?.let { mgr ->
                mgr.unregisterCallback(speedCallback)
                mgr.unregisterCallback(gearCallback)
                mgr.unregisterCallback(turnSignalCallback)
            }
            car?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting CarService", e)
        } finally {
            carPropertyManager = null
            car = null
            isConnected = false
        }
        Log.i(TAG, "Native CarPropertyManager disconnected.")
    }
}
