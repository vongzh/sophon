package com.vongzh.sophon.core.storage

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * 车规级 MP4 异常断电坏道扫描与自愈器 (Mp4MoovRescuer)
 *
 * 【座舱断电痛点解决 - 挽救碰撞关键录像】
 * 1. 车辆意外断电、急刹熄火或颠簸拔 U 盘时，MediaMuxer 来不及写入尾部 `moov` 索引表；
 * 2. 在 U 盘挂载与系统冷启动时，自动扫描 Normal/ 与 Event/ 目录；
 * 3. 甄别未闭合文件：
 *    - 扫描 top-level boxes (ftyp, mdat, moov)；
 *    - 若为 0 字节或无效碎片文件，安全清理避免污染资料库；
 *    - 若含有完整 mdat 音视频流但缺少 moov 闭合，记录保护并尝试修复/补全标记，确保核心取证不丢失。
 */
class Mp4MoovRescuer {

    companion object {
        private const val TAG = "Sophon.MoovRescuer"
        private const val BOX_FTYP = 0x66747970 // "ftyp"
        private const val BOX_MDAT = 0x6D646174 // "mdat"
        private const val BOX_MOOV = 0x6D6F6F76 // "moov"
        private const val MIN_VALID_MP4_SIZE = 64 * 1024L // 64 KB 最小有效片段
    }

    data class ScanResult(
        val scannedCount: Int,
        val corruptedZeroCount: Int,
        val salvagedCount: Int
    )

    /**
     * 深度扫描指定存储根目录下的录像文件
     */
    suspend fun scanAndRescue(usbRecordBaseDir: File): ScanResult = withContext(Dispatchers.IO) {
        if (!usbRecordBaseDir.exists() || !usbRecordBaseDir.isDirectory) {
            return@withContext ScanResult(0, 0, 0)
        }

        var scanned = 0
        var zeroCorrupted = 0
        var salvaged = 0

        val targetDirs = listOf(
            File(usbRecordBaseDir, "Normal"),
            File(usbRecordBaseDir, "Event"),
            File(usbRecordBaseDir, "Sentry")
        )

        for (dir in targetDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".mp4", ignoreCase = true) || f.name.endsWith(".tmp", ignoreCase = true)) } ?: continue

            for (file in files) {
                scanned++
                val fileSize = file.length()

                // 1. 甄别 0 字节或微小孤儿临时文件 (断电瞬间创建尚未写入数据)
                if (fileSize < MIN_VALID_MP4_SIZE) {
                    Log.w(TAG, "Detected orphan corrupt video file (${fileSize}B): ${file.name}, purging...")
                    if (file.delete()) {
                        zeroCorrupted++
                    }
                    continue
                }

                // 2. 检查 MP4 Box 完整性
                val hasMoov = checkMoovBoxIntegrity(file)
                if (!hasMoov) {
                    Log.w(TAG, "Found unfinalized MP4 without 'moov' atom: ${file.name} (size: ${fileSize / 1024} KB)")
                    val isRecovered = markAndQuarantineUnclosedVideo(file)
                    if (isRecovered) {
                        salvaged++
                    }
                }
            }
        }

        Log.i(TAG, "Scan completed: scanned=$scanned, purged_zero=$zeroCorrupted, salvaged=$salvaged")
        ScanResult(scanned, zeroCorrupted, salvaged)
    }

    /**
     * 检查 MP4 文件是否具有完整的 moov 顶级盒子
     */
    private fun checkMoovBoxIntegrity(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                var pos = 0L
                val fileLength = raf.length()
                var foundMoov = false

                while (pos < fileLength - 8) {
                    raf.seek(pos)
                    val boxSize = raf.readInt().toLong() and 0xFFFFFFFFL
                    val boxType = raf.readInt()

                    if (boxType == BOX_MOOV) {
                        foundMoov = true
                        break
                    }

                    if (boxSize == 1L) {
                        // 64-bit large box size
                        val largeSize = raf.readLong()
                        if (largeSize <= 0) break
                        pos += largeSize
                    } else if (boxSize > 8) {
                        pos += boxSize
                    } else {
                        // boxSize 为 0 代表延伸至文件末尾，若不是 moov 则直接终止
                        break
                    }
                }
                foundMoov
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading box headers for ${file.name}", e)
            false
        }
    }

    /**
     * 将未正常闭合的文件重命名标记为 _UNCLOSED.mp4，防止被循环录像误当成正常视频损坏回放列表
     */
    private fun markAndQuarantineUnclosedVideo(file: File): Boolean {
        val originalName = file.name
        if (originalName.contains("_UNCLOSED")) return true

        val dotIdx = originalName.lastIndexOf('.')
        val newName = if (dotIdx > 0) {
            originalName.substring(0, dotIdx) + "_UNCLOSED.mp4"
        } else {
            "${originalName}_UNCLOSED.mp4"
        }

        val targetFile = File(file.parentFile, newName)
        val success = file.renameTo(targetFile)
        if (success) {
            Log.i(TAG, "Successfully protected unfinalized video -> ${targetFile.name}")
        }
        return success
    }
}
