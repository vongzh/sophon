package com.vongzh.sophon.core.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.contract.IStorageVault
import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.domain.model.StorageUsageBreakdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车载安全存储金库实现 (StorageVaultImpl)
 *
 * 【安全红线 - 底线二 (Zero Write to Internal Flash / UFS)】
 * 1. 严格校验存储路径，若解析为车机内部闪存直接熔断抛出 SecurityException；
 * 2. 贯彻 "No USB, No Write" 准则；
 * 3. 自动多级目录维护与 FIFO 循环覆盖淘汰机制（严禁触碰 Event 与 Snapshot 保护区）；
 * 4. 集成 Mp4MoovRescuer 异常断电未闭合视频自动修复与孤儿坏道清理。
 */
class StorageVaultImpl(
    private val context: Context
) : IStorageVault {

    companion object {
        private const val TAG = "Sophon.StorageVault"
        private const val BASE_RECORD_DIR = "SophonRecord"
        private const val MIN_FREE_BYTES_THRESHOLD = 2L * 1024 * 1024 * 1024 // 2GB 最低保护阈值
        private const val AUTO_PRUNE_BYTES_THRESHOLD = 8L * 1024 * 1024 * 1024 // 8GB 启动清理阈值
        private const val EVENT_QUOTA_ALERT_BYTES = 25L * 1024 * 1024 * 1024 // 25GB 加锁事件预警阈值

        private const val GB = 1024L * 1024 * 1024
        private const val TRASH_DIR_NAME = ".Trash"

        /** 存储用量达到该比例即触发（对应设置项「存储超 90%」） */
        private const val USAGE_TRIGGER_RATIO = 0.90

        /** 热写入路径上配额评估的最小间隔，避免每次开切片都遍历目录 */
        private const val QUOTA_EVAL_MIN_INTERVAL_MS = 60_000L
    }

    private val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moovRescuer = Mp4MoovRescuer()

    private val _isUsbMounted = MutableStateFlow(false)
    override val isUsbMounted: StateFlow<Boolean> = _isUsbMounted.asStateFlow()

    private val _currentUsbRoot = MutableStateFlow<File?>(null)
    override val currentUsbRoot: StateFlow<File?> = _currentUsbRoot.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /** 上次配额评估时间戳，用于热写入路径节流 */
    private var lastQuotaEvalMs = 0L

    init {
        refreshStorageVolumes()
    }

    /**
     * 刷新并检测当前系统挂载的所有存储卷，精确锁定外置 U 盘
     */
    fun refreshStorageVolumes() {
        val externalDirs = context.getExternalFilesDirs(null)
        var detectedUsbDir: File? = null

        for (dir in externalDirs) {
            if (dir == null) continue
            // 排除系统主内部存储 (即 /storage/emulated/0)
            if (Environment.isExternalStorageEmulated(dir)) {
                continue
            }
            // 寻找真正的物理挂载 USB 卷
            if (dir.canWrite()) {
                var root = dir
                while (root.parentFile != null && root.parentFile?.absolutePath != "/storage") {
                    root = root.parentFile ?: break
                }
                detectedUsbDir = root
                break
            }
        }

        if (detectedUsbDir != null && isPathSafeExternal(detectedUsbDir)) {
            _currentUsbRoot.value = detectedUsbDir
            _isUsbMounted.value = true
            Log.i(TAG, "External USB detected & mounted: ${detectedUsbDir.absolutePath}")
            val baseDir = File(detectedUsbDir, BASE_RECORD_DIR)
            ensureDirectoryStructures(detectedUsbDir)

            // 挂载成功后，自动启动后台断电坏道与未闭合 MP4 拯救扫描
            vaultScope.launch {
                moovRescuer.scanAndRescue(baseDir)
            }
        } else {
            _currentUsbRoot.value = null
            _isUsbMounted.value = false
            Log.w(TAG, "No valid external USB storage detected! StorageVault enters NO_WRITE mode.")
        }
    }

    /**
     * 校验目标路径是否为合法外置存储，严禁渗透至车机内部存储
     */
    private fun isPathSafeExternal(file: File): Boolean {
        val canonical = file.canonicalPath
        if (canonical.startsWith("/data") ||
            canonical.startsWith("/sdcard") ||
            canonical.startsWith("/storage/emulated")
        ) {
            Log.e(TAG, "CRITICAL SECURITY BREACH: Attempted to target internal storage! Path: $canonical")
            return false
        }
        return true
    }

    private fun ensureDirectoryStructures(usbRoot: File) {
        val baseDir = File(usbRoot, BASE_RECORD_DIR)
        RecordCategory.values().forEach { category ->
            val catDir = File(baseDir, category.dirName)
            if (!catDir.exists()) {
                catDir.mkdirs()
            }
        }
    }

    override val isWriteAllowed: Boolean
        get() {
            val root = _currentUsbRoot.value ?: return false
            if (!_isUsbMounted.value) return false

            return try {
                val stat = StatFs(root.absolutePath)
                val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
                availableBytes > MIN_FREE_BYTES_THRESHOLD
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query storage stats", e)
                false
            }
        }

    override fun openChunkOutputStream(
        category: RecordCategory,
        timestampMs: Long,
        cameraTag: String
    ): OutputStream? {
        val usbRoot = _currentUsbRoot.value
        if (usbRoot == null || !isUsbMounted.value) {
            Log.w(TAG, "No USB attached. Skipping file write (No USB, No Write rule enforced).")
            return null
        }

        if (!isPathSafeExternal(usbRoot)) {
            throw SecurityException("Security Violation: Target path is internal flash!")
        }

        pruneOldestRecordingsIfNeeded()

        val timeStr = dateFormat.format(Date(timestampMs))
        val targetCategoryDir = File(File(usbRoot, BASE_RECORD_DIR), category.dirName)
        if (!targetCategoryDir.exists()) targetCategoryDir.mkdirs()

        val targetFile = File(targetCategoryDir, "${timeStr}_${cameraTag}.mp4")

        return try {
            FileOutputStream(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open output stream to USB: ${targetFile.absolutePath}", e)
            null
        }
    }

    override fun queryStorageBreakdown(): StorageUsageBreakdown {
        val usbRoot = _currentUsbRoot.value ?: return StorageUsageBreakdown()
        return try {
            val stat = StatFs(usbRoot.absolutePath)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong

            val baseDir = File(usbRoot, BASE_RECORD_DIR)
            val drivingBytes = calculateDirSize(File(baseDir, RecordCategory.NORMAL.dirName))
            val sentryBytes = calculateDirSize(File(baseDir, RecordCategory.SENTRY.dirName)) +
                    calculateDirSize(File(baseDir, RecordCategory.EVENT.dirName))
            val photoBytes = calculateDirSize(File(baseDir, RecordCategory.SNAPSHOT.dirName))

            val otherBytes = totalBytes - freeBytes - drivingBytes - sentryBytes - photoBytes

            StorageUsageBreakdown(
                totalBytes = totalBytes,
                freeBytes = freeBytes,
                drivingBytes = drivingBytes,
                sentryBytes = sentryBytes,
                photoBytes = photoBytes,
                otherBytes = maxOf(0L, otherBytes)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage breakdown", e)
            StorageUsageBreakdown()
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /**
     * 按用户在设置页配置的目录配额执行淘汰。
     *
     * 设计要点：
     * - **只入 .Trash，不做不可逆删除**：配额超限属于用户策略调整，误伤代价高，先进入回收缓冲，
     *   由 pruneOldestRecordingsIfNeeded() 在真正出现空间压力时才永久抹除；
     * - 与剩余空间安全网解耦：后者是写入连续性硬底线，不受触发时机影响；
     * - Snapshot 虽属 FIFO 保护区，但照片配额是用户显式设置的，超限仍要裁剪——同样只入 .Trash。
     */
    override fun enforceStorageQuotas(trigger: Int): Boolean {
        val prefs = SophonApp.preferences
        // 只有命中所选触发时机才执行
        if (prefs.storageCleanupTrigger != trigger) return false

        val usbRoot = _currentUsbRoot.value ?: return false
        if (!isUsbMounted.value) return false

        return try {
            val baseDir = File(usbRoot, BASE_RECORD_DIR)
            val trashDir = File(baseDir, TRASH_DIR_NAME).apply { if (!exists()) mkdirs() }
            var freedBytes = 0L

            // 1. 行车循环录像配额：超限后按「单次释放比例」淘汰最早的普通切片
            val videoQuotaBytes = prefs.storageVideoQuotaGb.coerceIn(1, 512) * GB
            val normalDir = File(baseDir, RecordCategory.NORMAL.dirName)
            val normalSize = calculateDirSize(normalDir)
            if (normalSize > videoQuotaBytes) {
                val ratio = prefs.storageCleanupRatioPercent.coerceIn(5, 100) / 100.0
                val targetFreed = (normalSize * ratio).toLong()
                val moved = stageOldestToTrash(trashDir, normalDir, targetFreed)
                freedBytes += moved
                if (moved > 0) {
                    Log.i(
                        TAG,
                        "Video quota exceeded (${normalSize / GB}GB > ${videoQuotaBytes / GB}GB). " +
                            "Staged ${moved / (1024 * 1024)}MB oldest chunks to .Trash."
                    )
                }
            }

            // 2. 抓拍照片配额：超限后回收最早的抓拍
            val photoQuotaBytes = prefs.storagePhotoQuotaGb.coerceIn(1, 128) * GB
            val snapDir = File(baseDir, RecordCategory.SNAPSHOT.dirName)
            val snapSize = calculateDirSize(snapDir)
            if (snapSize > photoQuotaBytes) {
                val moved = stageOldestToTrash(trashDir, snapDir, snapSize - photoQuotaBytes)
                freedBytes += moved
                if (moved > 0) {
                    Log.i(
                        TAG,
                        "Photo quota exceeded (${snapSize / GB}GB > ${photoQuotaBytes / GB}GB). " +
                            "Staged ${moved / (1024 * 1024)}MB oldest snapshots to .Trash."
                    )
                }
            }

            freedBytes > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce storage quotas: ${e.message}")
            false
        }
    }

    /**
     * 将 [srcDir] 中最早的文件移入 [trashDir]，直到累计释放 [targetBytes] 字节。
     * 目标文件重名时自动加后缀，避免 renameTo 失败后退化成直接删除（那会造成静默数据丢失）。
     */
    private fun stageOldestToTrash(trashDir: File, srcDir: File, targetBytes: Long): Long {
        if (targetBytes <= 0) return 0L
        val files = srcDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return 0L
        var movedBytes = 0L
        for (file in files) {
            val size = file.length()
            val target = uniqueTarget(trashDir, file.name)
            if (file.renameTo(target)) {
                movedBytes += size
                Log.d(TAG, "Staged to .Trash (quota): ${file.name}")
            } else {
                Log.w(TAG, "Failed to stage ${file.name} to .Trash, leaving in place")
                continue
            }
            if (movedBytes >= targetBytes) break
        }
        return movedBytes
    }

    private fun uniqueTarget(trashDir: File, name: String): File {
        var candidate = File(trashDir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists() && i < 1000) {
            candidate = File(trashDir, "${base}_$i$ext")
            i++
        }
        return candidate
    }

    override fun pruneOldestRecordingsIfNeeded(): Boolean {
        val usbRoot = _currentUsbRoot.value ?: return false
        try {
            val stat = StatFs(usbRoot.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val baseDir = File(usbRoot, BASE_RECORD_DIR)

            // 0. 用户所选触发时机为「存储超 90%」时评估整体用量。
            // 本方法位于每次开切片的热写入路径上，而配额检查需要遍历 Normal/Snapshot 目录；
            // 磁盘长期处于 90% 以上时会变成「每 1~3 分钟一次全目录 walk」，故加 60s 节流。
            val now = System.currentTimeMillis()
            if (now - lastQuotaEvalMs >= QUOTA_EVAL_MIN_INTERVAL_MS &&
                totalBytes > 0 &&
                (totalBytes - availableBytes).toDouble() / totalBytes >= USAGE_TRIGGER_RATIO
            ) {
                lastQuotaEvalMs = now
                enforceStorageQuotas(IStorageVault.CLEANUP_TRIGGER_USAGE_90)
            }

            // 1. 监测加锁保护区配额预警
            val eventBytes = calculateDirSize(File(baseDir, RecordCategory.EVENT.dirName)) +
                    calculateDirSize(File(baseDir, RecordCategory.SENTRY.dirName))
            if (eventBytes > EVENT_QUOTA_ALERT_BYTES) {
                Log.w(TAG, "WARNING: Protected Event/Sentry size exceeds quota (${eventBytes / (1024*1024*1024)}GB > 25GB).")
            }

            if (availableBytes > AUTO_PRUNE_BYTES_THRESHOLD) {
                return false
            }

            Log.i(TAG, "Free storage low ($availableBytes bytes). Staging oldest recordings to .Trash recycle buffer...")
            val trashDir = File(baseDir, ".Trash").apply { if (!exists()) mkdirs() }

            // 2. 先从 .Trash 彻底抹除超过空间限制的最旧文件
            val trashFiles = trashDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()
            var freedBytes = 0L
            for (f in trashFiles) {
                val size = f.length()
                if (f.delete()) {
                    freedBytes += size
                    Log.d(TAG, "Purged trash chunk permanently: ${f.name}")
                }
                if (availableBytes + freedBytes > AUTO_PRUNE_BYTES_THRESHOLD) {
                    return true
                }
            }

            // 3. 将 Normal 目录下最旧的视频平滑移入 .Trash 缓冲池（严禁触碰 Event/Sentry/Snapshot 保护区！）
            val normalDir = File(baseDir, RecordCategory.NORMAL.dirName)
            val normalFiles = normalDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return false

            for (file in normalFiles) {
                val targetTrashFile = File(trashDir, file.name)
                val size = file.length()
                if (file.renameTo(targetTrashFile)) {
                    Log.d(TAG, "Staged recording chunk to .Trash buffer: ${file.name}")
                } else if (file.delete()) {
                    freedBytes += size
                }
                if (freedBytes > (AUTO_PRUNE_BYTES_THRESHOLD - availableBytes)) {
                    break
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed during prune operation", e)
            return false
        }
    }
}
