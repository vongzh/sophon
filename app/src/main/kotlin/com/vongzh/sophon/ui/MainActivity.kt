package com.vongzh.sophon.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.CameraPosition
import com.vongzh.sophon.domain.model.GearState
import com.vongzh.sophon.domain.model.RecordCategory
import com.vongzh.sophon.service.BlindSpotOverlayService
import com.vongzh.sophon.service.SophonCoreService
import com.vongzh.sophon.ui.history.RecordingHistoryFragment
import com.vongzh.sophon.ui.photo.PhotoViewerFragment
import com.vongzh.sophon.ui.playback.QuadSyncPlayerActivity
import com.vongzh.sophon.ui.sentry.SentryEventReviewFragment
import com.vongzh.sophon.ui.settings.SophonSettingsFragment
import com.vongzh.sophon.ui.widget.StorageCapacityRingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极视 (Sophon) 座舱主控界面 (MainActivity)
 *
 * 【架构规范 - 避免在 MainActivity 中堆积业务逻辑】
 * 仅承担 UI 呈现、生命周期调度与状态观察，录制与信号处理彻底委托至后台守护进程与 Manager 类。
 */
class MainActivity : AppCompatActivity() {

    enum class MainTab {
        LIVE,
        DRIVING,
        SENTRY,
        PHOTOS,
        SETTINGS
    }

    private var currentTab: MainTab = MainTab.LIVE

    // 侧边栏导航按钮、图标与标题
    private lateinit var btnNavLive: View
    private lateinit var btnNavDriving: View
    private lateinit var btnNavSentry: View
    private lateinit var btnNavPhotos: View
    private lateinit var btnNavSettings: View

    private lateinit var ivNavLive: ImageView
    private lateinit var ivNavDriving: ImageView
    private lateinit var ivNavSentry: ImageView
    private lateinit var ivNavPhotos: ImageView
    private lateinit var ivNavSettings: ImageView

    private lateinit var tvNavLiveTitle: TextView
    private lateinit var tvNavDrivingTitle: TextView
    private lateinit var tvNavSentryTitle: TextView
    private lateinit var tvNavPhotosTitle: TextView
    private lateinit var tvNavSettingsTitle: TextView

    // 主舞台容器
    private lateinit var layoutStageLive: View
    private lateinit var containerFragmentContent: View

    // 4 个嵌入式 Fragment 实例
    private var fragmentDriving: RecordingHistoryFragment? = null
    private var fragmentSentry: SentryEventReviewFragment? = null
    private var fragmentPhotos: PhotoViewerFragment? = null
    private var fragmentSettings: SophonSettingsFragment? = null

    // 状态与遥测标题
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvMainResFpsChip: TextView
    private lateinit var tvTelemetryGear: TextView
    private lateinit var tvTelemetrySpeed: TextView
    private lateinit var tvTelemetryTemp: TextView
    private lateinit var tvTelemetryGps: TextView
    private lateinit var tvUsbStatus: TextView
    private lateinit var ivStorageUsbIcon: ImageView
    private lateinit var layoutStorageCard: View
    private lateinit var storageRingMini: StorageCapacityRingView
    private lateinit var barStorageMini: com.vongzh.sophon.ui.widget.MultiSegmentStorageBarView
    private lateinit var layoutStorageLegend: View
    private lateinit var tvStorageUnmountedTip: TextView

    // 侧边栏报警角标
    private lateinit var badgeSentry: TextView

    // 顶部全局状态栏
    private lateinit var tvTopClock: TextView
    private lateinit var tvTopUsbIndicator: TextView
    private lateinit var tvTopRecTime: TextView
    private lateinit var dotRec: View

    // 全局 REC 录制角标（画布顶部中央，录制中才可见）
    private lateinit var layoutRecBanner: View
    private lateinit var dotRecBanner: View
    private lateinit var tvRecBanner: TextView

    // 四路相机 SurfaceView 直出预览
    private lateinit var surfaceCamFront: SurfaceView
    private lateinit var surfaceCamBack: SurfaceView
    private lateinit var surfaceCamLeft: SurfaceView
    private lateinit var surfaceCamRight: SurfaceView

    // 四路机位 OSD 状态指示小圆点 (替代通道待命文案)
    private lateinit var dotOsdStatusFront: View
    private lateinit var dotOsdStatusBack: View
    private lateinit var dotOsdStatusLeft: View
    private lateinit var dotOsdStatusRight: View

    // 四路机位 OSD 动态时间水印与规格 (已按需精简)
    private var tvOsdTimeFront: TextView? = null
    private var tvOsdTimeBack: TextView? = null
    private var tvOsdTimeLeft: TextView? = null
    private var tvOsdTimeRight: TextView? = null
    private var tvOsdSpecFront: TextView? = null
    private var tvOsdSpecBack: TextView? = null
    private var tvOsdSpecLeft: TextView? = null
    private var tvOsdSpecRight: TextView? = null

    // 单路全屏专属 OSD
    private lateinit var tvSingleRoadInfo: TextView
    private lateinit var tvSingleExposure: TextView
    private lateinit var tvSingleGps: TextView
    private lateinit var tvSingleSpecs: TextView

    // 拍照动效与三段浮动控制
    private var viewShutterFlash: View? = null
    private var layoutCaptureToast: View? = null
    private var tvCaptureToastMsg: TextView? = null
    private var fabMic: View? = null
    private var ivFabMic: ImageView? = null
    private var fabViewMode: View? = null
    private var ivFabViewMode: ImageView? = null
    private var fabSnapshot: View? = null
    private var hideToastRunnable: Runnable? = null
    private var recDotAnimator: ValueAnimator? = null

