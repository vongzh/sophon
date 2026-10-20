package com.vongzh.sophon.ui.widget

import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.Switch

/**
 * 极视座舱 UI 交互与物理动效辅助工具
 */
object UiMotionUtils {

    /**
     * 车规级刷新按钮动效：弹性物理按压 (0.90x -> 1.0x Overshoot) + 360 度顺滑阻尼旋转 (600ms)
     */
    fun startRefreshAnimation(
        button: View,
        icon: ImageView?,
        onAction: () -> Unit
    ) {
        // 1. 物理按压触感 (缩放至 0.90x 后弹性回弹)
        button.animate().cancel()
        button.scaleX = 0.90f
        button.scaleY = 0.90f
        button.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()

        // 2. 刷新图标 360 度顺滑旋转动效 (600ms 阻尼减速)
        icon?.let { iv ->
            iv.animate().cancel()
            iv.rotation = 0f
            iv.animate()
                .rotation(360f)
                .setDuration(600)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { iv.rotation = 0f }
                .start()
        }

        // 3. 执行真实刷新逻辑
        onAction()
    }

    /**
     * 为车载触摸按钮或卡片附加触感反馈微动效：
     * 按下时微缩 (0.95x)，抬起或移出时带 2.0f 阻尼过冲回弹，不拦截真实 Click 事件。
     */
    fun attachPressFeedback(view: View, scale: Float = 0.95f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(scale).scaleY(scale).setDuration(100).start()
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

    /**
     * 车机大屏行触摸联动辅助：点击整行切换 Switch，并注入波纹触控水波纹
     */
    fun bindSwitchRow(switch: Switch) {
        val parent = switch.parent as? ViewGroup ?: return
        parent.isClickable = true
        parent.isFocusable = true
        val outValue = TypedValue()
        if (parent.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            parent.setBackgroundResource(outValue.resourceId)
        }
        parent.setOnClickListener {
            switch.toggle()
        }
    }
}
