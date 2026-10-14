package com.vongz.sophon.domain.contract

import com.vongz.sophon.domain.model.DoorState
import com.vongz.sophon.domain.model.GearState
import com.vongz.sophon.domain.model.HardKeyEvent
import com.vongz.sophon.domain.model.TirePressureInfo
import com.vongz.sophon.domain.model.TurnSignalState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车身信号反应式监听总线 (Vehicle Signal Bus)
 *
 * 【全面对标 EVCC (com.kooo.evcc) 与 盯盯车】
 * 覆盖：转向灯、挡位、车速、方向盘转角、胎压胎温 (TPMS)、车门状态、安全带状态、硬件按键长按、AVM 全景避让。
 *
 * 【安全红线 - 底线三 (Zero Tampering)】
 * 100% 只读订阅者 (Read-Only Subscriber)。
 * 严禁声明任何写方法，不干预车辆任何 ECU 控制逻辑。
 */
interface IVehicleSignalBus {

    /**
     * 转向灯状态流 (OFF, LEFT, RIGHT, HAZARD)
     */
    val turnSignalState: StateFlow<TurnSignalState>

    /**
     * 车辆当前行驶速度 (单位: km/h)
     */
    val vehicleSpeed: StateFlow<Float>

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
     * 四轮胎压与胎温数据流 (TPMS 看板)
     */
    val tirePressureInfo: StateFlow<TirePressureInfo>

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
     * 启动信号监听
     */
    fun startListening()

    /**
     * 手动分发按键动作事件 (如方向盘物理按键)
     */
    fun dispatchHardKeyEvent(event: HardKeyEvent)

    /**
     * 停止信号监听并释放资源
     */
    fun stopListening()
}
