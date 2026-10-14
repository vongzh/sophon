package com.vongzh.sophon.ui.sentry

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vongzh.sophon.R

/**
 * 哨兵模式车身全景雷达波方位视图 (SentryVehicleRadarView) · 设计稿 S5 规范
 *
 * 【四向车身盲区与震动雷达态势】
 * 1. 矢量绘制俯视车身拓扑结构；
 * 2. 动态渲染左前、右侧盲区及车尾触发雷达波；
 * 3. 区分碰撞（红色 #D92D20）、人员靠近（橙黄 #B2670A）与车身震动（Flyme蓝 #3572F4）。
 */
class SentryVehicleRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.sp_surf3)
    }

    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = ContextCompat.getColor(context, R.color.sp_line2)
    }

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.sp_surf)
    }

    private val glassStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = ContextCompat.getColor(context, R.color.sp_line2)
    }

    private val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val radarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val colorDanger = ContextCompat.getColor(context, R.color.sp_dg)
    private val colorWarning = ContextCompat.getColor(context, R.color.sp_wn)
    private val colorAccent = ContextCompat.getColor(context, R.color.sp_ac)

    private val carBounds = RectF()
    private val frontGlass = RectF()
    private val rearGlass = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f

        // 1. 绘制车身剪影 (车长约占视图高度 75%，车宽约占 40%)
        val carW = (w * 0.42f).coerceAtMost(110f * resources.displayMetrics.density)
        val carH = (h * 0.72f).coerceAtMost(220f * resources.displayMetrics.density)

        val carLeft = centerX - carW / 2f
        val carTop = centerY - carH / 2f
        val carRight = centerX + carW / 2f
        val carBottom = centerY + carH / 2f

        carBounds.set(carLeft, carTop, carRight, carBottom)
        val cornerRadius = carW * 0.32f
        canvas.drawRoundRect(carBounds, cornerRadius, cornerRadius, bodyPaint)
        canvas.drawRoundRect(carBounds, cornerRadius, cornerRadius, bodyStrokePaint)

        // 前挡风玻璃
        val glassW = carW * 0.72f
        val glassH = carH * 0.22f
        val glassLeft = centerX - glassW / 2f
        frontGlass.set(glassLeft, carTop + carH * 0.16f, glassLeft + glassW, carTop + carH * 0.16f + glassH)
        canvas.drawRoundRect(frontGlass, 10f, 10f, glassPaint)
        canvas.drawRoundRect(frontGlass, 10f, 10f, glassStrokePaint)

        // 后挡风玻璃
        rearGlass.set(glassLeft, carBottom - carH * 0.34f, glassLeft + glassW, carBottom - carH * 0.34f + glassH)
        canvas.drawRoundRect(rearGlass, 10f, 10f, glassPaint)
        canvas.drawRoundRect(rearGlass, 10f, 10f, glassStrokePaint)

        // 顶棚车标 / 雷达中枢指示
        radarFillPaint.color = colorAccent
        radarFillPaint.alpha = 40
        canvas.drawCircle(centerX, centerY + carH * 0.18f, 18f, radarFillPaint)
        radarFillPaint.alpha = 255
        canvas.drawCircle(centerX, centerY + carH * 0.18f, 6f, radarFillPaint)

        // 2. 绘制左前碰撞警报雷达波 (高危红色)
        val shockX = carLeft - 18f
        val shockY = carTop + carH * 0.18f

        radarPaint.color = colorDanger
        radarPaint.alpha = 180
        canvas.drawCircle(shockX, shockY, 38f, radarPaint)
        radarPaint.alpha = 80
        canvas.drawCircle(shockX, shockY, 56f, radarPaint)

        dotPaint.color = colorDanger
        dotPaint.alpha = 50
        canvas.drawCircle(shockX, shockY, 22f, dotPaint)
        dotPaint.alpha = 255
        canvas.drawCircle(shockX, shockY, 8f, dotPaint)

        // 3. 绘制右侧靠近波纹 (橙黄警告色)
        val warnX = carRight + 18f
        val warnY = centerY - 10f

        radarPaint.color = colorWarning
        radarPaint.alpha = 180
        canvas.drawCircle(warnX, warnY, 34f, radarPaint)
        radarPaint.alpha = 70
        canvas.drawCircle(warnX, warnY, 50f, radarPaint)

        dotPaint.color = colorWarning
        dotPaint.alpha = 50
        canvas.drawCircle(warnX, warnY, 18f, dotPaint)
        dotPaint.alpha = 255
        canvas.drawCircle(warnX, warnY, 7f, dotPaint)

        // 4. 绘制后方异常晃动波纹 (Flyme 蓝)
        val vibeX = carLeft - 6f
        val vibeY = carBottom - 24f

        radarPaint.color = colorAccent
        radarPaint.alpha = 160
        canvas.drawCircle(vibeX, vibeY, 30f, radarPaint)

        dotPaint.color = colorAccent
        dotPaint.alpha = 40
        canvas.drawCircle(vibeX, vibeY, 16f, dotPaint)
        dotPaint.alpha = 255
        canvas.drawCircle(vibeX, vibeY, 6f, dotPaint)
    }
}
