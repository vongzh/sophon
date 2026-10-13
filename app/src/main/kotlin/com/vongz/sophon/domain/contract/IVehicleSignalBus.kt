package com.vongz.sophon.domain.contract

import com.vongz.sophon.domain.model.DoorState
import com.vongz.sophon.domain.model.GearState
import com.vongz.sophon.domain.model.TurnSignalState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车身信号反应式监听总线 (Vehicle Signal Bus)
 *
 * 【安全红线 - 底线三 (Zero Tampering)】
 * 本接口为 100% 只读订阅者 (Read-Only Subscriber) 抽象。
 * 严禁在本接口及其实现中声明任何写方法 (如 setXXX, writeCAN, sendCommand)，
 * 确保对车辆底层电子电气架构绝对零侵入、零污染。
 */
interface IVehicleSignalBus {

    /**
     * 转向灯状态流 (左转、右转、双闪、关闭)
     * 用于驱动打灯补盲悬浮窗显示
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
     * 原厂 360 全景影像 (AVM) / 倒车影像激活状态
     * 关键安全信号：当此值为 true 时，补盲悬浮窗必须在 50ms 内无条件隐身避让
     */
    val isAvmActive: StateFlow<Boolean>

    /**
     * 车门开启状态流 (开门防碰撞预警)
     */
    val doorState: StateFlow<DoorState>

    /**
     * 驻车震动 / G-Sensor 哨兵唤醒事件流
     */
    val sentryTriggerEvents: SharedFlow<Long>

    /**
     * 启动信号监听
     */
    fun startListening()

    /**
     * 停止信号监听并释放底层 Binder / Socket 资源
     */
    fun stopListening()
}