    private var isSingleCamMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 首启合规门：未签署隐私协议前不启动任何采集（相机 / 录音 / 车身信号）
        if (!com.vongzh.sophon.ui.OnboardingActivity.isAgreed()) {
            startActivity(Intent(this, com.vongzh.sophon.ui.OnboardingActivity::class.java))
            finish()
            return
        }

        initViews()
        setupCameraSurfaces()
        checkAndRequestCameraPermission()
        observeSystemStates()
        checkShowLanShare(intent)
        handleTargetTab(intent)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != MainTab.LIVE) {
                    switchTab(MainTab.LIVE)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (currentTab == MainTab.LIVE) {
            syncAllPreviewSurfaces()
        }
        updateFabLabelsVisibility()
        refreshStorageAndNavStats()
    }

    private fun checkAndRequestCameraPermission() {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            window.decorView.post {
                startDaemonServices()
            }
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                1001
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val cameraIndex = permissions.indexOf(android.Manifest.permission.CAMERA)
            if (cameraIndex != -1 && grantResults.getOrNull(cameraIndex) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                window.decorView.post {
                    startDaemonServices()
                }
            } else if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                window.decorView.post {
                    startDaemonServices()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkShowLanShare(intent)
        handleTargetTab(intent)
    }

    private fun handleTargetTab(intent: Intent?) {
        val target = intent?.getStringExtra("target_tab") ?: return
        try {
            switchTab(MainTab.valueOf(target.uppercase()))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun checkShowLanShare(intent: Intent?) {
        if (intent?.getBooleanExtra("action_show_lan_share", false) == true) {
            com.vongzh.sophon.ui.lan.LanShareDialog(this).show()
        }
    }

    private fun initViews() {
        tvStatusTitle = findViewById(R.id.tv_status_title)
        tvStatusSubtitle = findViewById(R.id.tv_status_subtitle)
        tvMainResFpsChip = findViewById(R.id.tv_main_res_fps_chip)
        tvTelemetryGear = findViewById(R.id.tv_telemetry_gear)
        tvTelemetrySpeed = findViewById(R.id.tv_telemetry_speed)
        tvTelemetryTemp = findViewById(R.id.tv_telemetry_temp)
        tvTelemetryGps = findViewById(R.id.tv_telemetry_gps)
        tvUsbStatus = findViewById(R.id.tv_usb_status)
        ivStorageUsbIcon = findViewById(R.id.iv_storage_usb_icon)
        layoutStorageCard = findViewById(R.id.layout_storage_card)
        storageRingMini = findViewById(R.id.view_storage_ring_mini)
        barStorageMini = findViewById(R.id.bar_storage_mini)
        layoutStorageLegend = findViewById(R.id.layout_storage_legend)
        tvStorageUnmountedTip = findViewById(R.id.tv_storage_unmounted_tip)
        layoutStorageCard.setOnClickListener {
            switchTab(MainTab.SETTINGS)
        }
        applySpringTouchFeedback(layoutStorageCard)

        // 侧边栏按钮、图标、标题
        btnNavLive = findViewById(R.id.btn_nav_live)
        btnNavDriving = findViewById(R.id.btn_nav_driving)
        btnNavSentry = findViewById(R.id.btn_nav_sentry)
        btnNavPhotos = findViewById(R.id.btn_nav_photos)
        btnNavSettings = findViewById(R.id.btn_nav_settings)

        ivNavLive = findViewById(R.id.iv_nav_live)
        ivNavDriving = findViewById(R.id.iv_nav_driving)
        ivNavSentry = findViewById(R.id.iv_nav_sentry)
        ivNavPhotos = findViewById(R.id.iv_nav_photos)
        ivNavSettings = findViewById(R.id.iv_nav_settings)

        tvNavLiveTitle = findViewById(R.id.tv_nav_live_title)
        tvNavDrivingTitle = findViewById(R.id.tv_nav_driving_title)
        tvNavSentryTitle = findViewById(R.id.tv_nav_sentry_title)
        tvNavPhotosTitle = findViewById(R.id.tv_nav_photos_title)
        tvNavSettingsTitle = findViewById(R.id.tv_nav_settings_title)

        badgeSentry = findViewById(R.id.badge_sentry)

        // 主舞台容器
        layoutStageLive = findViewById(R.id.layout_stage_live)
        containerFragmentContent = findViewById(R.id.container_fragment_content)

        // 导航栏 Tab 点击即在右侧舞台切换组件（无返回按钮）
        btnNavLive.setOnClickListener { switchTab(MainTab.LIVE) }
        btnNavDriving.setOnClickListener { switchTab(MainTab.DRIVING) }
        btnNavSentry.setOnClickListener { switchTab(MainTab.SENTRY) }
        btnNavPhotos.setOnClickListener { switchTab(MainTab.PHOTOS) }
        btnNavSettings.setOnClickListener { switchTab(MainTab.SETTINGS) }

        // 顶部全局状态栏
        tvTopClock = findViewById(R.id.tv_top_clock)
        tvTopUsbIndicator = findViewById(R.id.tv_top_usb_indicator)
        tvTopRecTime = findViewById(R.id.tv_top_rec_time)
        dotRec = findViewById(R.id.dot_rec)

        // 四路相机 SurfaceView
        surfaceCamFront = findViewById(R.id.surface_cam_front)
        surfaceCamBack = findViewById(R.id.surface_cam_back)
        surfaceCamLeft = findViewById(R.id.surface_cam_left)
        surfaceCamRight = findViewById(R.id.surface_cam_right)

        // 四路机位 OSD 状态指示小圆点
        dotOsdStatusFront = findViewById(R.id.dot_osd_status_front)
        dotOsdStatusBack = findViewById(R.id.dot_osd_status_back)
        dotOsdStatusLeft = findViewById(R.id.dot_osd_status_left)
        dotOsdStatusRight = findViewById(R.id.dot_osd_status_right)

        // 四路机位 OSD (可选)
        tvOsdTimeFront = findViewById(R.id.tv_osd_time_front)
        tvOsdTimeBack = findViewById(R.id.tv_osd_time_back)
        tvOsdTimeLeft = findViewById(R.id.tv_osd_time_left)
        tvOsdTimeRight = findViewById(R.id.tv_osd_time_right)
        tvOsdSpecFront = findViewById(R.id.tv_osd_spec_front)
        tvOsdSpecBack = findViewById(R.id.tv_osd_spec_back)
        tvOsdSpecLeft = findViewById(R.id.tv_osd_spec_left)
        tvOsdSpecRight = findViewById(R.id.tv_osd_spec_right)

        layoutRecBanner = findViewById(R.id.layout_rec_banner)
        dotRecBanner = findViewById(R.id.dot_rec_banner)
        tvRecBanner = findViewById(R.id.tv_rec_banner)

        // 单路机位 OSD
        tvSingleRoadInfo = findViewById(R.id.tv_single_road_info)
        tvSingleExposure = findViewById(R.id.tv_single_exposure)
        tvSingleGps = findViewById(R.id.tv_single_gps)
        tvSingleSpecs = findViewById(R.id.tv_single_specs)

        // 拍照动效与浮动控制绑定
        viewShutterFlash = findViewById(R.id.view_shutter_flash)
        layoutCaptureToast = findViewById(R.id.layout_capture_toast)
        tvCaptureToastMsg = findViewById(R.id.tv_capture_toast_msg)

        fabSnapshot = findViewById(R.id.fab_snapshot)
        fabMic = findViewById(R.id.fab_mic)
        ivFabMic = findViewById(R.id.iv_fab_mic)
        fabViewMode = findViewById(R.id.fab_view_mode)
        ivFabViewMode = findViewById(R.id.iv_fab_view_mode)

        fabSnapshot?.setOnClickListener { triggerSnapshot() }
        findViewById<View>(R.id.btn_view_toggle)?.setOnClickListener { toggleQuadSingleView() }
        fabViewMode?.setOnClickListener { toggleQuadSingleView() }
        fabMic?.setOnClickListener { toggleMic() }

        // 车规触控反馈动效 (微缩放+弹性回弹)
        fabMic?.let { applySpringTouchFeedback(it) }
        fabSnapshot?.let { applySpringTouchFeedback(it) }
        fabViewMode?.let { applySpringTouchFeedback(it) }

        listOf(btnNavLive, btnNavDriving, btnNavSentry, btnNavPhotos, btnNavSettings).forEach { navItem ->
            applySpringTouchFeedback(navItem)
        }

        updateMicButtonState(animate = false)
        updateViewModeButtonState(animate = false)

        // 点击四路任一机位进入单路全屏
        val camContainers = listOf(
            R.id.container_cam_front,
            R.id.container_cam_back,
            R.id.container_cam_left,
            R.id.container_cam_right
        )
        camContainers.forEach { resId ->
            findViewById<View>(resId)?.setOnClickListener {
                toggleQuadSingleView()
            }
        }
    }

    fun switchTab(tab: MainTab) {
        currentTab = tab

        // 1. 更新左侧导航栏各项的高亮/普通背景与图标色彩
        val navItems = listOf(
            MainTab.LIVE to Triple(btnNavLive, ivNavLive, tvNavLiveTitle),
            MainTab.DRIVING to Triple(btnNavDriving, ivNavDriving, tvNavDrivingTitle),
            MainTab.SENTRY to Triple(btnNavSentry, ivNavSentry, tvNavSentryTitle),
            MainTab.PHOTOS to Triple(btnNavPhotos, ivNavPhotos, tvNavPhotosTitle),
            MainTab.SETTINGS to Triple(btnNavSettings, ivNavSettings, tvNavSettingsTitle)
        )

        for ((itemTab, views) in navItems) {
            val (btn, iv, tv) = views
            val isActive = (itemTab == tab)
            btn.setBackgroundResource(if (isActive) R.drawable.sp_bg_nav_item_active else R.drawable.sp_bg_nav_item)
            val tintColor = getColor(if (isActive) R.color.sp_ac else R.color.sp_tx2)
            iv.setColorFilter(tintColor)
            tv.setTextColor(getColor(if (isActive) R.color.sp_ac else R.color.sp_tx2))
            tv.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (isActive) {
                btn.scaleX = 0.95f
                btn.scaleY = 0.95f
                btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(220)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
        }

        // 2. 右侧主舞台与组件切换：非实时监看页面彻底折叠顶部状态栏容器 (56dp -> 0dp)，消灭空白断层
        val isLive = (tab == MainTab.LIVE)
        findViewById<View>(R.id.layout_top_bar_container)?.visibility = if (isLive) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layout_top_status_bar)?.visibility = if (isLive) View.VISIBLE else View.GONE

        if (tab == MainTab.LIVE) {
            layoutStageLive.alpha = 0f
            layoutStageLive.visibility = View.VISIBLE
            layoutStageLive.animate().alpha(1f).setDuration(200).start()
            containerFragmentContent.visibility = View.GONE
            val tx = supportFragmentManager.beginTransaction()
            fragmentDriving?.let { tx.hide(it) }
            fragmentSentry?.let { tx.hide(it) }
            fragmentPhotos?.let { tx.hide(it) }
            fragmentSettings?.let { tx.hide(it) }
            tx.commitAllowingStateLoss()
            syncAllPreviewSurfaces()
        } else {
            layoutStageLive.visibility = View.GONE
            containerFragmentContent.alpha = 0f
            containerFragmentContent.visibility = View.VISIBLE
            containerFragmentContent.animate().alpha(1f).setDuration(200).start()
            val tx = supportFragmentManager.beginTransaction()
            if (tab != MainTab.DRIVING) fragmentDriving?.let { tx.hide(it) }
            if (tab != MainTab.SENTRY) fragmentSentry?.let { tx.hide(it) }
            if (tab != MainTab.PHOTOS) fragmentPhotos?.let { tx.hide(it) }
            if (tab != MainTab.SETTINGS) fragmentSettings?.let { tx.hide(it) }

            when (tab) {
                MainTab.DRIVING -> {
                    if (fragmentDriving == null) {
                        fragmentDriving = RecordingHistoryFragment()
                        tx.add(R.id.container_fragment_content, fragmentDriving!!)
                    } else {
                        tx.show(fragmentDriving!!)
                    }
                }
                MainTab.SENTRY -> {
                    if (fragmentSentry == null) {
                        fragmentSentry = SentryEventReviewFragment()
                        tx.add(R.id.container_fragment_content, fragmentSentry!!)
                    } else {
                        tx.show(fragmentSentry!!)
                    }
                }
                MainTab.PHOTOS -> {
                    if (fragmentPhotos == null) {
                        fragmentPhotos = PhotoViewerFragment()
                        tx.add(R.id.container_fragment_content, fragmentPhotos!!)
                    } else {
                        tx.show(fragmentPhotos!!)
                    }
                }
                MainTab.SETTINGS -> {
                    if (fragmentSettings == null) {
                        fragmentSettings = SophonSettingsFragment()
                        tx.add(R.id.container_fragment_content, fragmentSettings!!)
                    } else {
                        tx.show(fragmentSettings!!)
                    }
                }
                else -> Unit
            }
            tx.commitAllowingStateLoss()
        }
    }

    private fun setupCameraSurfaces() {
        bindCameraPreview(surfaceCamFront, CameraPosition.FRONT)
        bindCameraPreview(surfaceCamBack, CameraPosition.BACK)
        bindCameraPreview(surfaceCamLeft, CameraPosition.LEFT)
        bindCameraPreview(surfaceCamRight, CameraPosition.RIGHT)
    }

    private fun bindCameraPreview(surfaceView: SurfaceView, position: CameraPosition) {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(position, holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SophonCoreService.instance?.attachPreviewSurface(position, holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                SophonCoreService.instance?.attachPreviewSurface(position, null)
            }
        })
    }

    private fun syncAllPreviewSurfaces() {
        val service = SophonCoreService.instance ?: return
        val list = listOf(
            CameraPosition.FRONT to surfaceCamFront,
            CameraPosition.BACK to surfaceCamBack,
            CameraPosition.LEFT to surfaceCamLeft,
            CameraPosition.RIGHT to surfaceCamRight
        )
        for ((pos, sv) in list) {
            val surf = sv.holder.surface
            if (surf != null && surf.isValid) {
                service.attachPreviewSurface(pos, surf)
            }
        }
    }

    private fun refreshStorageAndNavStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val storage = SophonApp.storageVault
            val isMounted = storage.isUsbMounted.value
            val root = storage.currentUsbRoot.value

            if (!isMounted || root == null || !root.exists()) {
                withContext(Dispatchers.Main) {
                    badgeSentry.visibility = View.GONE
                    tvTopUsbIndicator.text = "未连接存储"
                    tvTopUsbIndicator.setTextColor(getColor(R.color.sp_wn))

                    ivStorageUsbIcon.setColorFilter(getColor(R.color.sp_wn))
                    tvUsbStatus.text = "存储设备未就绪"
                    tvUsbStatus.setTextColor(getColor(R.color.sp_wn))
                    tvStorageUnmountedTip.visibility = View.VISIBLE
                    tvStorageUnmountedTip.text = "接入外置 U 盘启用录制"
                    storageRingMini.updateBreakdown(com.vongzh.sophon.domain.model.StorageUsageBreakdown())
                    barStorageMini.updateBreakdown(com.vongzh.sophon.domain.model.StorageUsageBreakdown())
                    layoutStorageLegend.visibility = View.GONE
                }
                return@launch
            }

            val baseDir = File(root, "SophonRecord")
            val normalDir = File(baseDir, RecordCategory.NORMAL.dirName)
            val sentryDir = File(baseDir, RecordCategory.SENTRY.dirName)
            val eventDir = File(baseDir, RecordCategory.EVENT.dirName)
            val snapshotDir = File(baseDir, RecordCategory.SNAPSHOT.dirName)

            val normalFiles = normalDir.listFiles()?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) } ?: emptyList()
            val normalCount = normalFiles.size
            val normalBytes = normalFiles.sumOf { it.length() }
            val normalGb = normalBytes.toDouble() / (1024.0 * 1024 * 1024)

            val todayPrefix = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val sentryFiles = (sentryDir.listFiles()?.filter { it.isFile } ?: emptyList()) +
                    (eventDir.listFiles()?.filter { it.isFile } ?: emptyList())
            val todaySentryCount = sentryFiles.count { it.name.startsWith(todayPrefix) }

            val photoFiles = snapshotDir.listFiles()?.filter {
                it.isFile && (it.extension.equals("jpg", ignoreCase = true) || it.extension.equals("png", ignoreCase = true))
            } ?: emptyList()
            val photoCount = photoFiles.size

            val latestVideo = (normalFiles + sentryFiles.filter { it.extension.equals("mp4", ignoreCase = true) })
                .maxByOrNull { it.lastModified() }
            val latestTimeBadge = if (latestVideo != null && latestVideo.length() > 0) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(latestVideo.absolutePath)
                    val durMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    retriever.release()
                    if (durMs > 0) {
                        val totalSec = durMs / 1000
                        String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
                    } else {
                        "--:--"
                    }
                } catch (e: Exception) {
                    "--:--"
                }
            } else {
                "--:--"
            }

            val breakdown = storage.queryStorageBreakdown()

            withContext(Dispatchers.Main) {
                if (todaySentryCount > 0) {
                    badgeSentry.text = todaySentryCount.toString()
                    badgeSentry.visibility = View.VISIBLE
                } else {
                    badgeSentry.visibility = View.GONE
                }
                tvTopUsbIndicator.text = "存储正常"
                tvTopUsbIndicator.setTextColor(getColor(R.color.sp_ac))

                ivStorageUsbIcon.setColorFilter(getColor(R.color.sp_ac))
                if (breakdown.totalBytes > 0) {
                    val usedGb = breakdown.usedBytes.toDouble() / (1024.0 * 1024 * 1024)
                    val totalGb = breakdown.totalBytes.toDouble() / (1024.0 * 1024 * 1024)
                    val freeGb = (breakdown.totalBytes - breakdown.usedBytes).toDouble() / (1024.0 * 1024 * 1024)
                    tvUsbStatus.text = "存储运行良好"
                    tvUsbStatus.setTextColor(getColor(R.color.sp_tx))
                    tvStorageUnmountedTip.visibility = View.VISIBLE
                    tvStorageUnmountedTip.text = String.format(Locale.getDefault(), "余 %.1fG / 共 %.0fG", freeGb, totalGb)
                    tvStorageUnmountedTip.setTextColor(getColor(R.color.sp_tx2))
                } else {
                    tvUsbStatus.text = "存储就绪"
                    tvUsbStatus.setTextColor(getColor(R.color.sp_tx))
                    tvStorageUnmountedTip.visibility = View.GONE
                }
                storageRingMini.updateBreakdown(breakdown)
                barStorageMini.updateBreakdown(breakdown)
                layoutStorageLegend.visibility = View.GONE
            }
        }
    }

    private fun triggerSnapshot() {
        // 0. 座舱操作物理声感与触感反馈 (盲操确定感保障)
        fabSnapshot?.playSoundEffect(android.view.SoundEffectConstants.CLICK)
        fabSnapshot?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

        // 1. 快门弹性按压动画
        fabSnapshot?.let { shutter ->
            shutter.animate().cancel()
            shutter.scaleX = 0.86f
            shutter.scaleY = 0.86f
            shutter.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        }

        // 2. 旗舰相机快门全屏白闪特效 (160ms 极速白闪)
        viewShutterFlash?.let { flash ->
            flash.visibility = View.VISIBLE
            flash.alpha = 0.72f
            flash.animate().cancel()
            flash.animate()
                .alpha(0.0f)
                .setDuration(160)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { flash.visibility = View.GONE }
                .start()
        }

        // 3. 触发真实全维四路抓拍
        val intent = Intent(this, SophonCoreService::class.java).apply {
            action = SophonCoreService.ACTION_TRIGGER_SNAPSHOT
        }
        startService(intent)

        // 4. 车规浮动 HUD 胶囊提示 (下滑淡入 + 自动回退)
        val isMounted = SophonApp.storageVault.isUsbMounted.value
        val initialHint = if (isMounted) "正在抓拍并存入外接相册..." else "正在抓拍 (存储未就绪，暂存机身沙盒)..."
        showCaptureToast(initialHint)
        refreshStorageAndNavStats()
    }

    private fun showCaptureToast(msg: String) {
        val toast = layoutCaptureToast ?: return
        tvCaptureToastMsg?.text = msg
        hideToastRunnable?.let { toast.removeCallbacks(it) }

        toast.visibility = View.VISIBLE
        toast.alpha = 0f
        toast.translationY = -90f
        toast.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val runnable = Runnable {
            toast.animate()
                .alpha(0f)
                .translationY(-90f)
                .setDuration(200)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { toast.visibility = View.GONE }
                .start()
        }
        hideToastRunnable = runnable
        toast.postDelayed(runnable, 2000)
    }

    private fun triggerEmergencyLock() {
        window.decorView.playSoundEffect(android.view.SoundEffectConstants.CLICK)
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val intent = Intent(this, SophonCoreService::class.java).apply {
            action = SophonCoreService.ACTION_TRIGGER_LOCK
        }
        startService(intent)
        showEmergencyLockEffect()
        refreshStorageAndNavStats()
    }

    /**
     * 紧急碰撞/加锁座舱 HUD 专属动效 (金盾冲击弹入 + 警示微闪 + 上浮入轨)
     */
    private fun showEmergencyLockEffect() {
        val overlay = findViewById<View>(R.id.layout_emergency_lock_overlay) ?: return
        val flash = findViewById<View>(R.id.v_emergency_lock_flash)
        val card = findViewById<View>(R.id.card_emergency_lock_badge)

        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f

        // 1. 全屏红色警示微闪 (180ms 极速渐隐)
        flash?.alpha = 0.65f
        flash?.animate()?.cancel()
        flash?.animate()
            ?.alpha(0f)
            ?.setDuration(360)
            ?.start()

        // 2. 徽章弹性冲击弹入并上浮消隐
        card?.scaleX = 0.45f
        card?.scaleY = 0.45f
        card?.alpha = 0f
        card?.translationY = 0f
        card?.animate()?.cancel()
        card?.animate()
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.alpha(1.0f)
            ?.setDuration(300)
            ?.setInterpolator(OvershootInterpolator(2.2f))
            ?.withEndAction {
                card.postDelayed({
                    card.animate()
                        ?.alpha(0f)
                        ?.scaleX(0.7f)
                        ?.scaleY(0.7f)
                        ?.translationY(-100f)
                        ?.setDuration(240)
                        ?.setInterpolator(AccelerateInterpolator())
                        ?.withEndAction {
                            overlay.visibility = View.GONE
                        }
                        ?.start()
                }, 1000L)
            }
            ?.start()
    }

    private fun toggleQuadSingleView() {
        isSingleCamMode = !isSingleCamMode
        val singleCamOsd = findViewById<View>(R.id.layout_single_cam_osd)

        val camBack = findViewById<View>(R.id.container_cam_back)
        val camLeft = findViewById<View>(R.id.container_cam_left)
        val camRight = findViewById<View>(R.id.container_cam_right)

        updateViewModeButtonState(animate = true)

        if (isSingleCamMode) {
            singleCamOsd?.alpha = 0f
            singleCamOsd?.visibility = View.VISIBLE
            singleCamOsd?.animate()?.alpha(1f)?.setDuration(200)?.start()

            camBack?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { camBack.visibility = View.GONE }?.start()
            camLeft?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { camLeft.visibility = View.GONE }?.start()
            camRight?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { camRight.visibility = View.GONE }?.start()
            tvStatusTitle.text = "前路 · 主摄全屏监看"
        } else {
            singleCamOsd?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction { singleCamOsd.visibility = View.GONE }?.start()

            listOf(camBack, camLeft, camRight).forEach { cam ->
                cam?.alpha = 0f
                cam?.visibility = View.VISIBLE
                cam?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
            tvStatusTitle.text = "四向全维实时监看"
        }
    }

    private fun toggleMic() {
        val prefs = SophonApp.preferences
        val newState = !prefs.isAudioRecordEnabled
        prefs.isAudioRecordEnabled = newState
        val core = com.vongzh.sophon.service.SophonCoreService.instance
        val active = core?.refreshCabinAudio() ?: false
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        updateMicButtonState(animate = true)

        val msg = when {
            !newState -> "车内录音已静音"
            active -> "车内录音已开启"
            !granted -> "未授予麦克风权限，录像不会包含音轨"
            core?.isRecordingFlow?.value != true -> "已记录，将在开始录制时自动收音"
            else -> "麦克风被其他应用占用，将在下次录制开始时重试"
        }
        showCaptureToast(msg)
    }

    private fun updateMicButtonState(animate: Boolean = false) {
        val isAudioEnabled = SophonApp.preferences.isAudioRecordEnabled
        val micButton = fabMic ?: return
        val micIcon = ivFabMic ?: return

        if (isAudioEnabled) {
            micButton.setBackgroundResource(R.drawable.sp_bg_dock_btn_active)
            micIcon.setImageResource(R.drawable.ic_perm_mic)
            micIcon.setColorFilter(Color.parseColor("#FFFFFF"))
            micIcon.alpha = 1.0f
            findViewById<TextView>(R.id.tv_fab_mic)?.text = "录音中"
        } else {
            micButton.setBackgroundResource(R.drawable.sp_bg_dock_btn_idle)
            micIcon.setImageResource(R.drawable.ic_mic_off)
            micIcon.setColorFilter(Color.parseColor("#B3FFFFFF"))
            micIcon.alpha = 0.75f
            findViewById<TextView>(R.id.tv_fab_mic)?.text = "已静音"
        }

        if (animate) {
            micButton.animate().cancel()
            micButton.scaleX = 0.85f
            micButton.scaleY = 0.85f
            micButton.rotation = if (isAudioEnabled) 15f else -15f
            micButton.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .rotation(0f)
                .setDuration(240)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        }
    }

    private fun updateViewModeButtonState(animate: Boolean = false) {
        val viewButton = fabViewMode ?: return
        val viewIcon = ivFabViewMode ?: return

        if (isSingleCamMode) {
            viewButton.setBackgroundResource(R.drawable.sp_bg_dock_btn_active)
            viewIcon.setImageResource(R.drawable.ic_view_single)
            viewIcon.setColorFilter(Color.parseColor("#FFFFFF"))
            viewIcon.alpha = 1.0f
            findViewById<TextView>(R.id.tv_fab_view_mode)?.text = "单路"
        } else {
            viewButton.setBackgroundResource(R.drawable.sp_bg_dock_btn_idle)
            viewIcon.setImageResource(R.drawable.ic_view_quad)
            viewIcon.setColorFilter(Color.parseColor("#B3FFFFFF"))
            viewIcon.alpha = 0.75f
            findViewById<TextView>(R.id.tv_fab_view_mode)?.text = "四路"
        }

        if (animate) {
            viewButton.animate().cancel()
            viewButton.scaleX = 0.85f
            viewButton.scaleY = 0.85f
            viewButton.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(240)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applySpringTouchFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180)
                        .setInterpolator(OvershootInterpolator(2.0f)).start()
                }
            }
            false
        }
    }

    private fun startRecBreathingAnimation() {
        if (recDotAnimator == null) {
            recDotAnimator = ValueAnimator.ofFloat(0.25f, 1.0f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    val alphaVal = animator.animatedValue as Float
                    dotRec.alpha = alphaVal
                    dotRecBanner.alpha = alphaVal
                    val scaleVal = 0.90f + 0.10f * alphaVal
                    dotRec.scaleX = scaleVal
                    dotRec.scaleY = scaleVal
                    dotRecBanner.scaleX = scaleVal
                    dotRecBanner.scaleY = scaleVal
                }
            }
        }
        if (recDotAnimator?.isRunning != true) {
            recDotAnimator?.start()
        }
    }

    private fun stopRecBreathingAnimation() {
        recDotAnimator?.cancel()
        dotRec.alpha = 0.2f
        dotRecBanner.alpha = 0.2f
        dotRec.scaleX = 1.0f
        dotRec.scaleY = 1.0f
        dotRecBanner.scaleX = 1.0f
        dotRecBanner.scaleY = 1.0f
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isSteeringWheelCustomKey(keyCode)) {
            if (event.isLongPress || event.repeatCount > 0) {
                SophonApp.vehicleSignalBus.dispatchHardKeyEvent(com.vongzh.sophon.domain.model.HardKeyEvent.LONG_PRESS)
                showEmergencyLockEffect()
                return true
            } else if (event.repeatCount == 0) {
                SophonApp.vehicleSignalBus.dispatchHardKeyEvent(com.vongzh.sophon.domain.model.HardKeyEvent.SHORT_PRESS)
                showCaptureToast("方向盘自定义键：四向画面已抓拍保存")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isSteeringWheelCustomKey(keyCode: Int): Boolean {
        return keyCode == android.view.KeyEvent.KEYCODE_BUTTON_1 ||
                keyCode == android.view.KeyEvent.KEYCODE_CAMERA ||
                keyCode == android.view.KeyEvent.KEYCODE_MEDIA_RECORD ||
                keyCode == android.view.KeyEvent.KEYCODE_STAR ||
                keyCode == 280
    }

    private fun startDaemonServices() {
        val coreIntent = Intent(this, SophonCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(coreIntent)
        } else {
            startService(coreIntent)
        }

        val overlayIntent = Intent(this, BlindSpotOverlayService::class.java)
        startService(overlayIntent)

        val capsuleIntent = Intent(this, com.vongzh.sophon.ui.statusbar.StatusBarCapsuleService::class.java)
        startService(capsuleIntent)

        // 悬浮窗矩阵：按用户开关对齐后视角 / 车辆状态 / 功能悬浮球三类小窗
        com.vongzh.sophon.service.OverlayMatrixController.apply(this)
    }

    private fun observeSystemStates() {
        val bus = SophonApp.vehicleSignalBus
        val storage = SophonApp.storageVault

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. 每秒刷新时钟、OSD 时间戳与录制胶囊
                launch {
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val osdFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    while (isActive) {
                        val now = Date()
                        tvTopClock.text = timeFormat.format(now)

                        syncAllPreviewSurfaces()

                        val isRec = SophonCoreService.instance?.isRecordingFlow?.value ?: false
                        val duration = SophonCoreService.instance?.recordingDurationSecFlow?.value ?: 0L
                        if (isRec) {
                            val durationStr = String.format(
                                Locale.getDefault(),
                                "%02d:%02d:%02d",
                                duration / 3600,
                                (duration % 3600) / 60,
                                duration % 60
                            )
                            tvTopRecTime.text = durationStr
                            tvTopRecTime.setTextColor(getColor(R.color.sp_on_rec))
                            startRecBreathingAnimation()

                            // 全局 REC 角标：画布顶部中央常驻
                            layoutRecBanner.visibility = View.VISIBLE
                            tvRecBanner.text = "REC $durationStr · 四路录制中"
                        } else {
                            tvTopRecTime.text = "录制就绪"
                            // 就绪态整体静默：灰点灰字，红色只留给真正的录制中，避免一眼像告警
                            stopRecBreathingAnimation()
                            tvTopRecTime.setTextColor(getColor(R.color.sp_tx2))
                            layoutRecBanner.visibility = View.GONE
                        }

                        // 真实遥测信号更新（GPS 硬件就绪且定位锁定时呈现真实坐标，无信号呈现 --，严禁伪造）
                        val gnss = SophonApp.vehicleSignalBus.gnssTelemetry.value
                        val gpsText = if (gnss.hasFix) gnss.toCoordinateString() else "--"
                        tvTelemetryGps.text = gpsText

                        val prefs = SophonApp.preferences
                        val sensText = when (prefs.crashSensitivity) {
                            0 -> "灵敏度低"
                            2 -> "灵敏度高"
                            else -> "灵敏度中"
                        }
                        val chunkMinutes = maxOf(1, prefs.chunkDurationSec / 60)
                        val chunkText = "${chunkMinutes}分钟分段"
                        val lockText = if (prefs.isAutoCrashLockEnabled) "循环覆盖已启用" else "循环覆盖已暂停"
                        // 读真实采集状态而非偏好原值：麦克风未授权/被占用/未在录制时，
                        // 开关虽开但 MP4 里没有音轨，显示「录音开启」就是假状态
                        val micReallyOn = SophonCoreService.instance?.isCabinAudioActive() == true
                        val micText = when {
                            !prefs.isAudioRecordEnabled -> "静音"
                            micReallyOn -> "录音开启"
                            else -> "待收音"
                        }
                        tvStatusSubtitle.text = "四路同步录制 · $lockText · $sensText · $chunkText · $micText"

                        // 更新可用硬件镜头状态指示小圆点 (绿灯就绪 / 灰灯离线，代替通道待命长文案)
                        tvSingleExposure.text = "自动测光 · AE开启"
                        tvSingleGps.text = "GPS: $gpsText"

                        SophonCoreService.instance?.availableCamerasFlow?.value?.let { avail ->
                            val hasFront = avail.contains(CameraPosition.FRONT)
                            val hasBack = avail.contains(CameraPosition.BACK)
                            val hasLeft = avail.contains(CameraPosition.LEFT)
                            val hasRight = avail.contains(CameraPosition.RIGHT)

                            // 机位角标三级状态：录制中红点 / 在线待命绿点 / 离线灰点
                            fun channelDot(hasCam: Boolean): Int = when {
                                isRec && hasCam -> R.drawable.sp_dot_rec
                                hasCam -> R.drawable.sp_dot_live_active
                                else -> R.drawable.sp_dot_live_inactive
                            }
                            dotOsdStatusFront.setBackgroundResource(channelDot(hasFront))
                            dotOsdStatusBack.setBackgroundResource(channelDot(hasBack))
                            dotOsdStatusLeft.setBackgroundResource(channelDot(hasLeft))
                            dotOsdStatusRight.setBackgroundResource(channelDot(hasRight))

                            // 机位角标文字：录制中显 REC（红），在线显 1080P，未接入时纯净隐蔽（彻底告别生硬抽象的“待接入”文本）
                            fun updateChannelSpec(tv: TextView?, hasCam: Boolean) {
                                if (tv == null) return
                                if (hasCam) {
                                    if (isRec) {
                                        tv.text = "REC"
                                        tv.setTextColor(getColor(R.color.sp_rec))
                                    } else {
                                        tv.text = "1080P"
                                        tv.setTextColor(getColor(R.color.sp_osd_tx2))
                                    }
                                    tv.visibility = View.VISIBLE
                                } else {
                                    tv.visibility = View.GONE
                                }
                            }
                            updateChannelSpec(tvOsdSpecFront, hasFront)
                            updateChannelSpec(tvOsdSpecBack, hasBack)
                            updateChannelSpec(tvOsdSpecLeft, hasLeft)
                            updateChannelSpec(tvOsdSpecRight, hasRight)

                            // 待命示意图展示逻辑：未推流时呈现高质感车规待命示意，推流时立即让位给真实视频
                            findViewById<View>(R.id.layout_standby_front)?.visibility = if (hasFront) View.GONE else View.VISIBLE
                            findViewById<View>(R.id.layout_standby_back)?.visibility = if (hasBack) View.GONE else View.VISIBLE
                            findViewById<View>(R.id.layout_standby_left)?.visibility = if (hasLeft) View.GONE else View.VISIBLE
                            findViewById<View>(R.id.layout_standby_right)?.visibility = if (hasRight) View.GONE else View.VISIBLE

                            tvSingleSpecs.text = if (hasFront) "1080P · 30fps" else "主摄通道连接中"
                            tvMainResFpsChip.text = if (hasFront) "1080P · 30fps" else "--"
                        }

                        delay(1000L)
                    }
                }

                // 2. 车机挡位与路况 OSD
                launch {
                    bus.gearState.collectLatest { gear ->
                        val gearText = when (gear) {
                            GearState.PARK -> "P 挡"
                            GearState.REVERSE -> "R 挡"
                            GearState.NEUTRAL -> "N 挡"
                            GearState.DRIVE -> "D 挡"
                            GearState.UNKNOWN -> "-- 挡"
                        }
                        tvTelemetryGear.text = gearText
                        if (gear == GearState.PARK) {
                            tvSingleRoadInfo.text = "P 挡驻车 · 哨兵待命守护"
                        } else {
                            tvSingleRoadInfo.text = "$gearText 行车巡航 · 四路视觉守护"
                        }
                    }
                }

                // 3. 车速
                launch {
                    bus.vehicleSpeed.collectLatest { speed ->
                        tvTelemetrySpeed.text = String.format(Locale.getDefault(), "%.0f km/h", speed)
                        findViewById<TextView>(R.id.tv_single_speed_num)?.text = String.format(Locale.getDefault(), "%.0f", speed)
                    }
                }

                // 3b. 车外环境温度 (真实 VHAL 信号驱动，无信号时诚实显示 -- ℃)
                launch {
                    bus.outsideTemperature.collectLatest { temp ->
                        tvTelemetryTemp.text = if (temp != null) {
                            String.format(Locale.getDefault(), "%.1f ℃", temp)
                        } else {
                            "-- ℃"
                        }
                    }
                }

                // 3c. VHAL 权限失败告警与显式化
                launch {
                    (bus as? com.vongzh.sophon.core.vhal.VehicleSignalBusImpl)?.vhalAuthError?.collectLatest { err ->
                        if (!err.isNullOrBlank()) {
                            android.util.Log.w("MainActivity", "VHAL unprivileged: $err")
                        }
                    }
                }

                // 4. 外接存储状态
                launch {
                    storage.isUsbMounted.collectLatest { isMounted ->
                        if (isMounted) {
                            tvUsbStatus.text = "外接存储已挂载"
                            tvUsbStatus.setTextColor(getColor(R.color.sp_ac))
                        } else {
                            tvUsbStatus.text = "外接存储未就绪"
                            tvUsbStatus.setTextColor(getColor(R.color.sp_wn))
                        }
                        refreshStorageAndNavStats()
                    }
                }

                // 5. 抓拍完成事件监听 (真实结果回调)
                launch {
                    SophonCoreService.snapshotEventFlow.collectLatest { result ->
                        showCaptureToast(result.message)
                        refreshStorageAndNavStats()
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && SophonApp.preferences.isPipAutoEnabled) {
            try {
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                // 忽略系统未授权或暂不支持 PiP
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val navRail = findViewById<View>(R.id.layout_nav_rail)
        val topBarContainer = findViewById<View>(R.id.layout_top_bar_container)
        if (isInPictureInPictureMode) {
            navRail?.visibility = View.GONE
            topBarContainer?.visibility = View.GONE
        } else {
            navRail?.visibility = View.VISIBLE
            topBarContainer?.visibility = if (currentTab == MainTab.LIVE) View.VISIBLE else View.GONE
        }
    }

    /**
     * 响应系统设置中「功能按键显示文字描述」开关：
     * 开启时收音与视角按键展示文字标签；关闭时收敛为紧凑图标按钮。
     * （注：中央快门按键为相机同心双环专属造型，保持经典纯净圆钮设计）
     */
    private fun updateFabLabelsVisibility() {
        val showLabels = SophonApp.preferences.isShowFabLabels
        val density = resources.displayMetrics.density
        val padH = if (showLabels) (14 * density).toInt() else (11 * density).toInt()

        findViewById<View>(R.id.tv_fab_mic)?.visibility = if (showLabels) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_fab_view_mode)?.visibility = if (showLabels) View.VISIBLE else View.GONE

        fabMic?.setPadding(padH, 0, padH, 0)
        fabViewMode?.setPadding(padH, 0, padH, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecBreathingAnimation()
        recDotAnimator = null
        hideToastRunnable?.let { runnable ->
            layoutCaptureToast?.removeCallbacks(runnable)
        }
    }
}
