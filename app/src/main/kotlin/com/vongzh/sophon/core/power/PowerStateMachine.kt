package com.vongzh.sophon.core.power

import android.util.Log
import com.vongzh.sophon.core.config.SophonPreferences
import com.vongzh.sophon.domain.contract.IVehicleSignalBus
import com.vongzh.sophon.domain.model.GearState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 芯擎科技 龙鹰一号 (SE1000) 领克08 整车电源与哨兵状态机 (PowerStateMachine)
 *
 * 【原厂顺风车休眠唤醒协同 & 12V 蓄电池防亏电红线】
 * 串联 FlymeAuto 底层 STR (Suspend-to-RAM) 广播、VHAL 挡位/车速/12V电瓶电压及车身震动传感器。
 * 驱动核心录制流水线在全功率行车录制、驻车待机、低功耗哨兵警戒、加锁告警及紧急断电保护之间平滑状态机流转。
 */
enum class PowerState {
    AWAKE_DRIVING,       // 行驶工作态: 挡位 D/R/N 或车速 > 0，10Mbps 满血录制，全开 OSD 遥测
    AWAKE_PARKED,        // 驻车工作态: P 挡静止但车辆点火，维持行车待机录制与画面实时输出
    SENTRY_STANDBY,      // 哨兵警戒态: 车辆离车落锁/休眠，低功耗码率 (3Mbps) 降噪录制，雷达/震动传感器就绪
    SENTRY_ALERT,        // 哨兵告警态: 侦测到车身冲击/震动，提升至 10Mbps 满血码率，触发加锁切片与远程多渠道声光告警
    EMERGENCY_POWER_CUT  // 紧急断电保护: 12V电瓶电压过低 (<11.8V) 或系统底层 STR Suspend 关机，立即强制闭合 MP4 moov 封包并释放硬件
}

/**
 * 目标 1: 哨兵能耗分级梯次 (SentryPowerTier)
 *
 * 借鉴小鹏与理想超低功耗哨兵设计：
 * - L0_STANDBY: 极省电待命，仅保留加速度计与微传感器，录制进入纯内存预录环形缓冲，不写 U 盘闪存，保护 U 盘寿命与车机电池；
 * - L1_PREVIEW: 视感警备，微震感知（如有人拉车门、轻靠），开启观察窗并启动视频观察；
 * - L2_ALERT: 确定威胁或强烈冲击，10Mbps 满血落盘写入 U 盘，触发声光与远程报警。
 */
enum class SentryPowerTier {
    L0_STANDBY,  // 超低功耗：纯内存环形预录，零闪存写入
    L1_PREVIEW,  // 视感戒备：微震先导唤醒
    L2_ALERT     // 满血告警：全量加密写盘与声光威慑
}

