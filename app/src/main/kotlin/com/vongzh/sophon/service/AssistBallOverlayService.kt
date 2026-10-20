package com.vongzh.sophon.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.vongzh.sophon.R
import com.vongzh.sophon.SophonApp
import com.vongzh.sophon.ui.MainActivity
import kotlin.math.abs

/**
 * 悬浮窗矩阵 · 功能悬浮球 (AssistBallOverlayService)
 *
 * 可拖拽圆球常驻屏幕右侧，单击展开快捷菜单：
 * 一键抓拍 / 紧急加锁 / 哨兵布撤防 / 回到主界面 / 收起悬浮球。
 *
 * 由 SophonPreferences.isAssistBallEnabled 控制总开关（默认关闭）。
 */
class AssistBallOverlayService : Service() {

    companion object {
        private const val TAG = "Sophon.AssistBall"
        private const val BALL_SIZE_DP = 56
        private const val MENU_WIDTH_DP = 168
        private const val DRAG_SLOP_PX = 8
    }

    private var windowManager: WindowManager? = null
    private var ballView: FrameLayout? = null
    private var menuView: LinearLayout? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var isBallAttached = false
    private var isMenuAttached = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        buildBall()
        attachBall()
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun buildBall() {
        val density = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()

        ballView = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.sp_bg_assist_ball)
            elevation = 12f * density
        }
        val tvIcon = TextView(this).apply {
            text = "极视"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AssistBallOverlayService, R.color.sp_tx_inverse))
            gravity = Gravity.CENTER
        }
        ballView?.addView(
            tvIcon,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        ballView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = ballParams?.x ?: 0
                    downY = ballParams?.y ?: 0
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val p = ballParams ?: return@setOnTouchListener true
                        p.x = downX + dx.toInt()
                        p.y = downY + dy.toInt()
                        windowManager?.updateViewLayout(ballView, p)
                        syncMenuPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        ballView?.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        ballView?.setOnClickListener {
            if (isMenuAttached) hideMenu() else showMenu()
        }

        ballParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 24
            y = 0
        }
    }

    private fun buildMenu() {
        if (menuView != null) return
        val density = resources.displayMetrics.density
        val width = (MENU_WIDTH_DP * density).toInt()

        menuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sp_bg_card)
            elevation = 16f * density
            setPadding(8, 8, 8, 8)
        }

        addMenuItem("一键抓拍", R.drawable.ic_action_camera) {
            triggerCoreAction(SophonCoreService.ACTION_TRIGGER_SNAPSHOT, "已触发四路抓拍")
        }
        addMenuItem("紧急加锁", R.drawable.ic_action_lock) {
            triggerCoreAction(SophonCoreService.ACTION_TRIGGER_LOCK, "已锁定当前视频切片")
        }
        addMenuItem("哨兵布撤防", R.drawable.ic_settings_guard) { toggleSentry() }
        addMenuItem("回到主界面", R.drawable.ic_action_home) { openMainActivity() }
        addMenuItem("收起悬浮球", R.drawable.ic_action_close) {
            SophonApp.preferences.isAssistBallEnabled = false
            stopSelf()
        }

        menuParams = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun addMenuItem(text: String, iconRes: Int, onClick: () -> Unit) {
        val item = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AssistBallOverlayService, R.color.sp_tx))
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
            compoundDrawableTintList =
                ContextCompat.getColorStateList(this@AssistBallOverlayService, R.color.sp_ac)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 0, 14, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (52 * resources.displayMetrics.density).toInt()
            )
            com.vongzh.sophon.ui.widget.UiMotionUtils.attachPressFeedback(this, 0.95f)
            setOnClickListener {
                onClick()
                hideMenu()
            }
        }
        menuView?.addView(item)
    }

    private fun showMenu() {
        buildMenu()
        if (isMenuAttached) return
        syncMenuPosition()
        val view = menuView ?: return
        try {
            view.alpha = 0f
            view.scaleX = 0.88f
            view.scaleY = 0.88f
            windowManager?.addView(view, menuParams)
            isMenuAttached = true
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show assist menu", e)
        }
    }

    private fun hideMenu() {
        if (!isMenuAttached) return
        val view = menuView ?: return
        try {
            view.animate()
                .alpha(0f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(120)
                .withEndAction {
                    try {
                        if (isMenuAttached) {
                            windowManager?.removeViewImmediate(view)
                            isMenuAttached = false
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error removing assist menu", e)
                    }
                }
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "Error animating assist menu hide", e)
            try {
                windowManager?.removeViewImmediate(view)
            } catch (e2: Exception) {
                // ignore
            }
            isMenuAttached = false
        }
    }

    private fun syncMenuPosition() {
        val bp = ballParams ?: return
        menuParams?.gravity = Gravity.TOP or Gravity.START
        // 菜单贴靠小球左侧，随拖拽跟随
        menuParams?.x = bp.x.coerceAtLeast(24)
        menuParams?.y = bp.y
    }

    private fun triggerCoreAction(action: String, toast: String) {
        try {
            val intent = Intent(this, SophonCoreService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch $action", e)
        }
    }

    private fun toggleSentry() {
        val prefs = SophonApp.preferences
        val next = !prefs.isSentryModeEnabled
        prefs.isSentryModeEnabled = next
        Toast.makeText(this, if (next) "哨兵模式已布防" else "哨兵模式已撤防", Toast.LENGTH_SHORT).show()
    }

    private fun openMainActivity() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity", e)
        }
    }

    private fun attachBall() {
        if (isBallAttached) return
        try {
            windowManager?.addView(ballView, ballParams)
            isBallAttached = true
            Log.d(TAG, "Assist ball attached.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach assist ball", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        hideMenu()
        if (isBallAttached) {
            try {
                windowManager?.removeViewImmediate(ballView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing assist ball", e)
            } finally {
                isBallAttached = false
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
