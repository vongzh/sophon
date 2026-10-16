package com.vongzh.sophon.core.codec

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 座舱麦克风采集 + AAC 编码 (CabinAudioEncoder)
 *
 * 【为什么必须存在这个类】
 * 「座舱音频同步收音」开关此前是一条纯假设置：Manifest 声明了 RECORD_AUDIO、
 * 权限页把麦克风列为必需、主界面麦克风浮键会弹「车内录音已开启」，但全项目
 * 没有任何一处真正采集音频——录出来的 MP4 里根本没有音轨。这里把它补成真实现：
 * AudioRecord 采集 PCM → MediaCodec AAC-LC 编码 → 由 ContinuousRecorder 混入 MP4。
 *
 * 【关键取舍】
 * - 采样率优先 48kHz（车机平台普遍支持），失败回落 44.1kHz（Android 保证可用）。
 * - 时间戳以 System.nanoTime() 为基准、按已 consumed 样本数推进：与 Surface 输入的
 *   视频时间基准同源（编码器对 Surface 输入同样按单调时钟打时间戳），音画不会漂移。
 * - 单次 read 的 PCM 可能超过编码器输入缓冲容量，必须按 remaining() 分片投递，
 *   否则 put() 直接抛 BufferOverflowException 把整条音频链路打断。
 * - 停止必须等 drain 循环真正退出再回收编码器：job.cancel() 是协作式的，
 *   若紧接着就 codec.stop()，循环里正在进行的 dequeue/queue 会抛 IllegalStateException。
 */
