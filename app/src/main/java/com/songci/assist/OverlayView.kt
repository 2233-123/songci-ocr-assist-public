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
 * ### 关于「全屏窗口」的一段弯路（值得记下来）
 *
 * 早期实现是 `MATCH_PARENT` 全屏悬浮窗，带 `FLAG_NOT_TOUCHABLE`，但实测在
 * **华为 HarmonyOS** 上下层游戏收不到点击（OEM 对"触摸遮挡"的实现差异）。
 *
 * 当时的修法是**把窗口缩小成「贴合内容包围盒」的窄窗口，且无内容时零尺寸**。
 * 这确实绕开了遮挡问题，但带来两个后果：
 *
 * 1. **框会从左上角飞过来** —— 悬浮窗的 `params.x/y/width/height` 一变，
 *    `WindowManager` 就播放窗口移动动画。用户明确要求「直接出现在对应位置」。
 * 2. 窗口尺寸在 0 与非 0 之间反复跳变，可能让合成链路走低效路径
 *    （真机 A/B：0×0 时 OCR 中位 217ms / 受理 2.5/s，保持 1×1 时 141ms / 5.9/s）。
 *
 * **正确的修法应该是保留全屏窗口 + 依赖 `FLAG_NOT_TOUCHABLE`**，而当年选择了绕开。
 * 现在已改回**恒定整屏窗口**（触摸穿透靠 flag），根除动画；
 * 详见 `OverlayService.applyOverlayLayout` 的注释。
 */
class OverlayView(context: Context) : View(context) {

    /**
     * 窗口布局：窗口在屏幕上的像素位置与尺寸，以及绘制原点偏移。
     *
     * - [x]/[y]/[width]/[height]：窗口几何。**当前恒为整屏 + (0,0)**，见类注释
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
            /**
             * 无内容时的窗口尺寸：**1×1 像素，而不是 0×0**。
             *
             * ### 为什么不能是 0×0（真机实测）
             *
             * 同一台手机上对比两段日志（面板开 vs 关，各数百帧）：
             *
             * ```
             * 面板开: 帧间隔中位 250ms (3.4 fps)   OCR 中位 185ms
             * 面板关: 帧间隔中位 265ms (2.5 fps)   OCR 中位 217ms
             * ```
             *
             * 面板开着时悬浮窗**持续有内容**（每帧重画四行诊断文字），关闭时窗口
             * 收缩到 0×0。两者 OCR 耗时差 17%、吞吐差 26% —— 方向与用户
             * 「开面板很快、关掉很慢」的观察一致。
             *
             * 推断的机制：尺寸为 0 的窗口会让系统合成链路进入另一种路径，
             * `VirtualDisplay` 可能重复投递同一帧（同样的像素再 OCR 一遍，
             * 所以"帧还是 55~67 次/秒"，但每帧的实际开销变高）。
             *
             * **这是一个待验证的假设**，所以本改动刻意做得最小、可回退：
             * 只把 0×0 换成 1×1，窗口位置与透明性都不变，不影响任何显示效果。
             */
            val IDLE = WindowLayout(0, 0, 1, 1, 0, 0, 0, 0)

            /** 兼容旧名：语义等同于 [IDLE] */
            val HIDDEN = IDLE
        }
    }

    /** 高亮目标（归一化）。null = 不画框。 */
    var highlight: HighlightRect? = null
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

    /**
     * 几何自述（诊断用）。
     *
     * 「窗口恒整屏」改造后出现过"框只画在左上角"，需要在真机上看清这几个量的关系：
     * - `w/h`：**view 实测尺寸**（即窗口实际给了多大画布）
     * - `cW/cH`：**绘制用的内容坐标尺寸**（`applyLayout` 里从 layout.screenW/H 来）
     * - `ox/oy`：画布平移原点
     * - `hl`：高亮目标的归一化矩形
     *
     * 若 `w == cW`（且 ox=oy=0），则内容坐标 == 画布坐标，框应出现在正确位置；
     * 若两者不等，就是尺寸来源不一致 —— 那才是"框跑到左上角"的原因。
     */
    fun geometryDesc(): String {
        val h = highlight
        return "view=%dx%d content=%dx%d origin=(%d,%d) hl=%s".format(
            width, height, contentW, contentH, originX, originY,
            h?.let { "(%.2f,%.2f,%.2f,%.2f)".format(it.left, it.top, it.right, it.bottom) } ?: "null",
        )
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

    companion object {
        /** 亮青绿：与游戏 UI 区分度高 */
        private const val HIGHLIGHT_COLOR = 0xFF00E5A0.toInt()

        /** 调试面板底色（比高亮框更实，保证小字可读） */
        private const val DEBUG_BG_COLOR = 0xE6101820.toInt()

        /** 箭头向左伸出多少 dp（用于算窗口左边界） */
        private const val ARROW_REACH_DP = 20f

        /**
         * 调试面板在屏幕高度上的位置比例。
         *
         * **公开**：`OverlayService` 要把这块区域报给流水线排除掉 ——
         * 否则面板文字可能被当成游戏选项气泡（见 `FramePipeline.selfDrawnBounds`）。
         */
        const val DEBUG_TOP_RATIO = 0.45f

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
