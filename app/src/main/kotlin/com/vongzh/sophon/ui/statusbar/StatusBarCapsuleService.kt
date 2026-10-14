package com.vongzh.sophon.ui.statusbar

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.service.SophonCoreService
import com.vongzh.sophon.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 车机顶部状态栏胶囊与快捷卡片服务 (StatusBarCapsuleService)
 *
 * 【对标盯盯车核心交互】
 * 1. 悬停于领克 08 屏幕最顶端状态栏 (Gravity.TOP)；
 * 2. 实时常驻展示：🔴 录制呼吸红点 + 00:00:00 秒表 + 🖫 U 盘指示；
 * 3. 支持左右水平拖动调整位置并持久化保存 (island_x)；
 * 4. 单手轻触弹出快捷控制卡片 (抓拍/加锁/主页)，4 秒无操作自动收起；
 * 5. 严格落实【底线一】：挂入 R 挡或原厂 360 全景激活时，50ms 内极速隐身让位！
 */
class StatusBarCapsuleService : Service() {

    companion object {
        private const val TAG = "Sophon.CapsuleService"
        private const val PREF_NAME = "sophon_statusbar_pref"
        private const val KEY_CAPSULE_X = "capsule_x"
        private const val DEFAULT_X = 600
        private const val AUTO_DISMISS_DELAY_MS = 4000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private lateinit var prefs: SharedPreferences

    // 1. 胶囊组件
    private var capsuleView: View? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var isCapsuleAttached = false

    // 2. 展开快捷卡片组件
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isPanelAttached = false
    private var panelDismissJob: Job? = null

    // 运行状态
    private var recordingDurationSec = 0L
    private var isRecording = true

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "StatusBarCapsuleService onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        initCapsuleView()
        initPanelView()
        attachCapsuleToStatusBar()
        observeSystemTelemetry()
        startLiveTimerLoop()
    }