class CabinAudioEncoder(
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {

    companion object {
        private const val TAG = "Sophon.CabinAudio"
        private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val BIT_RATE = 128_000
        private const val CHANNEL_COUNT = 1
        private const val TIMEOUT_US = 20_000L
        private const val STOP_WAIT_MS = 700L
        private val CANDIDATE_SAMPLE_RATES = intArrayOf(48_000, 44_100)

        /**
         * 构造 AAC-LC 的 AudioSpecificConfig（即 MediaMuxer 需要的 csd-0）。
         *
         * 位布局（MSB 优先，共 16 bit）：
         *   [5] audioObjectType = 2 (AAC-LC)
         *   [4] samplingFrequencyIndex
         *   [4] channelConfiguration
         *   [3] GASpecificConfig = frameLengthFlag 0 / dependsOnCoreCoder 0 / extensionFlag 0
         *
         * 之所以手工构造而不是等编码器吐出 outputFormat：MediaMuxer 要求所有轨道在
         * start() 之前 addTrack 完毕，而编码器首帧输出格式是异步到达的，切片创建时
         * 未必已就绪。这 16 bit 是标准可推导的，直接算出来最稳。
         */
        fun buildAacCsd(sampleRate: Int, channelCount: Int): ByteBuffer {
            val srIndex = when (sampleRate) {
                96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
                32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
                11025 -> 10; 8000 -> 11; 7350 -> 12
                else -> 4 // 未知采样率按 44100 申报，避免写出非法索引
            }
            val byte0 = ((2 shl 3) or (srIndex shr 1)).toByte()                    // AOT + 采样率索引高 3 位
            val byte1 = (((srIndex and 1) shl 7) or (channelCount shl 3)).toByte() // 索引低 1 位 + 声道配置 + GASpecificConfig 补零
            return ByteBuffer.allocate(2).put(byte0).put(byte1).apply { flip() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var running = false

    /** 释放兜底锁：drain 循环的 finally 与 stop() 的超时兜底只有一个能真正回收 */
    private val releaseGuard = Any()
    private var released = false
    private var stoppedLatch = CountDownLatch(1)

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null

    /** 实际采用的采样率；未启动为 0。供 ContinuousRecorder 构造音轨 MediaFormat。 */
    @Volatile
    var sampleRate: Int = 0
        private set

    val isRunning: Boolean get() = running

    /** 实例一次性：stop() 会取消协程作用域，之后不可再复用。 */
    @Volatile
    private var stopped = false

    /**
     * 启动采集。返回 false 表示无可用采样率、麦克风被占用、权限未授予，或实例已停止过。
     *
     * 调用方（ContinuousRecorder）每次都新建实例，因此「一次性」不构成使用负担，
     * 但可以杜绝「同一个实例被反复 start/stop」带来的状态错乱。
     */
    fun start(): Boolean {
        if (running) return true
        if (stopped) return false
        stoppedLatch = CountDownLatch(1)
        released = false
        for (rate in CANDIDATE_SAMPLE_RATES) {
            if (tryStart(rate)) {
                sampleRate = rate
                running = true
                Log.i(TAG, "Cabin audio capture active @ ${rate}Hz mono ${BIT_RATE / 1000}kbps AAC-LC.")
                return true
            }
        }
        Log.w(TAG, "座舱录音未启动：麦克风被占用、权限未授予或无可用采样率。")
        releaseResources()
        return false
    }

    private fun tryStart(rate: Int): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "RECORD_AUDIO not granted, cabin audio stays off.", e)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init failed @ ${rate}Hz", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        val enc = try {
            MediaCodec.createEncoderByType(MIME_AAC)
        } catch (e: Exception) {
            Log.w(TAG, "AAC encoder unavailable", e)
            rec.release()
            return false
        }

        val format = MediaFormat.createAudioFormat(MIME_AAC, rate, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", buildAacCsd(rate, CHANNEL_COUNT))
        }
        return try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            audioRecord = rec
            codec = enc
            job = scope.launch { drainLoop(rec, enc, rate, minBuf) }
            rec.startRecording()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start cabin audio pipeline @ ${rate}Hz", e)
            runCatching { enc.release() }
            runCatching { rec.release() }
            false
        }
    }

    private fun drainLoop(rec: AudioRecord, enc: MediaCodec, rate: Int, minBuf: Int) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(minBuf)
        val baseUs = System.nanoTime() / 1000L
        var samplesConsumed = 0L
        try {
            while (running && scope.isActive) {
                // 1. 采集 PCM 送入编码器。单次 read 可能大于编码器输入缓冲，必须分片投递。
                val read = rec.read(pcm, 0, pcm.size)
                if (read > 0) {
                    var consumed = 0
                    while (consumed < read && running && scope.isActive) {
                        val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx < 0) break
                        val buf = enc.getInputBuffer(inIdx) ?: break
                        val n = minOf(read - consumed, buf.remaining())
                        if (n <= 0) break
                        buf.clear()
                        buf.put(pcm, consumed, n)
                        val ptsUs = baseUs + (samplesConsumed / CHANNEL_COUNT) * 1_000_000L / rate
                        enc.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                        consumed += n
                        samplesConsumed += n / 2
                    }
                    if (consumed < read) {
                        // 编码器来不及消费：这段 PCM 只能丢弃，但时间戳必须照样推进，
                        // 否则后续音频会被整体提前，音画逐渐失同步。
                        samplesConsumed += (read - consumed) / 2
                    }
                }

                // 2. 取出编码后的 AAC 帧交给录制器混流
                val outIdx = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 已手工写入 csd-0，编码器再次声明的格式可忽略
                } else if (outIdx >= 0) {
                    val out = enc.getOutputBuffer(outIdx)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (out != null && info.size > 0 && !isConfig) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        runCatching { onEncoded(out, info) }
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cabin audio drain loop exited: ${e.message}")
        } finally {
            // 资源回收统一在这里做：stop() 可能来自主线程，不能在那里抢先 release
            releaseResources()
            stoppedLatch.countDown()
        }
    }

    /**
     * 停止采集。
     *
     * 必须先让 drain 循环退出再回收编码器：job.cancel() 是协作式的，循环可能在
     * dequeue/queue 中途，此时 codec.stop() 会抛 IllegalStateException 并让
     * AudioRecord 残留在占用状态（下次再开就再也拿不到麦克风）。
     */
    fun stop() {
        if (!running) return
        running = false
        stopped = true

        // AudioRecord.stop() 线程安全，且能立刻打断阻塞中的 read()
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }

        if (!stoppedLatch.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Drain loop did not exit in ${STOP_WAIT_MS}ms, forcing resource reclaim.")
        }
        releaseResources()

        job?.cancel()
        job = null
        runCatching { scope.cancel() }
        sampleRate = 0
        Log.i(TAG, "Cabin audio capture stopped.")
    }

    /** 幂等回收：drain 循环 finally 与 stop() 超时兜底可能同时到达 */
    private fun releaseResources() {
        synchronized(releaseGuard) {
            if (released) return
            released = true
            try {
                codec?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Audio codec stop failed", e)
            }
            runCatching { codec?.release() }
            runCatching { audioRecord?.release() }
            codec = null
            audioRecord = null
        }
    }
}
