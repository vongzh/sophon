package com.vongzh.sophon.core.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class Mp4MoovRescuerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val rescuer = Mp4MoovRescuer()

    @Test
    fun testEmptyDirectoryScan() = runBlocking {
        val rootDir = tempFolder.newFolder("SophonRecord")
        val result = rescuer.scanAndRescue(rootDir)
        assertEquals(0, result.scannedCount)
        assertEquals(0, result.corruptedZeroCount)
        assertEquals(0, result.salvagedCount)
    }

    @Test
    fun testPurgeOrphanZeroAndTinyFiles() = runBlocking {
        val rootDir = tempFolder.newFolder("SophonRecord")
        val normalDir = File(rootDir, "Normal").apply { mkdirs() }

        // 创建一个 0 字节文件和 1KB 的孤儿坏道碎片 (< 64KB)
        val zeroFile = File(normalDir, "20261019_120000_F.mp4").apply { writeBytes(ByteArray(0)) }
        val tinyFile = File(normalDir, "20261019_120100_F.tmp").apply { writeBytes(ByteArray(1024)) }

        val result = rescuer.scanAndRescue(rootDir)
        assertEquals(2, result.scannedCount)
        assertEquals(2, result.corruptedZeroCount)
        assertEquals(0, result.salvagedCount)

        assertFalse("0 字节损坏文件应被清理", zeroFile.exists())
        assertFalse("碎片临时文件应被清理", tinyFile.exists())
    }

    @Test
    fun testKeepValidMp4WithMoov() = runBlocking {
        val rootDir = tempFolder.newFolder("SophonRecord")
        val normalDir = File(rootDir, "Normal").apply { mkdirs() }

        // 构建一个包含合法 ftyp 与 moov 顶层 box 的模拟 MP4 文件 (>= 64KB)
        val validFile = File(normalDir, "20261019_120200_ALL.mp4")
        RandomAccessFile(validFile, "rw").use { raf ->
            // 1. ftyp box (32 bytes)
            raf.writeInt(32)
            raf.writeInt(0x66747970) // 'ftyp'
            raf.write(ByteArray(24))

            // 2. free box (8 bytes - 测试正好 8 字节的无 payload box)
            raf.writeInt(8)
            raf.writeInt(0x66726565) // 'free'

            // 3. moov box (128 bytes)
            raf.writeInt(128)
            raf.writeInt(0x6D6F6F76) // 'moov'
            raf.write(ByteArray(120))

            // 4. 填充至超过 64KB
            raf.setLength(70 * 1024L)
        }

        val result = rescuer.scanAndRescue(rootDir)
        assertEquals(1, result.scannedCount)
        assertEquals(0, result.corruptedZeroCount)
        assertEquals(0, result.salvagedCount)
        assertTrue("包含完整 moov 的文件应完好保留", validFile.exists())
    }

    @Test
    fun testSalvageUnclosedMp4WithoutMoov() = runBlocking {
        val rootDir = tempFolder.newFolder("SophonRecord")
        val eventDir = File(rootDir, "Event").apply { mkdirs() }

        // 模拟断电异常中断的视频：有 ftyp 和 mdat，但未写入尾部 moov
        val unclosedFile = File(eventDir, "20261019_120300_F.mp4")
        RandomAccessFile(unclosedFile, "rw").use { raf ->
            // ftyp box (32 bytes)
            raf.writeInt(32)
            raf.writeInt(0x66747970) // 'ftyp'
            raf.write(ByteArray(24))

            // mdat box (延伸，无 moov)
            raf.writeInt(1000)
            raf.writeInt(0x6D646174) // 'mdat'
            raf.write(ByteArray(992))

            raf.setLength(80 * 1024L)
        }

        val result = rescuer.scanAndRescue(rootDir)
        assertEquals(1, result.scannedCount)
        assertEquals(0, result.corruptedZeroCount)
        assertEquals(1, result.salvagedCount)

        assertFalse("原文件名应已重命名隔离", unclosedFile.exists())
        val quarantinedFile = File(eventDir, "20261019_120300_F_UNCLOSED.mp4")
        assertTrue("应生成保护隔离的 _UNCLOSED 文件供后续修复取证", quarantinedFile.exists())
    }
}
