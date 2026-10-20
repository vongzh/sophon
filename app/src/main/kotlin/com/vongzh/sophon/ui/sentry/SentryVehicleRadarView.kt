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
 * 哨兵模式车身全景雷达波方位视图 (SentryVehicleRadarView) · 真实数据驱动版
 *
 * 【四向车身盲区与震动雷达态势】
 * 1. 矢量绘制俯视车身拓扑结构；
 * 2. 真实数据驱动：仅在有事件时按方位动态渲染雷达波，无事件时呈现平稳安全监看态；
 * 3. 区分碰撞（红色 #D92D20）、人员靠近（橙黄 #B2670A）与车身震动（Flyme蓝 #3572F4）。
 */
class SentryVehicleRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class RadarDirection {
        NONE,
        FRONT_LEFT,
        FRONT_RIGHT,
        REAR_LEFT,
        REAR_RIGHT,
        ALL_AROUND
    }

    private var activeDirection: RadarDirection = RadarDirection.NONE
    private var activeWaveColor: Int = ContextCompat.getColor(context, R.color.sp_dg)

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

    private var colorDanger = ContextCompat.getColor(context, R.color.sp_dg)
    private var colorWarning = ContextCompat.getColor(context, R.color.sp_wn)
    private var colorAccent = ContextCompat.getColor(context, R.color.sp_ac)
    private var colorOk = ContextCompat.getColor(context, R.color.sp_ok)

    private val carBounds = RectF()
    private val frontGlass = RectF()
    private val rearGlass = RectF()

    /**
     * 响应车机白昼/黑夜模式动态切换，重新绑定 Paint 语义色
     */
    fun refreshThemeColors() {
        bodyPaint.color = ContextCompat.getColor(context, R.color.sp_surf3)
        bodyStrokePaint.color = ContextCompat.getColor(context, R.color.sp_line2)
        glassPaint.color = ContextCompat.getColor(context, R.color.sp_surf)
        glassStrokePaint.color = ContextCompat.getColor(context, R.color.sp_line2)

        colorDanger = ContextCompat.getColor(context, R.color.sp_dg)
        colorWarning = ContextCompat.getColor(context, R.color.sp_wn)
        colorAccent = ContextCompat.getColor(context, R.color.sp_ac)
        colorOk = ContextCompat.getColor(context, R.color.sp_ok)
        invalidate()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        refreshThemeColors()
    }

    private var waveAnimator: android.animation.ValueAnimator? = null
    private var waveProgress = 0f

    private var perimeterScanAnimator: android.animation.ValueAnimator? = null
    private var perimeterProgress = 0f
    private val perimeterPath = android.graphics.Path()
    private val pathMeasure = android.graphics.PathMeasure()
    private val posTan = FloatArray(2)

    private fun startWaveAnimation() {
        stopPerimeterScanAnimation()
        if (waveAnimator?.isRunning == true) return
        waveAnimator?.cancel()
        waveAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                waveProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopWaveAnimation() {
        waveAnimator?.cancel()
        waveAnimator = null
        waveProgress = 0f
        invalidate()
    }

    private fun startPerimeterScanAnimation() {
        if (perimeterScanAnimator?.isRunning == true) return
        perimeterScanAnimator?.cancel()
        perimeterScanAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                perimeterProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPerimeterScanAnimation() {
        perimeterScanAnimator?.cancel()
        perimeterScanAnimator = null
        perimeterProgress = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (activeDirection != RadarDirection.NONE) {
            startWaveAnimation()
        } else {
            startPerimeterScanAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        stopWaveAnimation()
        stopPerimeterScanAnimation()
        super.onDetachedFromWindow()
    }

    /**
     * 响应式高亮指定方位的雷达波（完全由外部事件驱动，绝不绘制假波纹）
     */
    fun highlightDirection(direction: RadarDirection, color: Int = colorDanger) {
        this.activeDirection = direction
        this.activeWaveColor = color
        if (direction != RadarDirection.NONE) {
            startWaveAnimation()
        } else {
            stopWaveAnimation()
            startPerimeterScanAnimation()
        }
    }

    fun resetToSafeState() {
        this.activeDirection = RadarDirection.NONE
        this.activeWaveColor = colorOk
        stopWaveAnimation()
        startPerimeterScanAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerX = w / 2f
        val centerY = h / 2f

        // 1. 绘制车身剪影 (车长约占视图高度 72%，车宽约占 42%)
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

        // 顶棚车标 / 雷达中枢指示 (安全状态下为绿色/蓝色常驻呼吸)
        val centerIndicatorColor = if (activeDirection == RadarDirection.NONE) colorOk else activeWaveColor
        radarFillPaint.color = centerIndicatorColor
        radarFillPaint.alpha = 40
        canvas.drawCircle(centerX, centerY + carH * 0.18f, 18f, radarFillPaint)
        radarFillPaint.alpha = 255
        canvas.drawCircle(centerX, centerY + carH * 0.18f, 6f, radarFillPaint)

        // 2. 根据真实事件动态绘制雷达波，无事件时不绘制假波纹
        when (activeDirection) {
            RadarDirection.FRONT_LEFT -> {
                drawRadarWave(canvas, carLeft - 18f, carTop + carH * 0.18f, activeWaveColor)
            }
            RadarDirection.FRONT_RIGHT -> {
                drawRadarWave(canvas, carRight + 18f, carTop + carH * 0.18f, activeWaveColor)
            }
            RadarDirection.REAR_LEFT -> {
                drawRadarWave(canvas, carLeft - 16f, carBottom - 24f, activeWaveColor)
            }
            RadarDirection.REAR_RIGHT -> {
                drawRadarWave(canvas, carRight + 16f, carBottom - 24f, activeWaveColor)
            }
            RadarDirection.ALL_AROUND -> {
                drawRadarWave(canvas, carLeft - 18f, carTop + carH * 0.18f, activeWaveColor)
                drawRadarWave(canvas, carRight + 18f, centerY, activeWaveColor)
                drawRadarWave(canvas, centerX, carBottom + 16f, activeWaveColor)
            }
            RadarDirection.NONE -> {
                // 安全待命态：车身周围极淡安全虚线 + 360° 动态巡检激光光珠环游扫动 (Perimeter Laser Sweep)
                radarPaint.color = colorOk
                radarPaint.alpha = 50
                perimeterPath.reset()
                perimeterPath.addRoundRect(
                    carLeft - 12f, carTop - 12f, carRight + 12f, carBottom + 12f,
                    cornerRadius + 8f, cornerRadius + 8f, android.graphics.Path.Direction.CW
                )
                canvas.drawPath(perimeterPath, radarPaint)

                // 沿边界环绕飞行的安全扫描光珠
                pathMeasure.setPath(perimeterPath, true)
                val len = pathMeasure.length
                if (len > 0f) {
                    pathMeasure.getPosTan(len * perimeterProgress, posTan, null)
                    val scanX = posTan[0]
                    val scanY = posTan[1]

                    // 外层微光晕
                    dotPaint.color = colorOk
                    dotPaint.alpha = 70
                    canvas.drawCircle(scanX, scanY, 9f, dotPaint)
                    // 核心光珠
                    dotPaint.alpha = 240
                    canvas.drawCircle(scanX, scanY, 4f, dotPaint)
                }
            }
        }
    }

    private fun drawRadarWave(canvas: Canvas, x: Float, y: Float, color: Int) {
        val density = resources.displayMetrics.density

        // 第一层主扩散波
        val r1 = (22f + waveProgress * 42f) * density
        val alpha1 = ((1f - waveProgress) * 200).toInt().coerceIn(0, 255)
        radarPaint.color = color
        radarPaint.alpha = alpha1
        canvas.drawCircle(x, y, r1, radarPaint)

        // 第二层跟随扩散波 (相位偏置 0.5)
        val p2 = (waveProgress + 0.5f) % 1.0f
        val r2 = (22f + p2 * 42f) * density
        val alpha2 = ((1f - p2) * 130).toInt().coerceIn(0, 255)
        radarPaint.alpha = alpha2
        canvas.drawCircle(x, y, r2, radarPaint)

        // 核心原点脉冲光晕
        val coreHalo = (14f + (1f - waveProgress) * 6f) * density
        dotPaint.color = color
        dotPaint.alpha = 50
        canvas.drawCircle(x, y, coreHalo, dotPaint)

        // 实心小圆点
        dotPaint.alpha = 255
        canvas.drawCircle(x, y, 6f * density, dotPaint)
    }
}