    private fun initCapsuleView() {
        capsuleView = LayoutInflater.from(this).inflate(R.layout.view_status_bar_capsule, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val savedX = prefs.getInt(KEY_CAPSULE_X, DEFAULT_X)

        capsuleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = 6 // 精准对齐车机顶端状态栏高度
        }

        // 触摸与拖动监听
        capsuleView?.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var initialTouchX = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = capsuleParams ?: return false
                val wm = windowManager ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        initialTouchX = event.rawX
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        if (abs(dx) > 10) {
                            isDragging = true
                            params.x = maxOf(0, startX + dx)
                            try {
                                wm.updateViewLayout(capsuleView, params)
                            } catch (e: Exception) {
                                Log.w(TAG, "Update capsule drag position failed", e)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            prefs.edit().putInt(KEY_CAPSULE_X, params.x).apply()
                        } else {
                            // 轻按触发：切换快捷卡片展开态
                            toggleQuickPanel()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun initPanelView() {
        panelView = LayoutInflater.from(this).inflate(R.layout.view_status_bar_panel, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = DEFAULT_X
            y = 44 // 在胶囊正下方弹出
        }

        panelView?.findViewById<TextView>(R.id.tv_panel_close)?.setOnClickListener {
            dismissQuickPanel()
        }

        // 抓拍按钮
        panelView?.findViewById<TextView>(R.id.btn_panel_snapshot)?.setOnClickListener {
            Toast.makeText(this, "📷 已触发四向即时抓拍", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, SophonCoreService::class.java).apply {
                action = SophonCoreService.ACTION_TRIGGER_SNAPSHOT
            }
            startService(intent)
            resetDismissTimer()
        }

        // 加锁按钮
        panelView?.findViewById<TextView>(R.id.btn_panel_lock)?.setOnClickListener {
            Toast.makeText(this, "🔒 当前视频切片已加锁保护", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, SophonCoreService::class.java).apply {
                action = SophonCoreService.ACTION_TRIGGER_LOCK
            }
            startService(intent)
            resetDismissTimer()
        }

        // 免流快传按钮
        panelView?.findViewById<TextView>(R.id.btn_panel_share)?.setOnClickListener {
            dismissQuickPanel()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("action_show_lan_share", true)
            }
            startActivity(mainIntent)
        }

        // 主页按钮
        panelView?.findViewById<TextView>(R.id.btn_panel_open)?.setOnClickListener {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(mainIntent)
            dismissQuickPanel()
        }
    }

    private fun attachCapsuleToStatusBar() {
        if (!isCapsuleAttached && capsuleView != null && capsuleParams != null) {
            try {
                windowManager?.addView(capsuleView, capsuleParams)
                isCapsuleAttached = true
                Log.i(TAG, "Status bar capsule attached successfully at x=${capsuleParams?.x}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach status bar capsule", e)
            }
        }
    }

    private fun toggleQuickPanel() {
        if (isPanelAttached) {
            dismissQuickPanel()
        } else {
            showQuickPanel()
        }
    }

    private fun showQuickPanel() {
        val wm = windowManager ?: return
        val view = panelView ?: return
        val params = panelParams ?: return
        val capParams = capsuleParams ?: return

        // 动态将快捷卡片对齐在胶囊正下方中心
        params.x = maxOf(20, capParams.x - 70)
        params.y = capParams.y + 36

        if (!isPanelAttached) {
            try {
                wm.addView(view, params)
                isPanelAttached = true
                resetDismissTimer()
                Log.d(TAG, "Quick action panel opened")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show quick panel", e)
            }
        }
    }

    private fun dismissQuickPanel() {
        panelDismissJob?.cancel()
        if (isPanelAttached && panelView != null) {
            try {
                windowManager?.removeViewImmediate(panelView)
                isPanelAttached = false
                Log.d(TAG, "Quick action panel dismissed")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dismiss quick panel", e)
            }
        }
    }

    private fun resetDismissTimer() {
        panelDismissJob?.cancel()
        panelDismissJob = serviceScope.launch {
            delay(AUTO_DISMISS_DELAY_MS)
            dismissQuickPanel()
        }
    }

    /**
     * 响应式监听车身总线与 U 盘状态
     */
    private fun observeSystemTelemetry() {
        val bus = SophonApp.vehicleSignalBus
        val storage = SophonApp.storageVault

        // 1. 50ms AVM 全景避让：倒车或全景雷达弹出时，瞬间隐身避让
        serviceScope.launch {
            bus.isAvmActive.collectLatest { isAvm ->
                if (isAvm) {
                    dismissQuickPanel()
                    capsuleView?.visibility = View.GONE
                    Log.d(TAG, "AVM Active: Status bar capsule preempted and hidden (<50ms)")
                } else {
                    capsuleView?.visibility = View.VISIBLE
                }
            }
        }

        // 2. U 盘插拔状态
        serviceScope.launch {
            storage.isUsbMounted.collectLatest { isMounted ->
                capsuleView?.findViewById<TextView>(R.id.tv_capsule_usb)?.let { tv ->
                    if (isMounted) {
                        tv.text = "🖫"
                        tv.setTextColor(getColor(R.color.flyme_cyan))
                    } else {
                        tv.text = "✕"
                        tv.setTextColor(getColor(R.color.accent_warning))
                    }
                }
                panelView?.findViewById<TextView>(R.id.tv_panel_storage_info)?.text =
                    if (isMounted) "U盘状态: 正常已挂载" else "U盘状态: 未插入 (待机模式)"
            }
        }
    }

    private fun startLiveTimerLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (isRecording) {
                    recordingDurationSec++
                    val h = recordingDurationSec / 3600
                    val m = (recordingDurationSec % 3600) / 60
                    val s = recordingDurationSec % 60
                    val timeStr = String.format("%02d:%02d:%02d", h, m, s)
                    capsuleView?.findViewById<TextView>(R.id.tv_capsule_time)?.text = timeStr
                }
            }
        }
    }

    override fun onDestroy() {
        dismissQuickPanel()
        if (isCapsuleAttached && capsuleView != null) {
            try {
                windowManager?.removeViewImmediate(capsuleView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing capsule view", e)
            }
            isCapsuleAttached = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
