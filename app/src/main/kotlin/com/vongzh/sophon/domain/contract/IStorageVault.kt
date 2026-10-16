package com.vongzh.sophon.domain.contract

import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.domain.model.StorageUsageBreakdown
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
     *
     * 注意：这是**写入连续性安全网**，与用户配额无关，任何触发时机下都必须执行。
     */
    fun pruneOldestRecordingsIfNeeded(): Boolean

    /**
     * 按用户在设置页配置的目录配额执行淘汰（行车视频 / 抓拍照片上限 + 单次释放比例）。
     *
     * 与 [pruneOldestRecordingsIfNeeded] 的区别：后者保的是"剩余空间"，本方法保的是"目录配额"。
     *
     * @param trigger 触发时机，取值见 [CLEANUP_TRIGGER_CHUNK_END] 等常量；
     *                仅当与用户所选时机一致时才真正执行
     * @return 是否实际释放了空间
     */
    fun enforceStorageQuotas(trigger: Int): Boolean

    companion object {
        /** 触发时机：每次录制结束（切片热切换时） */
        const val CLEANUP_TRIGGER_CHUNK_END = 0

        /** 触发时机：存储用量超过 90% */
        const val CLEANUP_TRIGGER_USAGE_90 = 1

        /** 触发时机：车机启动时 */
        const val CLEANUP_TRIGGER_BOOT = 2
    }
}
