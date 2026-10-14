package com.vongzh.sophon.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.domain.model.GearState
import com.vongzh.sophon.service.BlindSpotOverlayService
import com.vongzh.sophon.service.SophonCoreService
import com.vongzh.sophon.ui.photo.PhotoViewerActivity
import com.vongzh.sophon.ui.playback.QuadSyncPlayerActivity
import com.vongzh.sophon.ui.widget.StorageCapacityRingView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 极视 (Sophon) 座舱主控界面 (MainActivity)
 *
 * 【架构规范 - 避免在 MainActivity 中堆积业务逻辑】
 * 仅承担 UI 呈现、生命周期调度与状态观察，录制与信号处理彻底委托至后台守护进程与 Manager 类。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusTitle: TextView
    private lateinit var tvTelemetryGear: TextView
    private lateinit var tvTelemetrySpeed: TextView
    private lateinit var tvUsbStatus: TextView
    private lateinit var storageRingMini: StorageCapacityRingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkAndRequestCameraPermission()
        observeSystemStates()
        checkShowLanShare(intent)
    }

    private fun checkAndRequestCameraPermission() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startDaemonServices()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
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
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startDaemonServices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkShowLanShare(intent)
    }

    private fun checkShowLanShare(intent: Intent?) {
        if (intent?.getBooleanExtra("action_show_lan_share", false) == true) {
            com.vongzh.sophon.ui.lan.LanShareDialog(this).show()
        }
    }

    private var isSingleCamMode = false

    private fun initViews() {
        tvStatusTitle = findViewById(R.id.tv_status_title)
        tvTelemetryGear = findViewById(R.id.tv_telemetry_gear)
        tvTelemetrySpeed = findViewById(R.id.tv_telemetry_speed)
        tvUsbStatus = findViewById(R.id.tv_usb_status)
        storageRingMini = findViewById(R.id.view_storage_ring_mini)

        findViewById<View>(R.id.btn_nav_driving).setOnClickListener {
            val intent = Intent(this, com.vongzh.sophon.ui.history.RecordingHistoryActivity::class.java).apply {
                putExtra(com.vongzh.sophon.ui.history.RecordingHistoryActivity.EXTRA_FILTER, com.vongzh.sophon.ui.history.RecordingHistoryActivity.FILTER_NORMAL)
            }
            startActivity(intent)
        }

        findViewById<View>(R.id.btn_nav_sentry).setOnClickListener {
            startActivity(Intent(this, com.vongzh.sophon.ui.sentry.SentryEventReviewActivity::class.java))
        }

        findViewById<View>(R.id.btn_nav_photos).setOnClickListener {
            startActivity(Intent(this, PhotoViewerActivity::class.java))
        }

        findViewById<View>(R.id.btn_nav_settings).setOnClickListener {
            startActivity(Intent(this, com.vongzh.sophon.ui.settings.SophonSettingsActivity::class.java))
        }

        findViewById<View>(R.id.btn_top_lan_share).setOnClickListener {
            com.vongzh.sophon.ui.lan.LanShareDialog(this).show()
        }

        // 快门与悬浮控制条
        findViewById<View>(R.id.btn_shutter)?.setOnClickListener { triggerSnapshot() }
        findViewById<View>(R.id.fab_snapshot)?.setOnClickListener { triggerSnapshot() }
        findViewById<View>(R.id.btn_lock_current)?.setOnClickListener { triggerEmergencyLock() }
        findViewById<View>(R.id.fab_lock)?.setOnClickListener { triggerEmergencyLock() }
        findViewById<View>(R.id.btn_emergency)?.setOnClickListener { triggerEmergencyLock() }

        findViewById<View>(R.id.btn_recent_clip)?.setOnClickListener {
            startActivity(Intent(this, com.vongzh.sophon.ui.history.RecordingHistoryActivity::class.java))
        }

        findViewById<View>(R.id.btn_view_toggle)?.setOnClickListener { toggleQuadSingleView() }
        findViewById<View>(R.id.fab_view_mode)?.setOnClickListener { toggleQuadSingleView() }
        findViewById<View>(R.id.fab_mic)?.setOnClickListener { toggleMic() }

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

    private fun triggerSnapshot() {
        val intent = Intent(this, SophonCoreService::class.java).apply {
            action = SophonCoreService.ACTION_TRIGGER_SNAPSHOT
        }
        startService(intent)
        android.widget.Toast.makeText(this, "📷 已触发全维四路原图抓拍", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun triggerEmergencyLock() {
        val intent = Intent(this, SophonCoreService::class.java).apply {
            action = SophonCoreService.ACTION_TRIGGER_LOCK
        }
        startService(intent)
        android.widget.Toast.makeText(this, "🔒 已紧急锁定当前行车切片", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toggleQuadSingleView() {
        isSingleCamMode = !isSingleCamMode
        val singleCamOsd = findViewById<View>(R.id.layout_single_cam_osd)
        val btnViewToggle = findViewById<TextView>(R.id.btn_view_toggle)

        val camBack = findViewById<View>(R.id.container_cam_back)
        val camLeft = findViewById<View>(R.id.container_cam_left)
        val camRight = findViewById<View>(R.id.container_cam_right)

        if (isSingleCamMode) {
            singleCamOsd?.visibility = View.VISIBLE
            btnViewToggle?.text = "返回四路"
            camBack?.visibility = View.GONE
            camLeft?.visibility = View.GONE
            camRight?.visibility = View.GONE
            tvStatusTitle.text = "前路 · 主摄全屏监看"
        } else {
            singleCamOsd?.visibility = View.GONE
            btnViewToggle?.text = "四路同屏"
            camBack?.visibility = View.VISIBLE
            camLeft?.visibility = View.VISIBLE
            camRight?.visibility = View.VISIBLE
            tvStatusTitle.text = "四向全维实时监看"
        }
    }

    private fun toggleMic() {
        val ivMic = findViewById<ImageView>(R.id.iv_fab_mic)
        val isMuted = ivMic?.alpha == 0.4f
        if (isMuted) {
            ivMic?.alpha = 1.0f
            android.widget.Toast.makeText(this, "🎙 车内录音已开启", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            ivMic?.alpha = 0.4f
            android.widget.Toast.makeText(this, "🔇 车内录音已静音", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isSteeringWheelCustomKey(keyCode)) {
            if (event.isLongPress || event.repeatCount > 0) {
                SophonApp.vehicleSignalBus.dispatchHardKeyEvent(com.vongzh.sophon.domain.model.HardKeyEvent.LONG_PRESS)
                android.widget.Toast.makeText(this, "🔒 方向盘按键长按：已紧急锁死当前视频", android.widget.Toast.LENGTH_SHORT).show()
                return true
            } else if (event.repeatCount == 0) {
                SophonApp.vehicleSignalBus.dispatchHardKeyEvent(com.vongzh.sophon.domain.model.HardKeyEvent.SHORT_PRESS)
                android.widget.Toast.makeText(this, "📷 方向盘按键抓拍成功", android.widget.Toast.LENGTH_SHORT).show()
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
    }

    private fun observeSystemStates() {
        val bus = SophonApp.vehicleSignalBus
        val storage = SophonApp.storageVault

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bus.gearState.collectLatest { gear ->
                        tvTelemetryGear.text = when (gear) {
                            GearState.PARK -> "P 挡"
                            GearState.REVERSE -> "R 挡"
                            GearState.NEUTRAL -> "N 挡"
                            GearState.DRIVE -> "D 挡"
                            GearState.UNKNOWN -> "-- 挡"
                        }
                    }
                }

                launch {
                    bus.vehicleSpeed.collectLatest { speed ->
                        tvTelemetrySpeed.text = String.format("%.0f km/h", speed)
                        findViewById<TextView>(R.id.tv_single_speed_num)?.text = String.format("%.0f", speed)
                    }
                }

                launch {
                    storage.isUsbMounted.collectLatest { isMounted ->
                        if (isMounted) {
                            tvUsbStatus.text = "U盘已挂载"
                            tvUsbStatus.setTextColor(getColor(R.color.sp_ac))
                            storageRingMini.updateBreakdown(storage.queryStorageBreakdown())
                        } else {
                            tvUsbStatus.text = "U盘未就绪"
                            tvUsbStatus.setTextColor(getColor(R.color.sp_wn))
                        }
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
        val topBar = findViewById<View>(R.id.layout_top_status_bar)
        if (isInPictureInPictureMode) {
            navRail?.visibility = View.GONE
            topBar?.visibility = View.GONE
        } else {
            navRail?.visibility = View.VISIBLE
            topBar?.visibility = View.VISIBLE
        }
    }
}
