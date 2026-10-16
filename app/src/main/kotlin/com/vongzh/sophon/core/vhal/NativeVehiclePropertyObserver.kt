package com.vongzh.sophon.core.vhal

import android.content.Context
import android.util.Log
import com.vongzh.sophon.domain.model.GearState
import com.vongzh.sophon.domain.model.TurnSignalState
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Android 车规原生 VHAL 信号观察者 (NativeVehiclePropertyObserver)
 *
 * 【自立自强 - 0 外部应用依赖】
 * 直连车机底层 CarPropertyManager。采用反射 + 动态代理模式构建，
 * 既保证在标准 Android SDK 下零依赖编译通过，又在实际领克08/魅族FlymeAuto座舱底座中
 * 具备完整的原生毫秒级总线订阅能力。
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
        private const val EMERGENCY_DECEL_THRESHOLD_KMH_PER_S = 16f // 约 4.44 m/s² 强减速度阈值
        private const val EMERGENCY_DECEL_MIN_SPEED_KMH = 15f

        // VHAL Property IDs (Android Automotive OS 官方常量)
        private const val VEHICLE_PROPERTY_PERF_SPEED = 291504647 // 0x11600207
        private const val VEHICLE_PROPERTY_GEAR_SELECTION = 289408000 // 0x11400400
        private const val VEHICLE_PROPERTY_TURN_SIGNAL = 289408008 // 0x11400408

        private const val SENSOR_RATE_ONCHANGE = 0.0f
        private const val SENSOR_RATE_NORMAL = 3.0f
    }

    private var carInstance: Any? = null
    private var carPropertyManager: Any? = null
    private var callbackProxy: Any? = null
    private var isConnected = false

    private var lastSpeedKmh = 0f
    private var lastSpeedTimestampMs = 0L

    /**
     * 启动原生车身信号监听
     */
    fun start() {
        try {
            Log.i(TAG, "Connecting to Android Car Service via reflection...")
            val carClass = Class.forName("android.car.Car")

            // 创建 Car 实例: Car.createCar(context, handler, waitTimeout, serviceListener)
            val createCarMethod = carClass.methods.firstOrNull {
                it.name == "createCar" && it.parameterTypes.size == 4
            } ?: carClass.methods.firstOrNull {
                it.name == "createCar" && it.parameterTypes.size >= 1
            }

            if (createCarMethod == null) {
                Log.w(TAG, "Standard Car.createCar method not found. Entering passive mode.")
                return
            }

            val carObj = if (createCarMethod.parameterTypes.size == 1) {
                createCarMethod.invoke(null, context)
            } else {
                val serviceListenerClass = Class.forName("android.car.Car\$CarServiceLifecycleListener")
                val listenerProxy = Proxy.newProxyInstance(
                    carClass.classLoader,
                    arrayOf(serviceListenerClass),
                    CarServiceLifecycleHandler()
                )
                createCarMethod.invoke(null, context, null, -1L, listenerProxy)
            }

            if (carObj != null) {
                this.carInstance = carObj
                initCarPropertyManager(carObj)
            }
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "android.car.Car not present on this device (Standard Android mode).")
        } catch (e: Exception) {
            Log.w(TAG, "CarService connection reflection exception", e)
        }
    }

    private inner class CarServiceLifecycleHandler : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "onLifecycleChanged" && args != null && args.size >= 2) {
                val car = args[0]
                val ready = args[1] as? Boolean ?: false
                if (ready) {
                    carInstance = car
                    initCarPropertyManager(car)
                }
            }
            return null
        }
    }

    private fun initCarPropertyManager(car: Any) {
        try {
            val getCarManagerMethod = car.javaClass.getMethod("getCarManager", String::class.java)
            val mgr = getCarManagerMethod.invoke(car, "property") ?: return
            this.carPropertyManager = mgr

            val callbackInterface = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val proxy = Proxy.newProxyInstance(
                callbackInterface.classLoader,
                arrayOf(callbackInterface),
                CarPropertyEventHandler()
            )
            this.callbackProxy = proxy

            // 反射注册三个属性回调
            val registerMethod = mgr.javaClass.methods.firstOrNull {
                it.name == "registerCallback" && it.parameterTypes.size == 3 && it.parameterTypes[1] == Int::class.javaPrimitiveType
            }

            if (registerMethod != null) {
                registerMethod.invoke(mgr, proxy, VEHICLE_PROPERTY_PERF_SPEED, SENSOR_RATE_NORMAL)
                registerMethod.invoke(mgr, proxy, VEHICLE_PROPERTY_GEAR_SELECTION, SENSOR_RATE_ONCHANGE)
                registerMethod.invoke(mgr, proxy, VEHICLE_PROPERTY_TURN_SIGNAL, SENSOR_RATE_ONCHANGE)
                isConnected = true
                Log.i(TAG, "CarPropertyManager callbacks registered successfully.")
            } else {
                Log.w(TAG, "registerCallback method not matching signature on CarPropertyManager")
            }
        } catch (e: SecurityException) {
            // 权限未授予：普通第三方应用不会获得 CAR_* (signature|privileged) 授权，
            // 必须显式上抛，否则车速/挡位/转向灯会静默恒为默认值而被误判为真数据。
            signalBus.dispatchVhalAuthFailure(
                "CarPropertyManager 注册被拒绝：${e.message}。需声明 android.car.permission.* 且使用平台签名或预装至 /system/priv-app"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize CarPropertyManager", e)
        }
    }

    private inner class CarPropertyEventHandler : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "onChangeEvent" && args != null && args.isNotEmpty()) {
                val carPropValue = args[0]
                handlePropertyChanged(carPropValue)
            }
            return null
        }
    }

    private fun handlePropertyChanged(carPropValue: Any) {
        try {
            val getPropIdMethod = carPropValue.javaClass.getMethod("getPropertyId")
            val getValueMethod = carPropValue.javaClass.getMethod("getValue")

            val propId = getPropIdMethod.invoke(carPropValue) as? Int ?: return
            val value = getValueMethod.invoke(carPropValue) ?: return

            when (propId) {
                VEHICLE_PROPERTY_PERF_SPEED -> {
                    val speedMps = (value as? Number)?.toFloat() ?: return
                    val speedKmh = speedMps * 3.6f
                    val now = System.currentTimeMillis()

                    if (lastSpeedTimestampMs > 0L) {
                        val dtSec = (now - lastSpeedTimestampMs) / 1000f
                        if (dtSec in 0.1f..1.5f && lastSpeedKmh >= EMERGENCY_DECEL_MIN_SPEED_KMH) {
                            val decel = (lastSpeedKmh - speedKmh) / dtSec
                            if (decel >= EMERGENCY_DECEL_THRESHOLD_KMH_PER_S) {
                                Log.w(TAG, "EMERGENCY DECELERATION DETECTED! Drop: ${lastSpeedKmh - speedKmh} km/h in ${dtSec}s (Decel rate: $decel km/h/s)")
                                signalBus.dispatchEmergencyCollisionEvent("Emergency braking (Decel: %.1f km/h/s)".format(decel))
                            }
                        }
                    }

                    lastSpeedKmh = speedKmh
                    lastSpeedTimestampMs = now
                    signalBus.dispatchSpeedUpdate(speedKmh)
                }
                VEHICLE_PROPERTY_GEAR_SELECTION -> {
                    val gearInt = (value as? Number)?.toInt() ?: return
                    val gear = when (gearInt) {
                        1 -> GearState.PARK
                        2 -> GearState.REVERSE
                        4 -> GearState.NEUTRAL
                        8 -> GearState.DRIVE
                        else -> GearState.UNKNOWN
                    }
                    signalBus.dispatchGearUpdate(gear)
                }
                VEHICLE_PROPERTY_TURN_SIGNAL -> {
                    val stateInt = (value as? Number)?.toInt() ?: return
                    val state = when (stateInt) {
                        1 -> TurnSignalState.RIGHT
                        2 -> TurnSignalState.LEFT
                        3 -> TurnSignalState.HAZARD
                        else -> TurnSignalState.OFF
                    }
                    signalBus.dispatchTurnSignalUpdate(state)
                }
            }
        } catch (e: SecurityException) {
            signalBus.dispatchVhalAuthFailure("读取车身属性被拒绝：${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Error processing CarPropertyValue update", e)
        }
    }

    /**
     * 停止监听并注销回调
     */
    fun stop() {
        try {
            val mgr = carPropertyManager
            val proxy = callbackProxy
            if (mgr != null && proxy != null) {
                val unregisterMethod = mgr.javaClass.methods.firstOrNull {
                    it.name == "unregisterCallback" && it.parameterTypes.size >= 1
                }
                unregisterMethod?.invoke(mgr, proxy)
            }
            carInstance?.let { car ->
                val disconnectMethod = car.javaClass.methods.firstOrNull { it.name == "disconnect" }
                disconnectMethod?.invoke(car)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting CarService", e)
        } finally {
            carPropertyManager = null
            callbackProxy = null
            carInstance = null
            isConnected = false
        }
        Log.i(TAG, "Native CarPropertyManager disconnected.")
    }
}
