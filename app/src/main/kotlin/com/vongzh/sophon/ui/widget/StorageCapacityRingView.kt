package com.vongzh.sophon.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vongzh.sophon.R
import com.vongzh.sophon.domain.model.StorageUsageBreakdown

/**
 * 四色车载存储容量环形图 (StorageCapacityRingView)
 *
 * 视觉规格参照 FlymeAuto 座舱风格：
 * 翡翠青：行车视频 (Driving)
 * 暖琥珀：哨兵与事件 (Sentry)
 * 电光蓝：精彩抓拍 (Photos)
 * 深夜灰：剩余可用空间 (Free)
 */
class StorageCapacityRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var strokeWidthPx = 12f
    private val rectF = RectF()

    private val paintFree = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.ring_free)
    }

    private val paintOther = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ring_other)
    }

    private val paintDriving = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ring_driving)
    }

    private val paintSentry = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ring_sentry)
    }

    private val paintPhotos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ring_photos)
    }

    private var breakdown: StorageUsageBreakdown = StorageUsageBreakdown()
    private var animProgress = 1f
    private var ringAnimator: android.animation.ValueAnimator? = null

    fun updateBreakdown(data: StorageUsageBreakdown, animate: Boolean = true) {
        this.breakdown = data
        if (animate && isAttachedToWindow) {
            ringAnimator?.cancel()
            ringAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 850L
                interpolator = android.view.animation.DecelerateInterpolator(1.8f)
                addUpdateListener {
                    animProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animProgress = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        ringAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * 响应车机白昼/黑夜模式动态切换，重新绑定 Paint 语义色
     */
    fun refreshThemeColors() {
        paintFree.color = ContextCompat.getColor(context, R.color.ring_free)
        paintOther.color = ContextCompat.getColor(context, R.color.ring_other)
        paintDriving.color = ContextCompat.getColor(context, R.color.ring_driving)
        paintSentry.color = ContextCompat.getColor(context, R.color.ring_sentry)
        paintPhotos.color = ContextCompat.getColor(context, R.color.ring_photos)
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        refreshThemeColors()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h)
        strokeWidthPx = (size * 0.16f).coerceIn(6f, 32f)

        paintFree.strokeWidth = strokeWidthPx
        paintOther.strokeWidth = strokeWidthPx
        paintDriving.strokeWidth = strokeWidthPx
        paintSentry.strokeWidth = strokeWidthPx
        paintPhotos.strokeWidth = strokeWidthPx

        val halfStroke = strokeWidthPx / 2f
        val offsetLeft = (w - size) / 2f + halfStroke
        val offsetTop = (h - size) / 2f + halfStroke

        rectF.set(
            offsetLeft,
            offsetTop,
            offsetLeft + size - strokeWidthPx,
            offsetTop + size - strokeWidthPx
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = breakdown.totalBytes
        if (total <= 0L) {
            canvas.drawArc(rectF, 0f, 360f, false, paintFree)
            return
        }

        var startAngle = -90f

        val sweepOther = (breakdown.otherBytes.toFloat() / total) * 360f * animProgress
        val sweepDriving = (breakdown.drivingBytes.toFloat() / total) * 360f * animProgress
        val sweepPhotos = (breakdown.photoBytes.toFloat() / total) * 360f * animProgress
        val sweepSentry = (breakdown.sentryBytes.toFloat() / total) * 360f * animProgress

        // 1. 底环 (可用空间)
        canvas.drawArc(rectF, 0f, 360f, false, paintFree)

        // 2. 其他系统/车载占用
        if (sweepOther > 0f) {
            canvas.drawArc(rectF, startAngle, sweepOther, false, paintOther)
            startAngle += sweepOther
        }

        // 3. 行车记录
        if (sweepDriving > 0f) {
            canvas.drawArc(rectF, startAngle, sweepDriving, false, paintDriving)
            startAngle += sweepDriving
        }

        // 4. 抓拍相册
        if (sweepPhotos > 0f) {
            canvas.drawArc(rectF, startAngle, sweepPhotos, false, paintPhotos)
            startAngle += sweepPhotos
        }

        // 5. 哨兵事件
        if (sweepSentry > 0f) {
            canvas.drawArc(rectF, startAngle, sweepSentry, false, paintSentry)
        }
    }
}
