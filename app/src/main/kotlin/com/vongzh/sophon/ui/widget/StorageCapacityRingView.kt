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

    fun updateBreakdown(data: StorageUsageBreakdown) {
        this.breakdown = data
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = minOf(w, h)
        strokeWidthPx = (size * 0.16f).coerceIn(6f, 32f)

        paintFree.strokeWidth = strokeWidthPx
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

        val sweepDriving = (breakdown.drivingBytes.toFloat() / total) * 360f
        val sweepSentry = (breakdown.sentryBytes.toFloat() / total) * 360f
        val sweepPhotos = (breakdown.photoBytes.toFloat() / total) * 360f

        // 1. 底环 (可用空间)
        canvas.drawArc(rectF, 0f, 360f, false, paintFree)

        // 2. 行车记录
        if (sweepDriving > 0f) {
            canvas.drawArc(rectF, startAngle, sweepDriving, false, paintDriving)
            startAngle += sweepDriving
        }

        // 3. 哨兵事件
        if (sweepSentry > 0f) {
            canvas.drawArc(rectF, startAngle, sweepSentry, false, paintSentry)
            startAngle += sweepSentry
        }

        // 4. 抓拍相册
        if (sweepPhotos > 0f) {
            canvas.drawArc(rectF, startAngle, sweepPhotos, false, paintPhotos)
        }
    }
}
