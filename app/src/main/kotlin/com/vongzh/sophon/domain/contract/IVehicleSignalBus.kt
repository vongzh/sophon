package com.vongzh.sophon.domain.contract

import com.vongzh.sophon.domain.model.DoorState
import com.vongzh.sophon.domain.model.GearState
import com.vongzh.sophon.domain.model.HardKeyEvent
import com.vongzh.sophon.domain.model.TurnSignalState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车身信号反应式监听总线 (Vehicle Signal Bus)
 *
 * 【车规级全域信号感知】
 * 覆盖：转向灯、挡位、车速、方向盘转角、胎压胎温 (TPMS)、车门状态、安全带状态、
 * 12V 蓄电池电压 (低压切断守护)、刹车踏板状态、硬件按键长按、AVM 全景避让。
 *
 * 【安全红线 - 底线三 (Zero Tampering)】
 * 100% 只读订阅者 (Read-Only Subscriber)。
 * 严禁声明任何写方法，不干预车辆任何 ECU 控制逻辑。
 */
interface IVehicleSignalBus {

    /**
     * 12V 蓄电池电压 (伏特，用于哨兵低压切断保护)
     */
    val batteryVoltage: StateFlow<Float>

    /**
     * 刹车踏板踩下状态 (用于 OSD 水印与急刹感知)
     */
    val isBraking: StateFlow<Boolean>

    /**
     * 转向灯状态流 (OFF, LEFT, RIGHT, HAZARD)
     */
    val turnSignalState: StateFlow<TurnSignalState>

    /**
     * 车辆当前行驶速度 (单位: km/h)
     */
    val vehicleSpeed: StateFlow<Float>

    /**
     * 车外环境温度 (摄氏度，null 表示未获取或未授权)
     */
    val outsideTemperature: StateFlow<Float?>

    /**
     * 变速箱挡位状态 (P / R / N / D)
     */
    val gearState: StateFlow<GearState>

    /**
     * 方向盘转向角 (度数，负值为左打，正值为右打)
     * 用于低速大转角自适应开启盲区补盲
     */
    val steeringAngle: StateFlow<Float>

    /**
     * 主驾安全带佩戴状态
     */
    val isDriverSeatbeltFastened: StateFlow<Boolean>

    /**
     * 原厂 360 全景影像 (AVM) / 倒车影像激活状态 (50ms 避让信号)
     */
    val isAvmActive: StateFlow<Boolean>

    /**
     * 车门开启状态流 (开门防碰撞预警)
     */
    val doorState: StateFlow<DoorState>

    /**
     * 方向盘/物理按键动作流 (长按抓拍/锁存)
     */
    val hardKeyEvents: SharedFlow<HardKeyEvent>

    /**
     * 驻车震动 / G-Sensor 哨兵唤醒事件流
     */
    val sentryTriggerEvents: SharedFlow<Long>

    /**
     * 行车急刹 / 碰撞异常事件流 (携带判定原因)
     */
    val emergencyCollisionEvents: SharedFlow<String>

    /**
     * GNSS / GPS 经纬度与定位遥测数据 (只读流，无定位硬件或无锁定时 hasFix 为 false)
     */
    val gnssTelemetry: StateFlow<com.vongzh.sophon.domain.model.GnssTelemetry>

    /**
     * VHAL 信号授权错误/安全异常告警流 (未提权时上抛错误描述，无异常时为 null)
     */
    val vhalAuthError: StateFlow<String?>

    /**
     * 目标 2：鸣笛状态流 (借鉴特斯拉鸣笛瞬时加锁存证)
     */
    val isHornActive: StateFlow<Boolean>

    /**
     * 启动信号监听
     */
    fun startListening()

    /**
     * 手动分发按键动作事件 (如方向盘物理按键)
     */
    fun dispatchHardKeyEvent(event: HardKeyEvent)

    /**
     * 分发车辆鸣笛事件 (true: 鸣笛中，false: 鸣笛结束)
     */
    fun dispatchHornEvent(active: Boolean)

    /**
     * 停止信号监听并释放资源
     */
    fun stopListening()
}
