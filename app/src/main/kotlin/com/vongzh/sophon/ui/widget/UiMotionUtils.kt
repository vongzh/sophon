package com.vongzh.sophon.ui.widget

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView

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
}
