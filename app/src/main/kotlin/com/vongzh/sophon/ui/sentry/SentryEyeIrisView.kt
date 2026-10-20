package com.vongzh.sophon.ui.sentry

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 车规哨兵超感星瞳与全景扫描虹膜视窗 (SentryEyeIrisView)
 *
 * 【极客座舱设计语言 · 拟人化智眸】
 * 参照 Tesla Sentry Eye & FlymeAuto 视觉设计：
 * 1. 同心三轨逆向旋转轨道环 (Concentric Counter-Rotating Orbital Rings)；
 * 2. 动态呼吸虹膜核心 (Breathing Iris Core)，呈现自主生命体质感；
 * 3. 360° 实时激光全域扫描光束 (Continuous Luminous Radar Sweep)；
 * 4. 双阶拟人态无缝切换：
 *    - LEVEL_NOTICE: 极光青蓝，缓速律动，宣示全维守护；
 *    - LEVEL_ALARM: 烈焰警示红，疾速扫描，高频威慑冲击。
 * 5. 严格恪守车规 onDraw 零堆分配与生命周期无泄漏回收。
 */
class SentryEyeIrisView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class IrisMode {
        NOTICE,  // 一级轻度守护：青蓝温和星瞳
        ALARM    // 二级高危警戒：绯红烈焰警示眼
    }

    private var mode: IrisMode = IrisMode.ALARM

    private var rotationAngle = 0f
    private var breathPhase = 0f

    private var irisAnimator: ValueAnimator? = null

    // 预分配复用绘图对象 (零 onDraw 堆内存开销)
    private val outerRingRect = RectF()
    private val middleRingRect = RectF()
    private val sweepMatrix = Matrix()

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val dashedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val coreHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val coreIrisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0B0F17.toInt() // 深空纯黑瞳孔
    }

    private var themeColor = 0xFFFF453A.toInt()
    private var haloColor = 0x33FF453A.toInt()

    private val sweepPositions = floatArrayOf(0f, 0.5f, 0.75f, 0.92f, 1.0f)
    private var sweepColors = intArrayOf()
    private var sweepShader: SweepGradient? = null

    init {
        updateColorPalette()
    }

    fun setMode(newMode: IrisMode) {
        if (this.mode == newMode) return
        this.mode = newMode
        updateColorPalette()
        updateSweepShader(width / 2f, height / 2f)
        restartAnimator()
        invalidate()
    }

    private fun updateColorPalette() {
        if (mode == IrisMode.NOTICE) {
            themeColor = 0xFF3572F4.toInt() // Sophon Flyme Blue
            haloColor = 0x333572F4.toInt()
        } else {
            themeColor = 0xFFFF453A.toInt() // Hazard Red
            haloColor = 0x3DFF453A.toInt()
        }
        outerRingPaint.color = themeColor
        dashedRingPaint.color = themeColor
        dashedRingPaint.alpha = 140
    }

    private fun updateSweepShader(centerX: Float, centerY: Float) {
        if (centerX <= 0f || centerY <= 0f) return
        sweepColors = intArrayOf(
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            themeColor and 0x00FFFFFF or 0x4D000000.toInt(),
            themeColor and 0x00FFFFFF or 0xB3000000.toInt()
        )
        sweepShader = SweepGradient(centerX, centerY, sweepColors, sweepPositions)
        sweepPaint.shader = sweepShader
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    private fun startAnimator() {
        if (irisAnimator?.isRunning == true) return
        val animDuration = if (mode == IrisMode.NOTICE) 4000L else 1800L
        irisAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = animDuration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                // 呼吸相位为双倍正弦映射 (0..1..0)
                val rad = Math.toRadians((rotationAngle * 2.0) % 360.0)
                breathPhase = ((Math.sin(rad) + 1.0) / 2.0).toFloat()
                invalidate()
            }
            start()
        }
    }

    private fun restartAnimator() {
        stopAnimator()
        startAnimator()
    }

    private fun stopAnimator() {
        irisAnimator?.cancel()
        irisAnimator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        outerRingPaint.strokeWidth = 2.5f * density
        dashedRingPaint.strokeWidth = 1.8f * density
        dashedRingPaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        updateSweepShader(w / 2f, h / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val centerY = h / 2f
        val baseRadius = minOf(centerX, centerY) * 0.88f

        // 1. 外部动态呼吸光晕 (Radial Glow)
        val haloRadius = baseRadius * (0.85f + breathPhase * 0.15f)
        coreHaloPaint.color = haloColor
        canvas.drawCircle(centerX, centerY, haloRadius, coreHaloPaint)

        // 2. 第一轨道：三段顺时针旋转刻度环 (Outer Segmented Orbital Ring)
        val outerRadius = baseRadius * 0.95f
        outerRingRect.set(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius)
        outerRingPaint.alpha = (160 + (breathPhase * 95)).toInt().coerceIn(0, 255)

        val saveCount1 = canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)
        canvas.drawArc(outerRingRect, 0f, 95f, false, outerRingPaint)
        canvas.drawArc(outerRingRect, 120f, 95f, false, outerRingPaint)
        canvas.drawArc(outerRingRect, 240f, 95f, false, outerRingPaint)
        canvas.restoreToCount(saveCount1)

        // 3. 第二轨道：逆时针旋转虚线雷达环 (Counter-Rotating Dashed Radar Ring)
        val middleRadius = baseRadius * 0.76f
        middleRingRect.set(centerX - middleRadius, centerY - middleRadius, centerX + middleRadius, centerY + middleRadius)

        val saveCount2 = canvas.save()
        canvas.rotate(-rotationAngle * 1.5f, centerX, centerY)
        canvas.drawArc(middleRingRect, 0f, 360f, false, dashedRingPaint)
        canvas.restoreToCount(saveCount2)

        // 4. 激光雷达扫描扇区光束 (Luminous Radar Sweep Beam · 零 onDraw 堆分配)
        val sweepRadius = baseRadius * 0.90f
        sweepShader?.let { shader ->
            sweepMatrix.setRotate(rotationAngle * 1.2f, centerX, centerY)
            shader.setLocalMatrix(sweepMatrix)
            canvas.drawCircle(centerX, centerY, sweepRadius, sweepPaint)
        }

        // 5. 核心虹膜瞳孔球 (Central Luminous Iris & Pupil)
        val irisRadius = baseRadius * (0.34f + breathPhase * 0.08f)
        coreIrisPaint.color = themeColor
        coreIrisPaint.alpha = 230
        canvas.drawCircle(centerX, centerY, irisRadius, coreIrisPaint)

        // 瞳孔同心纯黑核心
        val pupilRadius = irisRadius * 0.42f
        canvas.drawCircle(centerX, centerY, pupilRadius, pupilPaint)

        // 核心高光反光点
        pupilPaint.color = Color.WHITE
        pupilPaint.alpha = 200
        canvas.drawCircle(centerX - pupilRadius * 0.35f, centerY - pupilRadius * 0.35f, pupilRadius * 0.22f, pupilPaint)
        pupilPaint.color = 0xFF0B0F17.toInt()
        pupilPaint.alpha = 255
    }
}
