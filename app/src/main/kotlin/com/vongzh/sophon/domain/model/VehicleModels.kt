package com.vongzh.sophon.domain.model

/**
 * 转向灯状态枚举
 */
enum class TurnSignalState {
    OFF,
    LEFT,
    RIGHT,
    HAZARD
}

/**
 * 挡位状态枚举
 */
enum class GearState {
    UNKNOWN,
    PARK,
    REVERSE,
    NEUTRAL,
    DRIVE
}

/**
 * 车门与舱盖开启状态
 */
data class DoorState(
    val isDriverAjar: Boolean = false,
    val isPassengerAjar: Boolean = false,
    val isRearLeftAjar: Boolean = false,
    val isRearRightAjar: Boolean = false,
    val isTrunkAjar: Boolean = false,
    val isHoodAjar: Boolean = false
) {
    val isAnyAjar: Boolean
        get() = isDriverAjar || isPassengerAjar || isRearLeftAjar || isRearRightAjar || isTrunkAjar || isHoodAjar
}

/**
 * 车载物理/方向盘硬件按键动作
 */
enum class HardKeyEvent {
    STEERING_CUSTOM_CLICK,
    STEERING_CUSTOM_LONG_PRESS,
    REAR_FOG_LIGHT_LONG_PRESS,
    TURN_LEVER_LONG_PRESS,
    HORN_QUICK_SAVE;

    companion object {
        val SHORT_PRESS = STEERING_CUSTOM_CLICK
        val LONG_PRESS = STEERING_CUSTOM_LONG_PRESS
    }
}

/**
 * 录制分类 (严格隔离在 U 盘不同子目录)
 */
enum class RecordCategory(val dirName: String) {
    NORMAL("Normal"),
    EVENT("Event"),
    SENTRY("Sentry"),
    SNAPSHOT("Snapshot"),
    CLIPS("Clips")
}

/**
 * 摄像头方位枚举
 */
enum class CameraPosition(val channelId: Int, val tag: String) {
    FRONT(0, "F"),
    BACK(1, "B"),
    LEFT(2, "L"),
    RIGHT(3, "R")
}

/**
 * 存储容量分解统计 (单位: Bytes)
 */
data class StorageUsageBreakdown(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val drivingBytes: Long = 0L,
    val sentryBytes: Long = 0L,
    val photoBytes: Long = 0L,
    val otherBytes: Long = 0L
) {
    val usedBytes: Long
        get() = (totalBytes - freeBytes).coerceAtLeast(0L)
}

/**
 * 车规 GNSS 定位与经纬度遥测数据
 */
data class GnssTelemetry(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val speedKmh: Float = 0f,
    val bearingDegrees: Float = 0f,
    val accuracyMeters: Float = 0f,
    val timestampMs: Long = 0L,
    val hasFix: Boolean = false
) {
    fun toCoordinateString(): String {
        if (!hasFix) return "--"
        val latDir = if (latitude >= 0) "N" else "S"
        val lonDir = if (longitude >= 0) "E" else "W"
        return "%.4f°%s, %.4f°%s".format(kotlin.math.abs(latitude), latDir, kotlin.math.abs(longitude), lonDir)
    }
}

/**
 * 常用安全地理围栏区域 (SafeGeofence)
 *
 * 结合 GNSS 卫星定位，在进入家、公司等安全免巡逻区时自动撤防哨兵，
 * 从源头消除非必要待机电耗（借鉴特斯拉/理想地理围栏设计）。
 */
data class SafeGeofence(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f
) {
    /**
     * 计算当前经纬度与围栏中心的球面大圆距离 (Haversine 算法)
     */
    fun distanceToMeters(targetLat: Double, targetLon: Double): Double {
        val earthRadius = 6371000.0 // 地球平均半径(米)
        val dLat = Math.toRadians(targetLat - latitude)
        val dLon = Math.toRadians(targetLon - longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(targetLat)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    fun contains(targetLat: Double, targetLon: Double): Boolean {
        return distanceToMeters(targetLat, targetLon) <= radiusMeters
    }
}



