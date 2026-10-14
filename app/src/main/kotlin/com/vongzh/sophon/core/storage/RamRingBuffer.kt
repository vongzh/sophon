package com.vongzh.sophon.core.storage

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 纯内存安全环形缓冲区 (RAM Ring Buffer)
 *
 * 【安全红线 - 底线二 (Zero Write to Internal Flash)】
 * 1. 采用固定尺寸 DirectByteBuffer 堆外内存 (默认 8MB)，避免 GC 压力与车机堆内存膨胀；
 * 2. 仅作为向外置 U 盘写入的平滑缓冲区；
 * 3. 严格禁止溢出或回退写入车机内部闪存；若 U 盘写入卡顿达到警戒水位，执行安全丢弃策略 (Drop Policy)。
 */
class RamRingBuffer(
    val capacityBytes: Int = 8 * 1024 * 1024 // 8MB Direct Memory Pool
) {

    companion object {
        private const val TAG = "Sophon.RamRingBuffer"
    }

    private val directBuffer: ByteBuffer = ByteBuffer.allocateDirect(capacityBytes)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    private var head = 0 // 写入游标
    private var tail = 0 // 读取游标
    private var availableBytes = 0

    var droppedBytesTotal: Long = 0
        private set

    /**
     * 将编码后的音视频裸流数据块推入环形缓冲区
     */
    fun enqueue(source: ByteBuffer, length: Int): Int {
        if (length <= 0 || length > capacityBytes) return 0

        lock.withLock {
            val freeBytes = capacityBytes - availableBytes
            if (freeBytes < length) {
                // 水位已满，执行安全丢弃策略 (保护车机，绝不溢出到内部存储)
                droppedBytesTotal += length
                Log.w(TAG, "RingBuffer overflow! Dropping $length bytes. Total dropped: $droppedBytesTotal")
                return 0
            }

            val bytesToEnd = capacityBytes - head
            val dup = directBuffer.duplicate()
            dup.position(head)

            if (length <= bytesToEnd) {
                dup.put(source)
            } else {
                val firstChunk = bytesToEnd
                val tempSlice1 = source.slice()
                tempSlice1.limit(firstChunk)
                dup.put(tempSlice1)
                source.position(source.position() + firstChunk)

                val dupStart = directBuffer.duplicate()
                dupStart.position(0)
                dupStart.put(source)
            }

            head = (head + length) % capacityBytes
            availableBytes += length
            notEmpty.signal()
            return length
        }
    }

    /**
     * 从环形缓冲区中排空数据并写入外部目标
     */
    fun dequeue(destination: ByteArray, maxBytes: Int): Int {
        lock.withLock {
            if (availableBytes == 0) return 0

            val readLen = minOf(availableBytes, maxBytes, destination.size)
            val bytesToEnd = capacityBytes - tail

            if (readLen <= bytesToEnd) {
                val dup = directBuffer.duplicate()
                dup.position(tail)
                dup.get(destination, 0, readLen)
            } else {
                val firstChunk = bytesToEnd
                val secondChunk = readLen - bytesToEnd

                val dup = directBuffer.duplicate()
                dup.position(tail)
                dup.get(destination, 0, firstChunk)

                val dupStart = directBuffer.duplicate()
                dupStart.position(0)
                dupStart.get(destination, firstChunk, secondChunk)
            }

            tail = (tail + readLen) % capacityBytes
            availableBytes -= readLen
            return readLen
        }
    }

    fun clear() {
        lock.withLock {
            head = 0
            tail = 0
            availableBytes = 0
        }
    }

    val currentUsedBytes: Int
        get() = lock.withLock { availableBytes }
}
