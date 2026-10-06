package com.songci.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

/**
 * 高亮层：画「正确气泡」的圆角描边框 + 左侧小三角箭头 + 顶部状态条。
 *
 * 只负责画，不判断对错（判断在 [Matcher]）。
 *
 * ### 为什么不用「全屏窗口」
 *
 * 早期实现是一个 `MATCH_PARENT` 的全屏悬浮窗，虽然带 `FLAG_NOT_TOUCHABLE`，
 * 但实测在**华为 HarmonyOS** 上会导致下层游戏收不到点击（华为开发者论坛有
 * 同类记录，属 OEM 对"触摸遮挡"的实现差异）。
 *
 * 所以改为**按内容定尺寸的窄窗口**：窗口只覆盖"要高亮的那一小块 + 状态条"，
 * 并且**没有内容时窗口零尺寸**。这样即使在会误判遮挡的 ROM 上，也几乎不可能
 * 挡住游戏按钮 —— 从根源上消除这个风险，而不是依赖 `FLAG_NOT_TOUCHABLE` 被正确实现。
 *
 * 因为窗口不再是全屏，本 view 的绘制坐标**以窗口左上角为原点**，
 * 所以需要一个「内容矩形 → 窗口坐标」的偏移（见 [contentOriginX] / [contentOriginY]）。
 */
class OverlayView(context: Context) : View(context) {

