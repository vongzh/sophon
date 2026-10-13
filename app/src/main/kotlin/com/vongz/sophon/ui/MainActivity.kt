package com.vongz.sophon.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vongz.sophon.R
import com.vongz.sophon.SophonApp
import com.vongz.sophon.domain.model.GearState
import com.vongz.sophon.service.BlindSpotOverlayService
import com.vongz.sophon.service.SophonCoreService
import com.vongz.sophon.ui.photo.PhotoViewerActivity
import com.vongz.sophon.ui.playback.QuadSyncPlayerActivity
import com.vongz.sophon.ui.widget.StorageCapacityRingView
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
        startDaemonServices()
        observeSystemStates()
        checkShowLanShare(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkShowLanShare(intent)
    }

    private fun checkShowLanShare(intent: Intent?) {
        if (intent?.getBooleanExtra("action_show_lan_share", false) == true) {
            com.vongz.sophon.ui.lan.LanShareDialog(this).show()
        }
    }

    private fun initViews() {
        tvStatusTitle = findViewById(R.id.tv_status_title)
        tvTelemetryGear = findViewById(R.id.tv_telemetry_gear)
        tvTelemetrySpeed = findViewById(R.id.tv_telemetry_speed)
        tvUsbStatus = findViewById(R.id.tv_usb_status)
        storageRingMini = findViewById(R.id.view_storage_ring_mini)

        findViewById<View>(R.id.btn_nav_driving).setOnClickListener {
            startActivity(Intent(this, QuadSyncPlayerActivity::class.java))
        }

        findViewById<View>(R.id.btn_nav_sentry).setOnClickListener {
            startActivity(Intent(this, QuadSyncPlayerActivity::class.java))
        }

        findViewById<View>(R.id.btn_nav_photos).setOnClickListener {
            startActivity(Intent(this, PhotoViewerActivity::class.java))
        }

        findViewById<View>(R.id.btn_nav_settings).setOnClickListener {
            startActivity(Intent(this, com.vongz.sophon.ui.settings.SophonSettingsActivity::class.java))
        }

        findViewById<View>(R.id.btn_top_lan_share).setOnClickListener {
            com.vongz.sophon.ui.lan.LanShareDialog(this).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isSteeringWheelCustomKey(keyCode)) {
            if (event.isLongPress || event.repeatCount > 0) {
                SophonApp.vehicleSignalBus.dispatchHardKeyEvent(com.vongz.sophon.domain.model.HardKeyEvent.LONG_PRESS)
                android.widget.Toast.makeText(this, "🔒 方向盘按键长按：已紧急锁死当前视频", android.widget.Toast.LENGTH_SHORT).show()
                return true
            } else if (event.repeatCount == 0) {
                SophonApp.vehicleSignalBus.dispatchHardKeyEvent(com.vongz.sophon.domain.model.HardKeyEvent.SHORT_PRESS)
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

        val capsuleIntent = Intent(this, com.vongz.sophon.ui.statusbar.StatusBarCapsuleService::class.java)
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
                    }
                }

                launch {
                    storage.isUsbMounted.collectLatest { isMounted ->
                        if (isMounted) {
                            tvUsbStatus.text = "U盘已挂载"
                            tvUsbStatus.setTextColor(getColor(R.color.flyme_cyan))
                            storageRingMini.updateBreakdown(storage.queryStorageBreakdown())
                        } else {
                            tvUsbStatus.text = "U盘未就绪"
                            tvUsbStatus.setTextColor(getColor(R.color.accent_warning))
                        }
                    }
                }
            }
        }
    }
}
