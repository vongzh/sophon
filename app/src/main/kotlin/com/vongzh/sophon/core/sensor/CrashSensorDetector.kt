package com.vongzh.sophon.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * 车规级三轴加速度计碰撞与急刹感应器 (CrashSensorDetector)
 *
 * 【硬件级异常冲击与急刹感知】
 * 1. 监听 Android Sensor.TYPE_ACCELEROMETER / LINEAR_ACCELERATION；
 * 2. 动态分离重力矢量基线，提取车身突发剧烈冲击冲击加速度 (Impact G)；
 * 3. 超过设定灵敏度门限 (1.8G / 2.5G / 3.5G) 时即刻熔断并触发紧急录像锁存。
 */
class CrashSensorDetector(
    private val context: Context,
    private val onCrashDetected: (impactG: Float) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "Sophon.CrashDetector"
        private const val GRAVITY_STANDARD = 9.80665f
        private const val TRIGGER_COOLDOWN_MS = 10_000L // 10秒防抖冷却
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastTriggerTimeMs = 0L
    private var gravity = FloatArray(3) { 0f }
    private var isStarted = false

    fun start() {
        if (isStarted || accelerometer == null || sensorManager == null) return
        isStarted = true
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        Log.i(TAG, "CrashSensorDetector started with accelerometer.")
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        sensorManager?.unregisterListener(this)
        Log.i(TAG, "CrashSensorDetector stopped.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // 1. 低通滤波器平滑提取重力基线 (alpha = 0.8)
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        // 2. 高通滤波去除重力分量，提取纯净动态加速度
        val linearX = event.values[0] - gravity[0]
        val linearY = event.values[1] - gravity[1]
        val linearZ = event.values[2] - gravity[2]

        // 3. 计算 3D 加速度模长并归一化为 G 计数
        val magnitude = sqrt((linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble()).toFloat()
        val impactG = magnitude / GRAVITY_STANDARD

        val sensitivityIndex = com.vongzh.sophon.SophonApp.preferences.crashSensitivity
        val thresholdG = when (sensitivityIndex) {
            0 -> 3.5f // 低灵敏度
            2 -> 1.8f // 高灵敏度
            else -> 2.5f // 中灵敏度 (标准)
        }

        if (impactG >= thresholdG) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTimeMs > TRIGGER_COOLDOWN_MS) {
                lastTriggerTimeMs = now
                Log.w(TAG, "CRASH / EMERGENCY DECELERATION DETECTED! Impact: %.2f G (Threshold: %.1f G)".format(impactG, thresholdG))
                onCrashDetected(impactG)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
