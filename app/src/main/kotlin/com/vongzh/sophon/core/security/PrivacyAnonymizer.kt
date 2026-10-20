package com.vongzh.sophon.core.security

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log

/**
 * 车规数据安全与隐私脱敏处理器 (PrivacyAnonymizer)
 *
 * 【国家《汽车数据安全管理若干规定（试行）》合规落地（借鉴小鹏合规标杆）】
 * 1. 针对对外提供、分享、快传至手机的行车记录与抓拍影像进行去标识化处理；
 * 2. 对车身外部前方/后方敏感车牌区域及路人面部区域叠加像素化马赛克 (Mosaic) 遮罩；
 * 3. 画面顶部/角标印刻权威合规水印：《汽车数据安全合规脱敏》 [已去标识化]。
 */
object PrivacyAnonymizer {

    private const val TAG = "Sophon.PrivacyAnonymizer"

    /**
     * 对全景图像或抓拍图片执行车规隐私脱敏处理 (马赛克与合规角标)
     *
     * @param original 原始全景 Bitmap
     * @param anonymizePlates 是否对前方与后方车牌盲区进行马赛克打码
     * @param anonymizePedestrians 是否对路人视区打码
     * @return 脱敏后的新 Bitmap
     */
    fun anonymizeBitmap(
        original: Bitmap,
        anonymizePlates: Boolean = true,
        anonymizePedestrians: Boolean = true
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val width = result.width
        val height = result.height

        // 1. 车牌敏感区域马赛克 (基于车机视角视平线中下部规则：前摄与后摄近景车牌)
        if (anonymizePlates) {
            // 前摄视区 (左上角象限 0..w/2, 0..h/2) 下方车牌热区
            applyMosaic(result, Rect((width * 0.15f).toInt(), (height * 0.32f).toInt(), (width * 0.35f).toInt(), (height * 0.48f).toInt()), blockSize = 24)
            // 后摄视区 (右上角象限 w/2..w, 0..h/2) 下方车牌热区
            applyMosaic(result, Rect((width * 0.65f).toInt(), (height * 0.32f).toInt(), (width * 0.85f).toInt(), (height * 0.48f).toInt()), blockSize = 24)
        }

        // 2. 侧向近距人脸/行人视区马赛克 (左视/右视盲区近车身行人)
        if (anonymizePedestrians) {
            // 左视中侧 (左下角象限 0..w/2, h/2..h)
            applyMosaic(result, Rect((width * 0.05f).toInt(), (height * 0.60f).toInt(), (width * 0.25f).toInt(), (height * 0.80f).toInt()), blockSize = 32)
            // 右视中侧 (右下角象限 w/2..w, h/2..h)
            applyMosaic(result, Rect((width * 0.75f).toInt(), (height * 0.60f).toInt(), (width * 0.95f).toInt(), (height * 0.80f).toInt()), blockSize = 32)
        }

        // 3. 绘制车规合规胶囊水印角标
        drawComplianceBadge(canvas, width, height)

        return result
    }

    /**
     * 针对指定矩形区域执行像素化马赛克
     */
    fun applyMosaic(bitmap: Bitmap, rect: Rect, blockSize: Int = 20) {
        val safeLeft = rect.left.coerceIn(0, bitmap.width - 1)
        val safeTop = rect.top.coerceIn(0, bitmap.height - 1)
        val safeRight = rect.right.coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = rect.bottom.coerceIn(safeTop + 1, bitmap.height)

        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width <= 0 || height <= 0) return

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, safeLeft, safeTop, width, height)

        val bs = blockSize.coerceAtLeast(4)
        for (y in 0 until height step bs) {
            for (x in 0 until width step bs) {
                val blockW = minOf(bs, width - x)
                val blockH = minOf(bs, height - y)

                // 统计块均值颜色
                var totalR = 0
                var totalG = 0
                var totalB = 0
                var count = 0

                for (by in 0 until blockH) {
                    for (bx in 0 until blockW) {
                        val pixel = pixels[(y + by) * width + (x + bx)]
                        totalR += Color.red(pixel)
                        totalG += Color.green(pixel)
                        totalB += Color.blue(pixel)
                        count++
                    }
                }

                if (count > 0) {
                    val avgColor = Color.rgb(totalR / count, totalG / count, totalB / count)
                    for (by in 0 until blockH) {
                        for (bx in 0 until blockW) {
                            pixels[(y + by) * width + (x + bx)] = avgColor
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, width, safeLeft, safeTop, width, height)
    }

    private fun drawComplianceBadge(canvas: Canvas, width: Int, height: Int) {
        val bgPaint = Paint().apply {
            color = 0xCC1E293B.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val strokePaint = Paint().apply {
            color = 0xFF00E5FF.toInt() // Cyan Accent
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = 0xFFE2E8F0.toInt()
            textSize = 22f
            isAntiAlias = true
            setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
        }

        val text = "《汽车数据安全合规脱敏》 [已去标识化 · 车牌与人脸保护]"
        val textWidth = textPaint.measureText(text)
        val pillRect = RectF(
            width - textWidth - 48f,
            24f,
            width - 16f,
            72f
        )

        canvas.drawRoundRect(pillRect, 24f, 24f, bgPaint)
        canvas.drawRoundRect(pillRect, 24f, 24f, strokePaint)
        canvas.drawText(text, pillRect.left + 16f, pillRect.top + 31f, textPaint)
    }
}
