package com.vongz.sophon.domain.model

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
 * 车门开启状态
 */
data class DoorState(
    val isDriverAjar: Boolean = false,
    val isPassengerAjar: Boolean = false,
    val isRearLeftAjar: Boolean = false,
    val isRearRightAjar: Boolean = false,
    val isTrunkAjar: Boolean = false
) {
    val isAnyAjar: Boolean
        get() = isDriverAjar || isPassengerAjar || isRearLeftAjar || isRearRightAjar || isTrunkAjar
}

/**
 * 录制分类 (严格隔离在 U 盘不同子目录)
 */
enum class RecordCategory(val dirName: String) {
    NORMAL("Normal"),
    EVENT("Event"),
    SENTRY("Sentry"),
    SNAPSHOT("Snapshot")
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
)
