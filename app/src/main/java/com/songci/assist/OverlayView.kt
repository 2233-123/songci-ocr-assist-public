package com.songci.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 高亮层：画「正确气泡」的圆角描边框 + 左侧小三角箭头 + 顶部状态条。
 *
 * 只负责画，不判断对错（判断在 [Matcher]）。坐标全部是相对本 view 的比例（0..1），
 * 因此分辨率/旋转变化时不需要缓存任何像素坐标。
 */
class OverlayView(context: Context) : View(context) {

    /** 高亮目标（归一化）。null = 不画框。 */
    var highlight: HighlightRect? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 顶部状态条文案（"首句 → 词牌 ✓0.98" / "应选：西江月"）。null = 不显示。 */
    var statusText: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 偏好里可以关掉状态条（只留高亮框）。 */
    var showStatusBar: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 暂停时只留浮层，不画任何提示。 */
    var paused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 调试面板开关（排查「为什么没出框」用）。 */
    var debugMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 调试面板每帧的文本（帧数 / 顶部 OCR / 匹配结果）。 */
    var debugLines: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun clear() {
        highlight = null
        statusText = null
    }

    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(Config.HIGHLIGHT_STROKE_DP)
        color = HIGHLIGHT_COLOR
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = HIGHLIGHT_COLOR
    }

    private val statusBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = STATUS_BG_COLOR
    }

    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
    }

    private val debugBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DEBUG_BG_COLOR
    }

    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val arrowPath = Path()
    private val rectF = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paused) return

        highlight?.let { drawHighlight(canvas, it) }
        if (showStatusBar) {
            statusText?.takeIf { it.isNotBlank() }?.let { drawStatus(canvas, it) }
        }
        if (debugMode && debugLines.isNotEmpty()) {
            drawDebug(canvas, debugLines)
        }
    }

    /** 左上角调试面板：帧数 / 顶部 OCR / 匹配结果。 */
    private fun drawDebug(canvas: Canvas, lines: List<String>) {
        val padH = dp(10f)
        val padV = dp(8f)
        val lineH = dp(18f)
        val fm = debugTextPaint.fontMetrics
        val boxH = lineH * lines.size + padV * 2
        val boxW = (lines.maxOfOrNull { debugTextPaint.measureText(it) } ?: 0f) + padH * 2
        val safeW = minOf(boxW, width.toFloat())
        val top = height * 0.45f

        rectF.set(dp(8f), top, dp(8f) + safeW, top + boxH)
        canvas.drawRoundRect(rectF, dp(8f), dp(8f), debugBgPaint)

        var y = top + padV - fm.top
        for (line in lines) {
            canvas.drawText(line, dp(8f) + padH, y, debugTextPaint)
            y += lineH
        }
    }

    private fun drawHighlight(canvas: Canvas, target: HighlightRect) {
        val pad = dp(Config.HIGHLIGHT_PADDING_DP)
        rectF.set(
            target.left * width - pad,
            target.top * height - pad,
            target.right * width + pad,
            target.bottom * height + pad,
        )
        val radius = dp(10f)
        canvas.drawRoundRect(rectF, radius, radius, framePaint)

        // 左侧小三角箭头，指向被高亮的气泡
        val cy = rectF.centerY()
        val tipX = (rectF.left - dp(18f)).coerceAtLeast(0f)
        arrowPath.reset()
        arrowPath.moveTo(tipX, cy)
        arrowPath.lineTo(rectF.left, cy - dp(10f))
        arrowPath.lineTo(rectF.left, cy + dp(10f))
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    private fun drawStatus(canvas: Canvas, text: String) {
        val padH = dp(12f)
        val padV = dp(7f)
        val textWidth = statusTextPaint.measureText(text)
        val fm = statusTextPaint.fontMetrics
        val boxW = textWidth + padH * 2
        val boxH = (fm.bottom - fm.top) + padV * 2
        val left = (width - boxW) / 2f
        val top = dp(28f)
        if (left < 0f || top + boxH > height) return

        rectF.set(left, top, left + boxW, top + boxH)
        val radius = dp(12f)
        canvas.drawRoundRect(rectF, radius, radius, statusBgPaint)
        canvas.drawText(text, left + padH, top + padV - fm.top, statusTextPaint)
    }

    companion object {
        /** 亮青绿：与游戏 UI 区分度高 */
        private const val HIGHLIGHT_COLOR = 0xFF00E5A0.toInt()
        private const val STATUS_BG_COLOR = 0xCC000000.toInt()

        /** 调试面板底色（比状态条更实，保证小字可读） */
        private const val DEBUG_BG_COLOR = 0xE6101820.toInt()
    }
}
