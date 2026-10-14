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
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
    }

    private fun initViews() {
        findViewById<View>(R.id.sentry_deterrent_root).setOnClickListener {
            finish()
        }
    }

    private fun startEyePulsing() {
        val eyeContainer = findViewById<View>(R.id.fl_sentry_eye_container) ?: return
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.18f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.18f, 1.0f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 1.0f, 0.85f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(eyeContainer, scaleX, scaleY, alpha).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun startAutoDismissTimer() {
        lifecycleScope.launch {
            delay(12_000L) // 12秒自动息屏退出
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        super.onDestroy()
    }
}
