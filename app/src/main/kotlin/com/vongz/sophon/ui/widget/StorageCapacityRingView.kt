package com.vongz.sophon.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vongz.sophon.R
import com.vongz.sophon.domain.model.StorageUsageBreakdown

/**
 * 四色车载存储容量环形图 (StorageCapacityRingView)
 *
 * 视觉规格参照 FlymeAuto 座舱风格：
 * 蓝色：行车视频 (Driving)
 * 橙色：哨兵与事件 (Sentry)
 * 绿色：精彩抓拍 (Photos)
 * 深灰：剩余可用空间 (Free)
 */
class StorageCapacityRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokeWidthPx = 28f
    private val rectF = RectF()

    private val paintFree = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ContextCompat.getColor(context, R.color.ring_free)
    }

    private val paintDriving = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ContextCompat.getColor(context, R.color.ring_driving)
    }

    private val paintSentry = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ContextCompat.getColor(context, R.color.ring_sentry)
    }

    private val paintPhotos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ContextCompat.getColor(context, R.color.ring_photos)
    }

    private var breakdown: StorageUsageBreakdown = StorageUsageBreakdown()

    fun updateBreakdown(data: StorageUsageBreakdown) {
        this.breakdown = data
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfStroke = strokeWidthPx / 2f
        rectF.set(
            paddingLeft + halfStroke,
            paddingTop + halfStroke,
            w - paddingRight - halfStroke,
            h - paddingBottom - halfStroke
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
