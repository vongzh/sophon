package com.vongzh.sophon.core.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.vongzh.sophon.core.storage.RamRingBuffer
import com.vongzh.sophon.domain.contract.IRecordingPipeline
import com.vongzh.sophon.domain.contract.IStorageVault
import com.vongzh.sophon.domain.model.RecordCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 零掉帧连续硬件编码与无感切片流水线实现 (ContinuousRecorder)
 *
 * 【性能红线 - 0 毫秒无感切换】
 * 彻底消除传统行车记录仪切片丢帧缺陷：
 * 1. 龙鹰一号 MediaCodec 硬件实例在录制全程持续运转，永不停机；
 * 2. 切片到期时请求 IDR 关键帧，以流式热切换无感轮换目标文件；
 * 3. 严格直连 RamRingBuffer 与 StorageVault，落实 "No USB, No Write"。
 */
class ContinuousRecorder(
    private val storageVault: IStorageVault,
    private val ramRingBuffer: RamRingBuffer,
    val width: Int = 1920,
    val height: Int = 1080
) : IRecordingPipeline {

    companion object {
        private const val TAG = "Sophon.ContinuousRecorder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BIT_RATE = 10_000_000 // 10 Mbps 车规码率
        private const val FRAME_RATE = 25
        private const val I_FRAME_INTERVAL = 1 // 1秒一个关键帧
        private const val CHUNK_DURATION_MS = 60_000L // 1分钟一个标准切片
    }

    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    override val recordingDurationSec: StateFlow<Long> = _recordingDurationSec.asStateFlow()

    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var currentMuxer: MediaMuxer? = null
    private var currentVideoTrackIndex = -1
    private var currentAudioTrackIndex = -1
    private var isMuxerStarted = false
    private var cachedOutputFormat: MediaFormat? = null

    /** 座舱麦克风采集器；为 null 表示当前无音轨（用户关闭 / 权限未授予 / 启动失败） */
    private var cabinAudio: CabinAudioEncoder? = null

    /** MediaMuxer 非线程安全：视频 drain、音频 drain、切片热切换都会碰它，统一加锁 */
    private val muxerLock = Any()

    private var baseCategory = RecordCategory.NORMAL
    private var currentCategory = RecordCategory.NORMAL
    private val shouldSliceAtNextKeyframe = AtomicBoolean(false)
    private var sliceTimerJob: Job? = null
    private var durationTimerJob: Job? = null

    /** 当前切片的首帧基准 PTS (微秒)，保证每个输出切片视频从 0 开始 */
    @Volatile
    private var chunkBasePtsUs: Long = -1L

    /**
     * 动态设置基础录像分类 (NORMAL 行车录制 vs SENTRY 哨兵警戒)
     */
    fun setBaseCategory(category: RecordCategory) {
        this.baseCategory = category
        if (this.currentCategory != RecordCategory.EVENT) {
            this.currentCategory = category
        }
    }

    /**
     * 动态平滑调节编码码率 (如行车 10Mbps, 哨兵 3Mbps)
     */
    fun updateBitrate(newBitrateBps: Int) {
        if (!_isRecording.value) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            }
            mediaCodec?.setParameters(params)
            Log.i(TAG, "Dynamic encoder bitrate updated to: ${newBitrateBps / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update bitrate on active MediaCodec", e)
        }
    }

    /**
     * 初始化 MediaCodec 硬件编码器并创建 Input Surface
     */
    fun prepare(): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        this.inputSurface = surface
        this.mediaCodec = codec
        Log.i(TAG, "MediaCodec encoder prepared successfully.")
        return surface
    }

    override fun startRecording(): Boolean {
        if (_isRecording.value) return true
        if (!storageVault.isWriteAllowed) {
            Log.w(TAG, "StorageVault rejected write (No USB attached). Recording stays in standby.")
            return false
        }

        val codec = mediaCodec ?: return false
        codec.start()
        _isRecording.value = true
        currentCategory = baseCategory
        // 录音按用户开关起播：此前该开关只改 UI 图标，实际不采集任何音频
        applyAudioTrackState()
        startNewMuxerChunk(currentCategory)

        // 启动编码数据轮询输出协程
        recorderScope.launch { drainEncoderLoop() }
        // 启动切片定时器与计时器
        startSliceSchedule()

        Log.i(TAG, "Continuous recording pipeline ACTIVE (Category: $currentCategory).")
        return true
    }

    /**
     * 座舱录音开关热生效。
     *
     * MediaMuxer 的轨道只能在 start() 之前增删，因此开关无法在切片中途改变当前文件；
     * 这里在切换采集器后立即请求一次切片，让新状态在下一个切片（毫秒级后）生效，
     * 而不是让用户等满当前切片才看到变化。
     */
    /**
     * 座舱音轨此刻是否真的在采集。
     *
     * UI 文案必须读这个真实状态而不是 `prefs.isAudioRecordEnabled`——开关开着却拿不到
     * 麦克风（未授权 / 被占用 / 未在录制）时，按偏好值显示「录音开启」就是新的假状态。
     */
    fun isCabinAudioActive(): Boolean = cabinAudio?.isRunning == true

    /**
     * @return 座舱音轨是否真的处于采集状态。返回 false 时调用方必须如实提示用户，
     *         否则开关在 UI 上是「开」、录出来的文件却没有音轨——又一条假设置。
     */
    fun applyAudioTrackState(): Boolean {
        val enabled = com.vongzh.sophon.SophonApp.preferences.isAudioRecordEnabled
        // 未在录制时不允许麦克风空转：没有消费者，还会让车机误以为 App 一直在收音
        val shouldRun = enabled && _isRecording.value

        if (!shouldRun) {
            if (cabinAudio != null) {
                cabinAudio?.stop()
                cabinAudio = null
                if (_isRecording.value) requestChunkSlice()
            }
            return false
        }

        val current = cabinAudio
        if (current == null || !current.isRunning) {
            // 旧实例可能处于「启动失败但已持有 AudioRecord」的状态，不先 stop 就换新的
            // 会把麦克风留在占用状态，之后再也拿不到音轨。
            current?.stop()
            val encoder = CabinAudioEncoder { data, info -> writeAudioSample(data, info) }
            if (!encoder.start()) {
                cabinAudio = null
                Log.w(TAG, "座舱录音未启动（权限未授予 / 麦克风被占用），本轮录制无音轨。")
                requestChunkSlice()
                return false
            }
            cabinAudio = encoder
        }
        requestChunkSlice()
        return true
    }

    /** 构造当前切片的音轨格式；未启用或无可用采样率时返回 null（纯视频切片） */
    private fun buildAudioTrackFormat(): MediaFormat? {
        val rate = cabinAudio?.sampleRate ?: 0
        if (rate <= 0) return null
        return MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", CabinAudioEncoder.buildAacCsd(rate, 1))
        }
    }

    private fun writeAudioSample(data: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (!isMuxerStarted || currentAudioTrackIndex < 0) return
            // 音频采样与当前切片基准 PTS 对齐，视频首帧关键帧未就绪前丢弃音频首包，避免无视频先出音频
            val basePts = chunkBasePtsUs
            if (basePts < 0L) return
            val normalizedPts = (info.presentationTimeUs - basePts).coerceAtLeast(0L)
            val adjustedInfo = MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, normalizedPts, info.flags)
            }
            try {
                currentMuxer?.writeSampleData(currentAudioTrackIndex, data, adjustedInfo)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing audio sample to MediaMuxer", e)
            }
        }
    }

    /**
     * 切片时长热生效入口。
     *
     * 切片定时器此前只在 startRecording() 时按当时偏好建立一次，用户在设置页把
     * 1/3/5 分钟改掉后必须重启录制才生效——实际表现就是「改了没反应」。
     * 这里重挂定时器；已经累计的时长不回退，新时长从下一次切片起算。
     */
    fun applyChunkDuration() {
        if (!_isRecording.value) return
        startSliceSchedule()
        Log.i(TAG, "Slice duration hot-applied: ${com.vongzh.sophon.SophonApp.preferences.chunkDurationSec}s")
    }

    private fun startSliceSchedule() {
        val durationMs = (com.vongzh.sophon.SophonApp.preferences.chunkDurationSec * 1000L).coerceIn(30_000L, 600_000L)
        sliceTimerJob?.cancel()
        sliceTimerJob = recorderScope.launch {
            while (isActive) {
                delay(durationMs)
                requestChunkSlice()
            }
        }

        durationTimerJob?.cancel()
        durationTimerJob = recorderScope.launch {
            var seconds = 0L
            while (isActive) {
                delay(1000L)
                seconds++
                _recordingDurationSec.value = seconds
            }
        }
    }

    override fun requestChunkSlice() {
        // 尚无 muxer 可交接时不应请求切片：否则首个 IDR 帧会被拿去“热切换”，
        // 凭空多出一个没有任何轨道的空切片文件，也会提前触发一次配额淘汰。
        synchronized(muxerLock) { if (currentMuxer == null) return }
        Log.d(TAG, "Slice interval reached. Requesting IDR sync frame for hot switch...")
        shouldSliceAtNextKeyframe.set(true)
        // 强制编码器立即发射一个 IDR 关键帧
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        mediaCodec?.setParameters(params)
    }

    override fun triggerEventLock(reason: String) {
        Log.w(TAG, "EVENT LOCK TRIGGERED ($reason)! Promoting current chunk to EVENT category.")
        this.currentCategory = RecordCategory.EVENT
    }

    private fun drainEncoderLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (_isRecording.value) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                cachedOutputFormat = newFormat
                setupMuxerTrack(newFormat)
            } else if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex)
                if (encodedData != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    // 检查热切片条件：收到新 IDR 帧且触发了切片请求
                    if (isKeyFrame && shouldSliceAtNextKeyframe.compareAndSet(true, false)) {
                        hotSwitchMuxerChunk()
                    }

                    if (isMuxerStarted && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        // 规整时间戳：每个 MP4 切片文件的 PTS 从严格的 0 开始递增
                        if (chunkBasePtsUs < 0L) {
                            chunkBasePtsUs = bufferInfo.presentationTimeUs
                        }
                        val normalizedPts = (bufferInfo.presentationTimeUs - chunkBasePtsUs).coerceAtLeast(0L)
                        val adjustedInfo = MediaCodec.BufferInfo().apply {
                            set(bufferInfo.offset, bufferInfo.size, normalizedPts, bufferInfo.flags)
                        }

                        var written = false
                        synchronized(muxerLock) {
                            try {
                                currentMuxer?.writeSampleData(currentVideoTrackIndex, encodedData, adjustedInfo)
                                written = true
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed writing sample to MediaMuxer! Cushioning in RamRingBuffer...", e)
                            }
                        }
                        if (!written) {
                            try {
                                encodedData.position(bufferInfo.offset)
                                ramRingBuffer.enqueue(encodedData, bufferInfo.size)
                            } catch (bufEx: Exception) {
                                Log.w(TAG, "RamRingBuffer cushion failure", bufEx)
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
            }
        }
    }

    private fun setupMuxerTrack(format: MediaFormat) {
        val muxer = currentMuxer ?: return
        if (!isMuxerStarted) {
            currentVideoTrackIndex = muxer.addTrack(format)
            // 音轨必须在 muxer.start() 之前加入；无音轨时保持 -1，音频帧直接丢弃
            val audioFormat = buildAudioTrackFormat()
            currentAudioTrackIndex = if (audioFormat != null) muxer.addTrack(audioFormat) else -1
            muxer.start()
            isMuxerStarted = true
            Log.i(TAG, "MediaMuxer started: video=$currentVideoTrackIndex audio=$currentAudioTrackIndex")
        }
    }

    private fun startNewMuxerChunk(category: RecordCategory) {
        val outStream = storageVault.openChunkOutputStream(category, System.currentTimeMillis(), "ALL")
        if (outStream is FileOutputStream) {
            val fd: FileDescriptor = outStream.fd
            val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            this.currentMuxer = muxer
            this.isMuxerStarted = false
            this.currentVideoTrackIndex = -1
            this.currentAudioTrackIndex = -1
            this.chunkBasePtsUs = -1L

            // 若已有格式缓存，立即初始化音视频轨
            cachedOutputFormat?.let { setupMuxerTrack(it) }
            Log.i(TAG, "New chunk created for category: $category")
        }
    }

    /**
     * 0 毫秒热切换：无缝交接目标文件，编码器全程不停
     */
    private fun hotSwitchMuxerChunk() {
        Log.i(TAG, "Performing zero-gap muxer hot switch at IDR keyframe...")
        var oldMuxer: MediaMuxer? = null
        synchronized(muxerLock) {
            oldMuxer = currentMuxer
            startNewMuxerChunk(currentCategory)
        }

        recorderScope.launch(Dispatchers.IO) {
            try {
                oldMuxer?.stop()
                oldMuxer?.release()
                Log.d(TAG, "Old chunk closed asynchronously.")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous muxer chunk", e)
            }
        }
        // 切片后恢复常规轮询基线分类 (NORMAL 或 SENTRY)
        currentCategory = baseCategory

        // 上一个切片已封装落盘，此处即「每次录制结束」——按用户所选触发时机执行配额淘汰
        storageVault.enforceStorageQuotas(
            com.vongzh.sophon.domain.contract.IStorageVault.CLEANUP_TRIGGER_CHUNK_END
        )
    }

    override fun stopRecording() {
        _isRecording.value = false
        sliceTimerJob?.cancel()
        durationTimerJob?.cancel()
        // 先停采集再封口 muxer：否则音频 drain 可能在 muxer.stop() 之后继续写入。
        // CabinAudioEncoder.stop() 会等 drain 循环真正退出，无需在此额外同步。
        cabinAudio?.stop()
        cabinAudio = null

        // 停流必须与 writeSampleData 互斥：视频 drain / 音频 drain 都持有 muxerLock 写盘，
        // 不同步就可能出现「muxer 已 stop 但仍有 sample 写入」的 IllegalStateException。
        synchronized(muxerLock) {
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
                currentMuxer?.stop()
                currentMuxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error shutting down continuous recording pipeline", e)
            } finally {
                mediaCodec = null
                currentMuxer = null
                isMuxerStarted = false
                currentVideoTrackIndex = -1
                currentAudioTrackIndex = -1
            }
        }
        Log.i(TAG, "Recording pipeline completely stopped.")
    }

    /**
     * 车祸碰撞/熄火休眠紧急落盘封包 (毫秒级执行，防止 moov atom 缺失导致视频损坏)
     */
    @Synchronized
    fun emergencyFinalizeCurrentChunk(reason: String) {
        Log.w(TAG, "EMERGENCY FINALIZE TRIGGERED: $reason! Flushing and closing current MP4 chunk...")
        _isRecording.value = false
        sliceTimerJob?.cancel()
        durationTimerJob?.cancel()
        cabinAudio?.stop()
        cabinAudio = null

        // 与 writeSampleData 互斥，理由同 stopRecording()：视频/音频 drain 可能正在写盘
        synchronized(muxerLock) {
            try {
                if (isMuxerStarted && currentMuxer != null) {
                    currentMuxer?.stop()
                    currentMuxer?.release()
                    Log.i(TAG, "Current MP4 chunk successfully finalized with valid moov header.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed during emergency muxer finalization", e)
            } finally {
                currentMuxer = null
                isMuxerStarted = false
                currentVideoTrackIndex = -1
                currentAudioTrackIndex = -1
            }
        }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping media codec during emergency finalization", e)
        } finally {
            mediaCodec = null
        }
        ramRingBuffer.clear()
        Log.i(TAG, "Emergency finalization completed cleanly.")
    }
}
