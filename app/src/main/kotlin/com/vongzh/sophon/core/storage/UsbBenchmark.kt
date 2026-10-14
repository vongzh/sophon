package com.vongzh.sophon.core.storage

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 外置 U 盘写入基准测速与车规健康体检 (UsbBenchmark)
 *
 * 【车规级存储健康红线】
 * 1. 四路 1080P/2K 视频流实时并发写入对 U 盘持续顺序写入带宽有严格要求；
 * 2. 避免劣质/损坏 U 盘导致掉帧、I/O 阻塞或卡死车机；
 * 3. 严格在外置 U 盘根目录执行 8MB 压测块写入，测完立即抹除，绝不写入机身内部闪存！
 */
class UsbBenchmark {

    companion object {
        private const val TAG = "Sophon.UsbBenchmark"
        private const val TEST_FILE_NAME = ".benchmark.tmp"
        private const val TEST_SIZE_BYTES = 8L * 1024 * 1024 // 8 MB 顺序基准测试块
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB 突发 I/O 块
    }

    enum class UsbHealthGrade(val label: String, val levelColor: String) {
        EXCELLENT("极佳 (>=25MB/s)", "#00E5FF"),
        GOOD("良好 (15~25MB/s)", "#00E676"),
        POOR("预警 (<15MB/s)", "#FF5252")
    }

    data class UsbHealthReport(
        val isSuccess: Boolean,
        val writeSpeedMBs: Double,
        val grade: UsbHealthGrade,
        val totalSpaceGB: Double,
        val freeSpaceGB: Double,
        val usedSpacePercent: Int,
        val errorMessage: String? = null,
        val summary: String
    )

    /**
     * 在指定外置 U 盘执行真实写入基准测速并进行健康体检
     */
    suspend fun runBenchmark(usbRoot: File): UsbHealthReport = withContext(Dispatchers.IO) {
        if (!usbRoot.exists() || !usbRoot.isDirectory || !usbRoot.canWrite()) {
            return@withContext UsbHealthReport(
                isSuccess = false,
                writeSpeedMBs = 0.0,
                grade = UsbHealthGrade.POOR,
                totalSpaceGB = 0.0,
                freeSpaceGB = 0.0,
                usedSpacePercent = 0,
                errorMessage = "U盘不可写或未正确挂载",
                summary = "U盘路径异常，无法进行写入体检"
            )
        }

        // 1. 容量与可用空间检测
        val stat = StatFs(usbRoot.absolutePath)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val freeBytes = availableBlocks * blockSize
        val totalGB = totalBytes / (1024.0 * 1024.0 * 1024.0)
        val freeGB = freeBytes / (1024.0 * 1024.0 * 1024.0)
        val usedPercent = if (totalBytes > 0) (((totalBytes - freeBytes) * 100) / totalBytes).toInt() else 0

        if (freeBytes < TEST_SIZE_BYTES * 2) {
            return@withContext UsbHealthReport(
                isSuccess = false,
                writeSpeedMBs = 0.0,
                grade = UsbHealthGrade.POOR,
                totalSpaceGB = totalGB,
                freeSpaceGB = freeGB,
                usedSpacePercent = usedPercent,
                errorMessage = "可用空间不足 16MB，无法完成基准测试",
                summary = "U盘空间极度告急，请立即清理"
            )
        }

        // 2. 基准顺序写入测速
        val testFile = File(usbRoot, TEST_FILE_NAME)
        var writeSpeedMBs = 0.0
        try {
            val buffer = ByteArray(BUFFER_SIZE) { 0xA5.toByte() }
            val startTime = System.nanoTime()

            FileOutputStream(testFile).use { fos ->
                var written = 0L
                while (written < TEST_SIZE_BYTES) {
                    val toWrite = minOf(BUFFER_SIZE.toLong(), TEST_SIZE_BYTES - written).toInt()
                    fos.write(buffer, 0, toWrite)
                    written += toWrite
                }
                fos.flush()
                fos.fd.sync() // 强制下刷至物理 NAND 颗粒
            }

            val endTime = System.nanoTime()
            val durationSec = (endTime - startTime) / 1_000_000_000.0

            writeSpeedMBs = if (durationSec > 0) {
                (TEST_SIZE_BYTES / (1024.0 * 1024.0)) / durationSec
            } else 0.0

            Log.i(TAG, "Benchmark completed: %.2f MB/s in %.3f s".format(writeSpeedMBs, durationSec))
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark write test failed", e)
            return@withContext UsbHealthReport(
                isSuccess = false,
                writeSpeedMBs = 0.0,
                grade = UsbHealthGrade.POOR,
                totalSpaceGB = totalGB,
                freeSpaceGB = freeGB,
                usedSpacePercent = usedPercent,
                errorMessage = e.message ?: "I/O 写入异常",
                summary = "U盘物理 I/O 阻塞，疑似颗粒老化或供电不稳定"
            )
        } finally {
            if (testFile.exists()) {
                testFile.delete()
            }
        }

        // 3. 评级分析与车规建议
        val grade = when {
            writeSpeedMBs >= 25.0 -> UsbHealthGrade.EXCELLENT
            writeSpeedMBs >= 15.0 -> UsbHealthGrade.GOOD
            else -> UsbHealthGrade.POOR
        }

        val summary = when (grade) {
            UsbHealthGrade.EXCELLENT -> "速度极佳 (%.1f MB/s)，完美支持四路 1080P60 极致画质实时并发录制".format(writeSpeedMBs)
            UsbHealthGrade.GOOD -> "速度良好 (%.1f MB/s)，满足四路 1080P30 标清并发录像稳定写入".format(writeSpeedMBs)
            UsbHealthGrade.POOR -> "速度预警 (%.1f MB/s < 15MB/s)，写入偏慢，长时间录制可能偶发丢帧，建议更换车载高耐久U盘".format(writeSpeedMBs)
        }

        UsbHealthReport(
            isSuccess = true,
            writeSpeedMBs = writeSpeedMBs,
            grade = grade,
            totalSpaceGB = totalGB,
            freeSpaceGB = freeGB,
            usedSpacePercent = usedPercent,
            summary = summary
        )
    }
}
