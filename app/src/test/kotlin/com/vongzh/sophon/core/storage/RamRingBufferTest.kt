package com.vongzh.sophon.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RamRingBufferTest {

    @Test
    fun testSimpleEnqueueAndDequeue() {
        val buffer = RamRingBuffer(capacityBytes = 1024)
        assertEquals(0, buffer.currentUsedBytes)

        val testData = "Hello Sophon Automotive Cockpit".toByteArray(Charsets.UTF_8)
        val src = ByteBuffer.wrap(testData)

        val written = buffer.enqueue(src, testData.size)
        assertEquals(testData.size, written)
        assertEquals(testData.size, buffer.currentUsedBytes)

        val out = ByteArray(testData.size)
        val read = buffer.dequeue(out, testData.size)
        assertEquals(testData.size, read)
        assertArrayEquals(testData, out)
        assertEquals(0, buffer.currentUsedBytes)
    }

    @Test
    fun testPartialDequeue() {
        val buffer = RamRingBuffer(capacityBytes = 1024)
        val testData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        buffer.enqueue(ByteBuffer.wrap(testData), testData.size)

        val firstHalf = ByteArray(4)
        val read1 = buffer.dequeue(firstHalf, 4)
        assertEquals(4, read1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstHalf)
        assertEquals(4, buffer.currentUsedBytes)

        val secondHalf = ByteArray(4)
        val read2 = buffer.dequeue(secondHalf, 4)
        assertEquals(4, read2)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), secondHalf)
        assertEquals(0, buffer.currentUsedBytes)
    }

    @Test
    fun testCircularWrapAround() {
        // 容量 16 字节，方便触发跨越末尾回绕
        val buffer = RamRingBuffer(capacityBytes = 16)

        // 写入 10 字节，读出 10 字节 -> head=10, tail=10
        val data1 = ByteArray(10) { (it + 1).toByte() }
        buffer.enqueue(ByteBuffer.wrap(data1), 10)
        val read1 = ByteArray(10)
        buffer.dequeue(read1, 10)
        assertArrayEquals(data1, read1)
        assertEquals(0, buffer.currentUsedBytes)

        // 此时 head 为 10，剩余末尾空间为 6 字节。写入 12 字节将跨越末尾折回头部
        val data2 = ByteArray(12) { (it + 100).toByte() }
        val written2 = buffer.enqueue(ByteBuffer.wrap(data2), 12)
        assertEquals(12, written2)
        assertEquals(12, buffer.currentUsedBytes)

        // 读取 12 字节，验证跨越环形缓冲两段的数据一致性
        val read2 = ByteArray(12)
        val dequeued2 = buffer.dequeue(read2, 12)
        assertEquals(12, dequeued2)
        assertArrayEquals(data2, read2)
        assertEquals(0, buffer.currentUsedBytes)
    }

    @Test
    fun testOverflowDropPolicy() {
        val buffer = RamRingBuffer(capacityBytes = 100)

        val data1 = ByteArray(80) { 1 }
        val written1 = buffer.enqueue(ByteBuffer.wrap(data1), 80)
        assertEquals(80, written1)
        assertEquals(0L, buffer.droppedBytesTotal)

        // 再尝试写入 30 字节（80 + 30 > 100），触发保护性丢弃，禁止内存溢出
        val data2 = ByteArray(30) { 2 }
        val written2 = buffer.enqueue(ByteBuffer.wrap(data2), 30)
        assertEquals(0, written2)
        assertEquals(30L, buffer.droppedBytesTotal)
        assertEquals(80, buffer.currentUsedBytes) // 原有数据保持完整
    }

    @Test
    fun testClear() {
        val buffer = RamRingBuffer(capacityBytes = 64)
        val data = ByteArray(32) { 42 }
        buffer.enqueue(ByteBuffer.wrap(data), 32)
        assertEquals(32, buffer.currentUsedBytes)

        buffer.clear()
        assertEquals(0, buffer.currentUsedBytes)

        val dest = ByteArray(32)
        val read = buffer.dequeue(dest, 32)
        assertEquals(0, read)
    }
}
