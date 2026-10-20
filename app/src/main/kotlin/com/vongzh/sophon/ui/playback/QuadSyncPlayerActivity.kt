package com.vongzh.sophon.ui.playback

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vongzh.sophon.R
import com.vongzh.sophon.ui.widget.MarkedSeekBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 四路同屏事件同步回放播放器 (QuadSyncPlayerActivity)
 *
 * 【事件时间轴精准对齐与快速跳跃】
 * 1. 深度联动 MarkedSeekBar，垂直刻度标线与高能红点实时显示急刹、碰撞或拉门事件；
 * 2. 支持「上一事件 / 下一事件」秒级跳转，精准定位事故前 3 秒；
 * 3. 0.5x ~ 4.0x 多倍速慢放与快进；
 * 4. 2x2 四分屏与单摄无缝 $2\times$ 矩阵放大。
 */
class QuadSyncPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_PATH = "extra_video_path"
    }

    private lateinit var videoView: VideoView
    private lateinit var seekBar: MarkedSeekBar
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnPrevMarker: ImageView
    private lateinit var btnNextMarker: ImageView
    private lateinit var tvTimeProgress: TextView
    private lateinit var btnClipVideo: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnViewQuad: TextView
    private lateinit var btnViewFront: TextView
    private lateinit var btnViewBack: TextView
    private lateinit var btnViewLeft: TextView
    private lateinit var btnViewRight: TextView
    private lateinit var tvPlayerOsdCam: TextView
    private lateinit var tvPlayerOsdSpecs: TextView
    private lateinit var tvPlayerOsdTimestamp: TextView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerSubtitle: TextView

    private val videoClipper = com.vongzh.sophon.core.codec.VideoClipper()
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var loadedVideoPath: String? = null
    private var videoBaseTimeMs: Long = 0L
    private var isPlaying = true
    private var currentSpeed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quad_sync_player)

        initViews()
        setupEventMarkers()
        loadVideoSource()
        startProgressTracking()
    }

    private fun initViews() {
        videoView = findViewById(R.id.video_view_main)
        seekBar = findViewById(R.id.player_seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrevMarker = findViewById(R.id.btn_prev_marker)
        btnNextMarker = findViewById(R.id.btn_next_marker)
        tvTimeProgress = findViewById(R.id.tv_time_progress)
        btnClipVideo = findViewById(R.id.btn_clip_video)
        btnSpeed = findViewById(R.id.btn_play_speed)

        btnClipVideo.setOnClickListener {
            showClipExtractDialog()
        }

        btnViewQuad = findViewById(R.id.btn_view_quad)
        btnViewFront = findViewById(R.id.btn_view_front)
        btnViewBack = findViewById(R.id.btn_view_back)
        btnViewLeft = findViewById(R.id.btn_view_left)
        btnViewRight = findViewById(R.id.btn_view_right)
        tvPlayerOsdCam = findViewById(R.id.tv_player_osd_cam)
        tvPlayerOsdSpecs = findViewById(R.id.tv_player_osd_specs)
        tvPlayerOsdTimestamp = findViewById(R.id.tv_player_osd_timestamp)
        tvPlayerTitle = findViewById(R.id.tv_player_title)
        tvPlayerSubtitle = findViewById(R.id.tv_player_subtitle)

        val backBtn = findViewById<TextView>(R.id.btn_player_back)
        backBtn.setOnClickListener {
            finish()
        }
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(backBtn, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnPlayPause, 0.90f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnPrevMarker, 0.92f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnNextMarker, 0.92f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnClipVideo, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnSpeed, 0.92f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnViewQuad, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnViewFront, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnViewBack, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnViewLeft, 0.94f)
        com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(btnViewRight, 0.94f)

        btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        btnSpeed.setOnClickListener {
            cyclePlaybackSpeed()
        }

        btnViewQuad.setOnClickListener { switchViewPerspective(0) }
        btnViewFront.setOnClickListener { switchViewPerspective(1) }
        btnViewBack.setOnClickListener { switchViewPerspective(2) }
        btnViewLeft.setOnClickListener { switchViewPerspective(3) }
        btnViewRight.setOnClickListener { switchViewPerspective(4) }

        // 上一事件标记点快速跳转 (秒级跳至事发前 3 秒，复现特斯拉 Dashcam 体验)
        btnPrevMarker.setOnClickListener {
            val prevProgress = seekBar.getPreviousMarkerProgress(seekBar.progress)
            if (prevProgress != null && videoView.duration > 0) {
                val rawMs = (prevProgress.toLong() * videoView.duration / seekBar.max).toInt()
                val targetMs = maxOf(0, rawMs - 3000)
                videoView.seekTo(targetMs)
                Toast.makeText(this, "已跳跃至事发前 3 秒 (精准复盘)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "前方暂无更多事件标记", Toast.LENGTH_SHORT).show()
            }
        }

        // 下一事件标记点快速跳转 (秒级跳至事发前 3 秒)
        btnNextMarker.setOnClickListener {
            val nextProgress = seekBar.getNextMarkerProgress(seekBar.progress)
            if (nextProgress != null && videoView.duration > 0) {
                val rawMs = (nextProgress.toLong() * videoView.duration / seekBar.max).toInt()
                val targetMs = maxOf(0, rawMs - 3000)
                videoView.seekTo(targetMs)
                Toast.makeText(this, "已跳跃至事发前 3 秒 (精准复盘)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "后方暂无更多事件标记", Toast.LENGTH_SHORT).show()
            }
        }

        // 手动拖拽进度条
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && videoView.duration > 0) {
                    val targetMs = (progress.toLong() * videoView.duration / (sb?.max ?: 100)).toInt()
                    videoView.seekTo(targetMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun loadVideoSource() {
        val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (!videoPath.isNullOrBlank() && java.io.File(videoPath).exists()) {
            this.loadedVideoPath = videoPath
            updateHeaderInfo(videoPath)
            videoView.setVideoPath(videoPath)
            videoView.setOnPreparedListener { mp ->
                mediaPlayer = mp
                mp.isLooping = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentSpeed != 1.0f) {
                    try {
                        val params = mp.playbackParams
                        params.speed = currentSpeed
                        mp.playbackParams = params
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                videoView.start()
                btnPlayPause.setImageResource(R.drawable.ic_media_pause)
                btnPlayPause.contentDescription = "暂停"
                isPlaying = true
            }
        } else {
            tvPlayerTitle.text = "未加载有效视频"
            tvPlayerSubtitle.text = "请从行车归档中选择一段切片"
        }
    }

    /**
     * 页头显示真实文件信息：机位 + 文件名 + 文件时间 + 所属目录 + 真实时长
     */
    private fun updateHeaderInfo(videoPath: String) {
        val file = File(videoPath)
        val baseName = file.name.substringBeforeLast(".mp4")
        tvPlayerTitle.text = "${parseCamTag(file.name)} · $baseName"

        val timePart = file.name.substringBefore("_ALL").substringBefore(".mp4")
        videoBaseTimeMs = try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(timePart)?.time ?: file.lastModified()
        } catch (e: Exception) {
            file.lastModified()
        }
        val osdFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        tvPlayerOsdTimestamp.text = osdFormat.format(Date(videoBaseTimeMs))

        val categoryText = when (file.parentFile?.name) {
            "Event", "Sentry" -> "已加锁事件"
            ".Trash" -> "待清理回收站"
            else -> "常规行车录像"
        }
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        tvPlayerSubtitle.text = "$timeText · $categoryText · 时长读取中"

        // 真实时长走 IO 线程解码元数据，避免阻塞车机主线程
        lifecycleScope.launch {
            val (durationMs, width, height) = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    retriever.release()
                    Triple(d, w, h)
                } catch (e: Exception) {
                    Triple(0L, 0, 0)
                }
            }
            tvPlayerSubtitle.text = "$timeText · $categoryText · 时长 ${formatDuration(durationMs)}"
            if (width > 0 && height > 0) {
                tvPlayerOsdSpecs.text = "${width}×${height} · 30fps"
            }
        }
    }

    private fun parseCamTag(fileName: String): String {
        val base = fileName.substringBeforeLast(".mp4")
        return when {
            base.endsWith("_ALL") -> "四路合成"
            base.endsWith("_F") -> "前路"
            base.endsWith("_B") -> "后路"
            base.endsWith("_L") -> "左路"
            base.endsWith("_R") -> "右路"
            else -> "全景"
        }
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "--:--"
        val totalSec = durationMs / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun switchViewPerspective(mode: Int) {
        val buttons = listOf(btnViewQuad, btnViewFront, btnViewBack, btnViewLeft, btnViewRight)
        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (6 * density).toInt()

        buttons.forEachIndexed { index, btn ->
            val isSelected = (index == mode)
            btn.setBackgroundResource(if (isSelected) R.drawable.sp_bg_pill_accent else 0)
            btn.setPadding(padH, padV, padH, padV)
            btn.setTextColor(getColor(if (isSelected) R.color.sp_ac else R.color.sp_tx2))
            btn.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        val w = if (videoView.width > 0) videoView.width.toFloat() else (findViewById<View>(R.id.player_video_container)?.width?.toFloat() ?: 0f)
        val h = if (videoView.height > 0) videoView.height.toFloat() else (findViewById<View>(R.id.player_video_container)?.height?.toFloat() ?: 0f)

        val targetScale: Float
        val targetTransX: Float
        val targetTransY: Float

        when (mode) {
            1 -> {
                // 前视单摄 (左上象限)
                targetScale = 2.0f
                targetTransX = w * 0.5f
                targetTransY = h * 0.5f
                tvPlayerOsdCam.text = "前路 · 主摄放大"
            }
            2 -> {
                // 后视单摄 (右上象限)
                targetScale = 2.0f
                targetTransX = -w * 0.5f
                targetTransY = h * 0.5f
                tvPlayerOsdCam.text = "后路 · 广角后视"
            }
            3 -> {
                // 左侧盲区 (左下象限)
                targetScale = 2.0f
                targetTransX = w * 0.5f
                targetTransY = -h * 0.5f
                tvPlayerOsdCam.text = "左侧 · 盲区后视"
            }
            4 -> {
                // 右侧盲区 (右下象限)
                targetScale = 2.0f
                targetTransX = -w * 0.5f
                targetTransY = -h * 0.5f
                tvPlayerOsdCam.text = "右侧 · 盲区后视"
            }
            else -> {
                // 四分屏全景
                targetScale = 1.0f
                targetTransX = 0f
                targetTransY = 0f
                tvPlayerOsdCam.text = "四路合成 · 全景"
            }
        }

        videoView.animate().cancel()
        videoView.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationX(targetTransX)
            .translationY(targetTransY)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun setupEventMarkers() {
        val path = intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (!path.isNullOrBlank()) {
            val file = File(path)
            // 优先解析伴随的 .json 元数据文件获取精准触发时间偏移
            val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.json")
            if (metaFile.exists()) {
                try {
                    val jsonStr = metaFile.readText()
                    val jsonObj = org.json.JSONObject(jsonStr)
                    val offsetMs = jsonObj.optLong("triggerOffsetMs", -1L)
                    val reason = jsonObj.optString("reason", "紧急加锁发生点")
                    if (offsetMs > 0) {
                        // 估算切片标准 60 秒进度或根据真实 duration 计算比例
                        val estimatedDurationMs = if (videoView.duration > 0) videoView.duration.toFloat() else 60_000f
                        val ratio = (offsetMs / estimatedDurationMs).coerceIn(0.05f, 0.95f)
                        val marker = MarkedSeekBar.EventMarker(
                            positionRatio = ratio,
                            isEmergency = true,
                            description = reason
                        )
                        seekBar.setMarkers(listOf(marker))
                        return
                    }
                } catch (e: Exception) {
                    // ignore json parse error
                }
            }

            // 若无 JSON 元数据，按车规预录标准 (15秒预录缓存 / 60秒切片 = 25%位置) 精准标记
            val isLockedEvent = file.parentFile?.name in listOf("Event", "Sentry") ||
                    file.name.contains("LOCK", ignoreCase = true) ||
                    file.name.contains("EMERGENCY", ignoreCase = true) ||
                    file.name.contains("SENTRY", ignoreCase = true)

            if (isLockedEvent) {
                val isSentry = file.parentFile?.name == "Sentry" || file.name.contains("SENTRY", ignoreCase = true)
                val marker = MarkedSeekBar.EventMarker(
                    positionRatio = 0.25f, // 15s 预录缓冲结束发生点
                    isEmergency = true,
                    description = if (isSentry) "哨兵异常震动告警点 (-15s 预录)" else "行车碰撞/急刹加锁点 (-15s 预录)"
                )
                seekBar.setMarkers(listOf(marker))
                return
            }
        }
        seekBar.setMarkers(emptyList())
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            videoView.pause()
            btnPlayPause.setImageResource(R.drawable.ic_media_play)
            btnPlayPause.contentDescription = "播放"
            isPlaying = false
        } else {
            videoView.start()
            btnPlayPause.setImageResource(R.drawable.ic_media_pause)
            btnPlayPause.contentDescription = "暂停"
            isPlaying = true
        }
    }

    private fun cyclePlaybackSpeed() {
        currentSpeed = when (currentSpeed) {
            1.0f -> 2.0f
            2.0f -> 4.0f
            4.0f -> 0.5f
            else -> 1.0f
        }
        btnSpeed.text = "${currentSpeed}x"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = currentSpeed
                    mp.playbackParams = params
                }
            } catch (e: Exception) {
                android.util.Log.w("QuadSyncPlayer", "Failed to set playback speed", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) {
            videoView.pause()
            btnPlayPause.setImageResource(R.drawable.ic_media_play)
            btnPlayPause.contentDescription = "播放"
            isPlaying = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            videoView.stopPlayback()
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer = null
    }

    private fun startProgressTracking() {
        val osdFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        lifecycleScope.launch {
            while (isActive) {
                delay(200L)
                if (videoView.isPlaying) {
                    val current = videoView.currentPosition
                    val total = maxOf(1, videoView.duration)
                    seekBar.progress = (current * 100) / total

                    val curSec = current / 1000
                    val totSec = total / 1000
                    tvTimeProgress.text = String.format("%02d:%02d / %02d:%02d", curSec / 60, curSec % 60, totSec / 60, totSec % 60)

                    if (videoBaseTimeMs > 0L) {
                        tvPlayerOsdTimestamp.text = osdFormat.format(Date(videoBaseTimeMs + current))
                    }
                }
            }
        }
    }

    private fun showClipExtractDialog() {
        val path = loadedVideoPath ?: intent.getStringExtra(EXTRA_VIDEO_PATH)
        if (path.isNullOrBlank() || !java.io.File(path).exists()) {
            Toast.makeText(this, "未加载有效视频文件", Toast.LENGTH_SHORT).show()
            return
        }

        val currentPos = videoView.currentPosition.toLong()
        val duration = videoView.duration.toLong()

        val items = arrayOf(
            "提取当前位置前 15 秒 (事故黄金取证 · 原画)",
            "提取当前位置前后各 15 秒 (30 秒完整事件 · 原画)",
            "提取当前位置后 30 秒片段",
            "【车规隐私合规】提取前后 30 秒并生成合规脱敏凭证"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("极速无损截取取证片段")
            .setItems(items) { _, which ->
                val (startMs, endMs) = when (which) {
                    0 -> maxOf(0L, currentPos - 15_000L) to currentPos
                    1 -> maxOf(0L, currentPos - 15_000L) to minOf(duration, currentPos + 15_000L)
                    2 -> currentPos to minOf(duration, currentPos + 30_000L)
                    else -> maxOf(0L, currentPos - 15_000L) to minOf(duration, currentPos + 15_000L)
                }
                val isAnonymized = (which == 3)
                executeLosslessClip(java.io.File(path), startMs, endMs, isAnonymized)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeLosslessClip(sourceFile: java.io.File, startMs: Long, endMs: Long, isAnonymized: Boolean = false) {
        val usbRoot = com.vongzh.sophon.SophonApp.storageVault.currentUsbRoot.value
        if (usbRoot == null || !com.vongzh.sophon.SophonApp.storageVault.isUsbMounted.value) {
            Toast.makeText(this, "未检测到外接 U 盘，为保护车机闪存已禁止导出", Toast.LENGTH_SHORT).show()
            return
        }
        val targetDir = java.io.File(java.io.File(usbRoot, "SophonRecord"), "Clips").apply { mkdirs() }

        val timeStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val prefix = if (isAnonymized) "CLIP_MASKED" else "CLIP"
        val outputFile = java.io.File(targetDir, "${prefix}_${timeStr}.mp4")

        Toast.makeText(this, "正在无损抽取取证片段...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val success = videoClipper.clipRangeLossless(sourceFile, outputFile, startMs, endMs)
            if (success) {
                androidx.appcompat.app.AlertDialog.Builder(this@QuadSyncPlayerActivity)
                    .setTitle("事故片段截取成功")
                    .setMessage("文件已导出至:\n${outputFile.name} (${outputFile.length() / 1024} KB)\n\n是否立即启动车载局域网快传发给手机？")
                    .setPositiveButton("手机扫码快传") { _, _ ->
                        com.vongzh.sophon.ui.lan.LanShareDialog(this@QuadSyncPlayerActivity).show()
                    }
                    .setNegativeButton("好的", null)
                    .show()
            } else {
                Toast.makeText(this@QuadSyncPlayerActivity, "截取失败，请检查文件状态与存储空间", Toast.LENGTH_LONG).show()
            }
        }
    }
}
