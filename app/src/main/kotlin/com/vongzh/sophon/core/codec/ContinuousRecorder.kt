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
    private var isMuxerStarted = false
    private var cachedOutputFormat: MediaFormat? = null

    private var currentCategory = RecordCategory.NORMAL
    private val shouldSliceAtNextKeyframe = AtomicBoolean(false)
    private var sliceTimerJob: Job? = null
    private var durationTimerJob: Job? = null

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
        startNewMuxerChunk(RecordCategory.NORMAL)

        // 启动编码数据轮询输出协程
        recorderScope.launch { drainEncoderLoop() }
        // 启动切片定时器与计时器
        startSliceSchedule()

        Log.i(TAG, "Continuous recording pipeline ACTIVE.")
        return true
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
                        currentMuxer?.writeSampleData(currentVideoTrackIndex, encodedData, bufferInfo)
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
            muxer.start()
            isMuxerStarted = true
            Log.i(TAG, "MediaMuxer started with track index: $currentVideoTrackIndex")
        }
    }

    private fun startNewMuxerChunk(category: RecordCategory) {
        val outStream = storageVault.openChunkOutputStream(category, System.currentTimeMillis(), "ALL")
        if (outStream is FileOutputStream) {
            val fd: FileDescriptor = outStream.fd
            val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            this.currentMuxer = muxer
            this.isMuxerStarted = false

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
        val oldMuxer = currentMuxer
        recorderScope.launch(Dispatchers.IO) {
            try {
                oldMuxer?.stop()
                oldMuxer?.release()
                Log.d(TAG, "Old chunk closed asynchronously.")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous muxer chunk", e)
            }
        }

        startNewMuxerChunk(currentCategory)
        // 切片后恢复常规轮询分类
        currentCategory = RecordCategory.NORMAL
    }

    override fun stopRecording() {
        _isRecording.value = false
        sliceTimerJob?.cancel()
        durationTimerJob?.cancel()

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
