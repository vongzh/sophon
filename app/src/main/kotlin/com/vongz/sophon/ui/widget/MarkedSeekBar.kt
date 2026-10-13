package com.vongz.sophon.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.content.ContextCompat
import com.vongz.sophon.R

/**
 * 带有事件标线的时间轴进度条 (MarkedSeekBar)
 *
 * 参照 FlymeAuto & 特斯拉座舱时间轴设计：
 * 支持在连续视频轨道的指定时间点绘制红色（紧急碰撞）或橙色（哨兵侦测）垂直标记点。
 */
class MarkedSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    data class EventMarker(
        val positionRatio: Float,
        val isEmergency: Boolean = false
    )

    private val markerList = mutableListOf<EventMarker>()

    private val paintEmergency = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_danger)
        strokeWidth = 6f
    }

    private val paintWarning = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_warning)
        strokeWidth = 6f
    }

    fun setMarkers(markers: List<EventMarker>) {
        markerList.clear()
        markerList.addAll(markers)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (markerList.isEmpty()) return

        val barWidth = width - paddingLeft - paddingRight
        val topY = paddingTop.toFloat()
        val bottomY = (height - paddingBottom).toFloat()

        for (marker in markerList) {
            val markerX = paddingLeft + (marker.positionRatio * barWidth)
            val paint = if (marker.isEmergency) paintEmergency else paintWarning
            canvas.drawLine(markerX, topY, markerX, bottomY, paint)
        }
    }
}
