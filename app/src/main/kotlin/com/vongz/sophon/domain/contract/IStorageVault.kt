package com.vongz.sophon.domain.contract

import com.vongz.sophon.domain.model.RecordCategory
import com.vongz.sophon.domain.model.StorageUsageBreakdown
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.OutputStream

/**
 * 车载安全存储金库接口 (Storage Vault)
 *
 * 【安全红线 - 底线二 (Zero Write to Internal Flash / UFS)】
 * 严禁向车机内置闪存 (/data, /sdcard, /storage/emulated/0) 写入任何视频、切片或抓拍文件。
 * 严格贯彻 "No USB, No Write"：未挂载外置 USB 存储设备时，写请求一律拒绝或丢弃。
 */
interface IStorageVault {

    /**
     * 外置 USB 存储设备挂载状态流
     */
    val isUsbMounted: StateFlow<Boolean>

    /**
     * 当前外置存储卷根目录 (若未插入则为 null)
     */
    val currentUsbRoot: StateFlow<File?>

    /**
     * 是否允许落盘写入 (isUsbMounted == true 且具备写权限 且 剩余空间充足)
     */
    val isWriteAllowed: Boolean

    /**
     * 请求打开一个用于视频切片写入的输出流
     *
     * @param category 录制分类 (Normal / Event / Sentry)
     * @param timestampMs 时间戳 (毫秒)
     * @param cameraTag 摄像头代号 (F / B / L / R 或 ALL)
     * @return 若 USB 正常且空间充足返回 OutputStream；若未插 U 盘或空间耗尽返回 null
     * @throws SecurityException 如果目标路径被篡改为内部闪存，立刻抛出异常熔断
     */
    @Throws(SecurityException::class)
    fun openChunkOutputStream(
        category: RecordCategory,
        timestampMs: Long,
        cameraTag: String
    ): OutputStream?

    /**
     * 获取外置 U 盘容量使用细分信息 (四色存储环形图数据源)
     */
    fun queryStorageBreakdown(): StorageUsageBreakdown

    /**
     * 执行按时间 FIFO 循环自动清理 (当剩余空间低于预警阈值时)
     * 仅清理 Normal 目录下的最早切片，Event 和 Sentry 目录受到写保护
     */
    fun pruneOldestRecordingsIfNeeded(): Boolean
}
