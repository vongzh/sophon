package com.vongz.sophon.core.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.vongz.sophon.domain.contract.IStorageVault
import com.vongz.sophon.domain.model.RecordCategory
import com.vongz.sophon.domain.model.StorageUsageBreakdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * 3. 自动多级目录维护与 FIFO 循环覆盖淘汰机制。
 */
class StorageVaultImpl(
    private val context: Context
) : IStorageVault {

    companion object {
        private const val TAG = "Sophon.StorageVault"
        private const val BASE_RECORD_DIR = "SophonRecord"
        private const val MIN_FREE_BYTES_THRESHOLD = 2L * 1024 * 1024 * 1024 // 2GB 最低保护阈值
        private const val AUTO_PRUNE_BYTES_THRESHOLD = 8L * 1024 * 1024 * 1024 // 8GB 启动清理阈值
    }

    private val _isUsbMounted = MutableStateFlow(false)
    override val isUsbMounted: StateFlow<Boolean> = _isUsbMounted.asStateFlow()

    private val _currentUsbRoot = MutableStateFlow<File?>(null)
    override val currentUsbRoot: StateFlow<File?> = _currentUsbRoot.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

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
            ensureDirectoryStructures(detectedUsbDir)
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

    override fun pruneOldestRecordingsIfNeeded(): Boolean {
        val usbRoot = _currentUsbRoot.value ?: return false
        try {
            val stat = StatFs(usbRoot.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (availableBytes > AUTO_PRUNE_BYTES_THRESHOLD) {
                return false
            }

            Log.i(TAG, "Free storage low ($availableBytes bytes). Pruning oldest recordings...")
            val normalDir = File(File(usbRoot, BASE_RECORD_DIR), RecordCategory.NORMAL.dirName)
            val files = normalDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
                ?: return false

            var deletedBytes = 0L
            for (file in files) {
                val size = file.length()
                if (file.delete()) {
                    deletedBytes += size
                    Log.d(TAG, "Deleted old recording chunk: ${file.name}")
                }
                if (deletedBytes > (AUTO_PRUNE_BYTES_THRESHOLD - availableBytes)) {
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
