package com.vongzh.sophon.ui.sentry

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vongzh.sophon.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 哨兵震动告警中控屏“威慑警示眼”全屏视窗 (SentryDeterrentActivity)
 *
 * 【座舱威慑与险情提示】
 * 1. 驻车熄火哨兵感知到车身震动碰撞时，自动点亮中控屏；
 * 2. 呈现 FlymeAuto 风格红色呼吸脉冲警示眼，震慑靠近可疑人员；
 * 3. 倒计时 12 秒后自动渐隐退出以保护车机功耗，或车主上车点击屏幕即刻关闭。
 */
class SentryDeterrentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DETERRENT_LEVEL = "extra_deterrent_level"
        const val LEVEL_NOTICE = 1 // 一级轻度提醒：拟人化星瞳，不闪大灯不鸣笛 (借鉴理想)
        const val LEVEL_ALARM = 2  // 二级重度威慑：红色警示脉冲，声光与远程报警
    }

    private var pulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeScreenUp()
        setContentView(R.layout.activity_sentry_deterrent)

        initViews()
        startEyePulsing()
        startAutoDismissTimer()
    }

    private fun wakeScreenUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式全屏
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun initViews() {
        findViewById<View>(R.id.sentry_deterrent_root).setOnClickListener {
            dismissGracefully()
        }

        val level = intent.getIntExtra(EXTRA_DETERRENT_LEVEL, LEVEL_ALARM)
        val tvHeader = findViewById<android.widget.TextView>(R.id.tv_deterrent_header)
        val tvSubtitle = findViewById<android.widget.TextView>(R.id.tv_deterrent_subtitle)
        val tvBadge = findViewById<android.widget.TextView>(R.id.tv_deterrent_badge)
        val eyeView = findViewById<SentryEyeIrisView>(R.id.sentry_eye_view)

        if (level == LEVEL_NOTICE) {
            // 一级轻度提醒：温和星瞳守护主题，拟人化友好守护
            eyeView?.setMode(SentryEyeIrisView.IrisMode.NOTICE)
            val blueColor = 0xFF3572F4.toInt()
            val blueHaloBg = 0x263572F4.toInt()

            tvHeader?.text = "SOPHON SENTRY · GUARDING"
            tvHeader?.setTextColor(blueColor)

            tvSubtitle?.text = "极视全维哨兵持续守护中"

            tvBadge?.text = "侦测到人员靠近 · 车辆处于安全防卫监控中"
            tvBadge?.setTextColor(blueColor)
            tvBadge?.backgroundTintList = android.content.res.ColorStateList.valueOf(blueHaloBg)
        } else {
            // 二级高危威慑：警示红眼爆闪，事故加锁
            eyeView?.setMode(SentryEyeIrisView.IrisMode.ALARM)
            val redColor = 0xFFFF453A.toInt()
            val redHaloBg = 0x33FF453A.toInt()

            tvHeader?.text = "SOPHON SENTRY · ALARM"
            tvHeader?.setTextColor(redColor)

            tvSubtitle?.text = "高危警报 · 四路全维视频已实时加密锁存"

            tvBadge?.text = "侦测到车身冲击 · 关键事故证据已实时加锁存证"
            tvBadge?.setTextColor(redColor)
            tvBadge?.backgroundTintList = android.content.res.ColorStateList.valueOf(redHaloBg)
        }
    }

    private fun startEyePulsing() {
        val eyeView = findViewById<View>(R.id.sentry_eye_view) ?: return
        val level = intent.getIntExtra(EXTRA_DETERRENT_LEVEL, LEVEL_ALARM)

        val targetScale = if (level == LEVEL_NOTICE) 1.08f else 1.18f
        val durationMs = if (level == LEVEL_NOTICE) 2200L else 1100L

        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, targetScale, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, targetScale, 1.0f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 1.0f, 0.85f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(eyeView, scaleX, scaleY, alpha).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startAutoDismissTimer() {
        lifecycleScope.launch {
            delay(12_000L) // 12秒自动息屏退出
            if (!isFinishing && !isDestroyed) {
                dismissGracefully()
            }
        }
    }

    private fun dismissGracefully() {
        val root = findViewById<View>(R.id.sentry_deterrent_root) ?: run {
            finish()
            return
        }
        root.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                finish()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
                } else {
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
            }
            .start()
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        super.onDestroy()
    }
}