    /**
     * 窗口布局：窗口在屏幕上的像素位置与尺寸，以及绘制原点偏移。
     *
     * - 窗口尺寸 = 内容包围盒（高亮框 / 状态条 / 调试面板）向外扩 [PADDING_PX]
     * - [contentOriginX]/[contentOriginY] = 内容坐标系的原点在窗口内的位置
     *   （内容坐标系用的是**全屏**尺寸，与 [HighlightRect] 的归一化基准一致）
     */
    data class WindowLayout(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val contentOriginX: Int,
        val contentOriginY: Int,
        val screenW: Int,
        val screenH: Int,
    ) {
        /** 窗口是否真的需要显示（零尺寸 = 不需要） */
        val visible: Boolean get() = width > 0 && height > 0

        companion object {
            val HIDDEN = WindowLayout(0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

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

    /** 窗口内绘制原点：内容坐标(0,0) 在窗口中的位置 */
    private var originX = 0
    private var originY = 0

    /** 内容坐标系（全屏）的尺寸 */
    private var contentW = 0
    private var contentH = 0

    /**
     * 应用新的窗口布局。由 [OverlayService] 在 `updateViewLayout` 之后调用，
     * 使本 view 的绘制与窗口位置一致。
     */
    fun applyLayout(layout: WindowLayout) {
        originX = layout.contentOriginX
        originY = layout.contentOriginY
        contentW = layout.screenW
        contentH = layout.screenH
        invalidate()
    }

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
        // 内容坐标系 → 窗口坐标系
        canvas.save()
        canvas.translate(originX.toFloat(), originY.toFloat())

        highlight?.let { drawHighlight(canvas, it) }
        if (showStatusBar) {
            statusText?.takeIf { it.isNotBlank() }?.let { drawStatus(canvas, it) }
        }
        if (debugMode && debugLines.isNotEmpty()) {
            drawDebug(canvas, debugLines)
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------
    // 内容包围盒计算：供 OverlayService 决定窗口的位置与尺寸
    // ------------------------------------------------------------------

    /**
     * 当前内容在**屏幕坐标**下的包围盒（像素，左上/右下）。
     * 返回 null 表示没有任何内容需要显示 → 窗口应当收成零尺寸。
     */
    fun contentBoundsPx(): RectF? {
        if (paused) return null
        var box: RectF? = null

        highlight?.let { box = union(box, highlightRectPx(it)) }

        if (showStatusBar) {
            statusText?.takeIf { it.isNotBlank() }?.let { box = union(box, statusRectPx(it)) }
        }
        if (debugMode && debugLines.isNotEmpty()) {
            box = union(box, debugRectPx(debugLines))
        }
        return box
    }

    private fun union(a: RectF?, b: RectF): RectF =
        if (a == null) RectF(b) else RectF(
            minOf(a.left, b.left), minOf(a.top, b.top),
            maxOf(a.right, b.right), maxOf(a.bottom, b.bottom),
        )

    /** 高亮框（含箭头）在屏幕坐标下的矩形 */
    private fun highlightRectPx(target: HighlightRect): RectF {
        val pad = dp(Config.HIGHLIGHT_PADDING_DP)
        val box = RectF(
            target.left * contentW - pad,
            target.top * contentH - pad,
            target.right * contentW + pad,
            target.bottom * contentH + pad,
        )
        // 箭头在框左侧，最远伸出 18dp
        box.left = minOf(box.left, (box.left - dp(ARROW_REACH_DP)).coerceAtLeast(0f))
        return box
    }

    /** 状态条在屏幕坐标下的矩形 */
    private fun statusRectPx(text: String): RectF {
        val padH = dp(12f)
        val padV = dp(7f)
        val textWidth = statusTextPaint.measureText(text)
        val fm = statusTextPaint.fontMetrics
        val boxW = textWidth + padH * 2
        val boxH = (fm.bottom - fm.top) + padV * 2
        val left = (contentW - boxW) / 2f
        val top = dp(STATUS_TOP_DP)
        return RectF(left, top, left + boxW, top + boxH)
    }

    /** 调试面板在屏幕坐标下的矩形 */
    private fun debugRectPx(lines: List<String>): RectF {
        val padH = dp(10f)
        val padV = dp(8f)
        val lineH = dp(18f)
        val boxH = lineH * lines.size + padV * 2
        val boxW = (lines.maxOfOrNull { debugTextPaint.measureText(it) } ?: 0f) + padH * 2
        val safeW = minOf(boxW, contentW.toFloat())
        val top = contentH * DEBUG_TOP_RATIO
        return RectF(dp(8f), top, dp(8f) + safeW, top + boxH)
    }

    // ------------------------------------------------------------------
    // 绘制（坐标都已 translate 到内容坐标系）
    // ------------------------------------------------------------------

    private fun drawDebug(canvas: Canvas, lines: List<String>) {
        val padH = dp(10f)
        val padV = dp(8f)
        val lineH = dp(18f)
        val fm = debugTextPaint.fontMetrics
        val boxH = lineH * lines.size + padV * 2
        val boxW = (lines.maxOfOrNull { debugTextPaint.measureText(it) } ?: 0f) + padH * 2
        val safeW = minOf(boxW, contentW.toFloat())
        val top = contentH * DEBUG_TOP_RATIO

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
            target.left * contentW - pad,
            target.top * contentH - pad,
            target.right * contentW + pad,
            target.bottom * contentH + pad,
        )
        val radius = dp(10f)
        canvas.drawRoundRect(rectF, radius, radius, framePaint)

        // 左侧小三角箭头，指向被高亮的气泡
        val cy = rectF.centerY()
        val tipX = (rectF.left - dp(ARROW_REACH_DP)).coerceAtLeast(0f)
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
        val left = (contentW - boxW) / 2f
        val top = dp(STATUS_TOP_DP)
        if (left < 0f || top + boxH > contentH) return

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

        /** 箭头向左伸出多少 dp（用于算窗口左边界） */
        private const val ARROW_REACH_DP = 20f

        /** 状态条距屏幕顶部多少 dp */
        private const val STATUS_TOP_DP = 28f

        /** 调试面板在屏幕高度上的位置比例 */
        private const val DEBUG_TOP_RATIO = 0.45f

        /**
         * 由内容包围盒算出窗口布局。
         *
         * 窗口 = 包围盒向外扩 [PADDING_PX]，并夹在屏幕范围内；内容为空则返回 [WindowLayout.HIDDEN]。
         */
        fun layoutFor(
            bounds: RectF?,
            screenW: Int,
            screenH: Int,
            paddingPx: Int,
        ): WindowLayout {
            if (bounds == null || screenW <= 0 || screenH <= 0) {
                return WindowLayout.HIDDEN.copy(screenW = screenW, screenH = screenH)
            }
            val left = (bounds.left.roundToInt() - paddingPx).coerceIn(0, screenW - 1)
            val top = (bounds.top.roundToInt() - paddingPx).coerceIn(0, screenH - 1)
            val right = (bounds.right.roundToInt() + paddingPx).coerceIn(left + 1, screenW)
            val bottom = (bounds.bottom.roundToInt() + paddingPx).coerceIn(top + 1, screenH)
            return WindowLayout(
                x = left,
                y = top,
                width = right - left,
                height = bottom - top,
                contentOriginX = -left,
                contentOriginY = -top,
                screenW = screenW,
                screenH = screenH,
            )
        }
    }
}
