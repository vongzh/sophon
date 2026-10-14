package com.vongzh.sophon.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat
import com.vongzh.sophon.R

/**
 * 带有事件标线与高能标记点的时间轴进度条 (MarkedSeekBar)
 *
 * 参照 FlymeAuto & 特斯拉车载哨兵时间轴设计：
 * 1. 支持在连续视频轨道的指定时间点绘制红色（紧急碰撞）或橙色（哨兵侦测）垂直标记点与顶部高能圆点；
 * 2. 支持查询上一/下一高能标记时间戳，实现毫秒级精准跳跃定位。
 */
class MarkedSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    data class EventMarker(
        val positionRatio: Float,
        val isEmergency: Boolean = false,
        val description: String = ""
    )

    private val markerList = mutableListOf<EventMarker>()

    private val paintEmergency = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sp_dg)
        strokeWidth = 6f
    }

    private val paintWarning = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sp_wn)
        strokeWidth = 6f
    }

    private val dotPaintEmergency = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sp_dg)
        style = Paint.Style.FILL
    }

    private val dotPaintWarning = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sp_wn)
        style = Paint.Style.FILL
    }

    fun setMarkers(markers: List<EventMarker>) {
        markerList.clear()
        markerList.addAll(markers.sortedBy { it.positionRatio })
        invalidate()
    }

    fun hasMarkers(): Boolean = markerList.isNotEmpty()

    /**
     * 获取当前播放进度之前的上一个事件标记点目标 Progress (0..max)
     */
    fun getPreviousMarkerProgress(currentProgress: Int): Int? {
        if (markerList.isEmpty() || max <= 0) return null
        val currentRatio = currentProgress.toFloat() / max.toFloat()
        // 查找比当前 ratio 小且差距大于 1% 的最近前一个 marker
        val prev = markerList.lastOrNull { it.positionRatio < currentRatio - 0.015f }
            ?: markerList.firstOrNull()
        return prev?.let { (it.positionRatio * max).toInt() }
    }

    /**
     * 获取当前播放进度之后的下一个事件标记点目标 Progress (0..max)
     */
    fun getNextMarkerProgress(currentProgress: Int): Int? {
        if (markerList.isEmpty() || max <= 0) return null
        val currentRatio = currentProgress.toFloat() / max.toFloat()
        val next = markerList.firstOrNull { it.positionRatio > currentRatio + 0.015f }
            ?: markerList.lastOrNull()
        return next?.let { (it.positionRatio * max).toInt() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (markerList.isEmpty()) return

        val barWidth = width - paddingLeft - paddingRight
        val topY = paddingTop.toFloat()
        val bottomY = (height - paddingBottom).toFloat()
        val dotRadius = 7f

        for (marker in markerList) {
            val markerX = paddingLeft + (marker.positionRatio * barWidth)
            val linePaint = if (marker.isEmergency) paintEmergency else paintWarning
            val dotPaint = if (marker.isEmergency) dotPaintEmergency else dotPaintWarning

            // 绘制垂直标线
            canvas.drawLine(markerX, topY, markerX, bottomY, linePaint)
            // 绘制顶部高能标记小圆点
            canvas.drawCircle(markerX, topY + dotRadius, dotRadius, dotPaint)
        }
    }
}
