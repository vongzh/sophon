package com.vongzh.sophon.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vongzh.sophon.R
import com.vongzh.sophon.domain.model.StorageUsageBreakdown

/**
 * 车载多色水平分段存储容量条 (MultiSegmentStorageBarView)
 * 平滑圆角分段渲染：
 * 其他 (紫色) | 行车 (蓝色) | 图库 (绿色) | 哨兵 (橙色) | 剩余可用 (灰色槽底)
 */
class MultiSegmentStorageBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private val barRect = RectF()
    private val segmentRect = RectF()

    private val paintFree = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ring_free)
    }

    private val paintOther = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ring_other)
    }

    private val paintDriving = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ring_driving)
    }

    private val paintPhotos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ring_photos)
    }

    private val paintSentry = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ring_sentry)
    }

    private var breakdown: StorageUsageBreakdown = StorageUsageBreakdown()

    fun updateBreakdown(data: StorageUsageBreakdown) {
        this.breakdown = data
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val radius = h / 2f
        clipPath.reset()
        clipPath.addRoundRect(barRect, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        // 底色 (全部涂上 free 槽底)
        canvas.drawRect(barRect, paintFree)

        val total = breakdown.totalBytes
        if (total > 0L) {
            var currentX = 0f

            fun drawSegment(bytes: Long, paint: Paint) {
                if (bytes <= 0L) return
                val segmentWidth = (bytes.toFloat() / total) * w
                if (segmentWidth > 0f) {
                    segmentRect.set(currentX, 0f, (currentX + segmentWidth).coerceAtMost(w), h)
                    canvas.drawRect(segmentRect, paint)
                    currentX += segmentWidth
                }
            }

            drawSegment(breakdown.otherBytes, paintOther)
            drawSegment(breakdown.drivingBytes, paintDriving)
            drawSegment(breakdown.photoBytes, paintPhotos)
            drawSegment(breakdown.sentryBytes, paintSentry)
        }

        canvas.restoreToCount(saveCount)
    }
}