class PowerStateMachine(
    private val bus: IVehicleSignalBus,
    private val preferences: SophonPreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "Sophon.PowerStateMachine"
        private const val SENTRY_ALERT_HOLD_MS = 60_000L // 哨兵告警保持 60 秒后恢复待机
        private const val BATTERY_RECOVERY_VOLTS = 12.6f // 蓄电池保护解除回滞门限
    }

    private val _currentState = MutableStateFlow(PowerState.AWAKE_PARKED)
    val currentState: StateFlow<PowerState> = _currentState.asStateFlow()

    private val _currentSentryTier = MutableStateFlow(SentryPowerTier.L0_STANDBY)
    val currentSentryTier: StateFlow<SentryPowerTier> = _currentSentryTier.asStateFlow()

    private var alertTimerJob: Job? = null
    private var lastRecordedVoltage = 12.8f
    private var isSystemSleeping = false

    /** 围栏命中：在回家 / 公司等可信地点自动撤防哨兵 */
    private var isSentrySuppressedByGeofence = false

    fun start() {
        Log.i(TAG, "Starting PowerStateMachine monitoring...")
        observeGearAndSpeed()
        observeBatteryVoltage()
        observeSentryVibrationEvents()
    }

    private fun observeGearAndSpeed() {
        scope.launch {
            bus.gearState.collectLatest { gear ->
                evaluateDrivingState(gear, bus.vehicleSpeed.value)
            }
        }

        scope.launch {
            bus.vehicleSpeed.collectLatest { speed ->
                evaluateDrivingState(bus.gearState.value, speed)
            }
        }
    }

    private fun evaluateDrivingState(gear: GearState, speed: Float) {
        if (isSystemSleeping) {
            // 系统处于休眠/哨兵态，若检测到车速明显增加或挂出 P 挡，说明车辆被点火启动
            if (gear == GearState.DRIVE || gear == GearState.REVERSE || gear == GearState.NEUTRAL || speed > 2.0f) {
                isSystemSleeping = false
                transitionTo(PowerState.AWAKE_DRIVING, "Vehicle movement detected during sleep -> Ignition Awakened")
            }
            return
        }

        if (_currentState.value == PowerState.EMERGENCY_POWER_CUT) {
            // 紧急低电保护态下不响应常规挡位切换，等待电压恢复或系统唤醒
            return
        }

        val isDriving = (gear == GearState.DRIVE || gear == GearState.REVERSE || gear == GearState.NEUTRAL || speed > 1.0f)
        if (isDriving) {
            transitionTo(PowerState.AWAKE_DRIVING, "Gear: $gear, Speed: ${speed}km/h -> AWAKE_DRIVING")
        } else {
            transitionTo(PowerState.AWAKE_PARKED, "Gear: P, Speed: ${speed}km/h -> AWAKE_PARKED")
        }
    }

    private fun observeBatteryVoltage() {
        scope.launch {
            bus.batteryVoltage.collectLatest { volts ->
                if (volts <= 0f) return@collectLatest
                lastRecordedVoltage = volts

                if (preferences.isBatteryProtectionEnabled) {
                    val threshold = preferences.batteryProtectionThresholdVolts
                    if (volts < threshold && _currentState.value != PowerState.EMERGENCY_POWER_CUT) {
                        Log.e(TAG, "12V Battery Voltage below cutoff threshold (%.2fV < %.1fV)! Emergency power cut.".format(volts, threshold))
                        transitionTo(PowerState.EMERGENCY_POWER_CUT, "Battery Undervoltage Protection (%.2fV)".format(volts))
                    } else if (_currentState.value == PowerState.EMERGENCY_POWER_CUT && volts >= BATTERY_RECOVERY_VOLTS) {
                        Log.i(TAG, "12V Battery Voltage recovered to %.2fV (Alternator active). Re-evaluating state.".format(volts))
                        if (isSystemSleeping) {
                            transitionTo(PowerState.SENTRY_STANDBY, "Battery recovered during sleep -> SENTRY_STANDBY")
                        } else {
                            transitionTo(PowerState.AWAKE_PARKED, "Battery recovered -> AWAKE_PARKED")
                        }
                    }
                }
            }
        }
    }

    private fun observeSentryVibrationEvents() {
        scope.launch {
            bus.sentryTriggerEvents.collectLatest { timestampMs ->
                notifySentryVibration(timestampMs)
            }
        }
    }

    /**
     * 领克 08 原厂 STR (Suspend-to-RAM) / PRE_SLEEP / 关机信号触发
     */
    fun notifyStrSuspend() {
        isSystemSleeping = true
        alertTimerJob?.cancel()

        if (preferences.isBatteryProtectionEnabled && lastRecordedVoltage < preferences.batteryProtectionThresholdVolts) {
            transitionTo(PowerState.EMERGENCY_POWER_CUT, "Vehicle STR Suspend with battery below threshold (%.2fV)".format(lastRecordedVoltage))
            return
        }

        if (isSentrySuppressedByGeofence) {
            transitionTo(PowerState.EMERGENCY_POWER_CUT, "Vehicle STR Suspend -> Sentry disarmed by home geofence")
        } else if (preferences.isSentryModeEnabled) {
            transitionTo(PowerState.SENTRY_STANDBY, "Vehicle STR Suspend -> Sentry mode active")
        } else {
            transitionTo(PowerState.EMERGENCY_POWER_CUT, "Vehicle STR Suspend -> Sentry disabled, power cut")
        }
    }

    /**
     * 围栏状态变化 (回家自动撤防 / 离家自动布防)
     *
     * 命中围栏时不进入任何哨兵态；离开围栏且车辆已休眠时立即补回哨兵警戒。
     */
    fun setSentrySuppressedByGeofence(suppressed: Boolean) {
        if (isSentrySuppressedByGeofence == suppressed) return
        isSentrySuppressedByGeofence = suppressed

        if (suppressed) {
            if (_currentState.value == PowerState.SENTRY_STANDBY || _currentState.value == PowerState.SENTRY_ALERT) {
                alertTimerJob?.cancel()
                transitionTo(
                    PowerState.EMERGENCY_POWER_CUT,
                    "Home geofence hit -> Sentry disarmed at trusted location"
                )
            }
        } else if (isSystemSleeping && preferences.isSentryModeEnabled) {
            transitionTo(PowerState.SENTRY_STANDBY, "Left home geofence -> Sentry re-armed")
        }
    }

    /**
     * 领克 08 原厂 STR_RESUME / SYSTEM_READY 唤醒信号触发
     */
    fun notifyStrResume() {
        isSystemSleeping = false
        alertTimerJob?.cancel()

        val gear = bus.gearState.value
        val speed = bus.vehicleSpeed.value
        if (gear == GearState.DRIVE || gear == GearState.REVERSE || gear == GearState.NEUTRAL || speed > 1.0f) {
            transitionTo(PowerState.AWAKE_DRIVING, "Vehicle STR Resume -> AWAKE_DRIVING")
        } else {
            transitionTo(PowerState.AWAKE_PARKED, "Vehicle STR Resume -> AWAKE_PARKED")
        }
    }

    /**
     * 哨兵震动告警触发
     */
    fun notifySentryVibration(timestampMs: Long = System.currentTimeMillis()) {
        if (isSentrySuppressedByGeofence) {
            Log.i(TAG, "Sentry vibration ignored: suppressed by home geofence.")
            return
        }
        if (_currentState.value == PowerState.SENTRY_STANDBY || _currentState.value == PowerState.AWAKE_PARKED) {
            transitionTo(PowerState.SENTRY_ALERT, "Sentry shock triggered at $timestampMs")

            alertTimerJob?.cancel()
            alertTimerJob = scope.launch {
                delay(SENTRY_ALERT_HOLD_MS)
                if (_currentState.value == PowerState.SENTRY_ALERT) {
                    val returnState = if (isSystemSleeping) PowerState.SENTRY_STANDBY else PowerState.AWAKE_PARKED
                    transitionTo(returnState, "Sentry Alert hold duration expired -> Reverting")
                }
            }
        }
    }

    private fun transitionTo(newState: PowerState, reason: String) {
        val oldState = _currentState.value
        if (oldState == newState) return

        Log.i(TAG, "PowerState Transition: [$oldState] -> [$newState] (Reason: $reason)")
        _currentState.value = newState

        // 同步维护哨兵三级能耗梯次
        when (newState) {
            PowerState.SENTRY_STANDBY -> {
                _currentSentryTier.value = SentryPowerTier.L0_STANDBY
            }
            PowerState.SENTRY_ALERT -> {
                _currentSentryTier.value = SentryPowerTier.L2_ALERT
            }
            else -> {
                _currentSentryTier.value = SentryPowerTier.L0_STANDBY
            }
        }
    }
}
