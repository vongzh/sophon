package com.vongzh.sophon.core.codec

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 零转码无损极速视频剪辑引擎 (VideoClipper)
 *
 * 【座舱事故取证极速提取】
 * 1. 采用 MediaExtractor + MediaMuxer 样本直通重封装架构，零 CPU/GPU 重编码消耗；
 * 2. 毫秒级提取事故黄金 15s / 30s 事故片段（耗时 < 300ms）；
 * 3. 产物体积轻巧（通常 15~30MB），支持局域网免流秒级快传手机。
 */
class VideoClipper {

    companion object {
        private const val TAG = "Sophon.VideoClipper"
        private const val DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB 样本缓存
    }

    /**
     * 无损截取视频片段
     *
     * @param sourceFile 原视频切片文件
     * @param outputFile 剪辑导出目标文件
     * @param startMs 截取起始时间 (毫秒)
     * @param endMs 截取结束时间 (毫秒)
     * @param onProgress 进度回调 (0.0 ~ 1.0)
     * @return 导出成功返回 true，失败返回 false
     */
    suspend fun clipRangeLossless(
        sourceFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.e(TAG, "Source file does not exist or empty: ${sourceFile.absolutePath}")
            return@withContext false
        }

        outputFile.parentFile?.mkdirs()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(sourceFile.absolutePath)
            val trackCount = extractor.trackCount
            val trackMap = HashMap<Int, Int>()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val muxerTrackIndex = muxer.addTrack(format)
                    trackMap[i] = muxerTrackIndex
                    Log.d(TAG, "Added track $i ($mime) to muxer as $muxerTrackIndex")
                }
            }

            if (trackMap.isEmpty()) {
                Log.e(TAG, "No valid video or audio tracks found in source file!")
                return@withContext false
            }

            muxer.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            val totalUs = maxOf(1L, endUs - startUs)

            // 定位到起始关键帧 (SEEK_TO_PREVIOUS_SYNC 确保首帧可被解码器正确渲染)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break // 到达流末尾
                }

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) {
                    break // 超过截取终点
                }

                val trackIndex = extractor.sampleTrackIndex
                val muxerTrackIndex = trackMap[trackIndex]

                if (muxerTrackIndex != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTimeUs - startUs
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                    val progress = ((sampleTimeUs - startUs).toFloat() / totalUs).coerceIn(0f, 1f)
                    onProgress?.invoke(progress)
                }

                if (!extractor.advance()) {
                    break
                }
            }

            Log.i(TAG, "Lossless clip completed successfully -> ${outputFile.name} (${outputFile.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clipping video range: ${e.message}", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            false
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping muxer", e)
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing extractor", e)
            }
        }
    }
}
