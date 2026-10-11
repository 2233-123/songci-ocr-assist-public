package com.songci.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

/**
 * 状态条：屏幕**顶部正中**的一条胶囊形提示（"首句 → 词牌 ✓0.98" / "应选：西江月"）。
 *
 * ### 为什么是独立窗口
 *
 * 原来状态条和高亮框画在同一个窗口里，而那个窗口的位置是两者包围盒的**并集** ——
 * 高亮框一移动，窗口边界就跟着变，状态条在屏幕上也就跟着跑
 * （用户反馈："顶部的框在择律过程中会动来动去"）。
 *
 * 拆成独立窗口后，本窗口宽度 = 整屏、位置恒为 `(0, 0)`，**只由分辨率决定**，
 * 与高亮框的位置完全无关，所以状态条永远钉在同一个地方。
 *
 * 窗口虽然铺满屏幕宽度，但带 `FLAG_NOT_TOUCHABLE`（**可触摸区域为空**），
 * 不会挡住下面的游戏；高度也仅够容纳这条提示。
 */
class StatusBarView(context: Context) : View(context) {

    /** 提示文案。null / 空白 = 不显示（窗口高度会收成 0，等于不存在）。 */
    var text: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 偏好里可以关掉状态条（只留高亮框）。 */
    var barEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * **「暂无匹配」占位态** —— 取帧在跑，但当前屏幕上没有匹配到词句。
     *
     * ### 为什么需要它（用户报告："屏幕上没有匹配到对应词句时，长条框就是会消失"）
     *
     * 原来的写法是这种情况下 `text = null`，而 [onDraw] 的判据是
     * `if (!showing || t.isNullOrBlank()) return` —— **直接一个像素都不画**。
     * 于是"没匹配到"就等于"状态条不见了"，而且和亮框同时（同一个分支里 `view.clear()`）。
     *
     * 但"没匹配到"恰恰是**最需要拖动状态条**的时刻（玩家要先把它挪开再等下一次匹配），
     * 一个看不见的窗口既找不到也没法抓。
     *
     * 所以这里改成：**窗口始终在、位置始终稳**，没匹配时画一个弱化的占位条
     * （暗色 + 灰字），让人看得见、抓得到，同时**明确区别于真实结论**，
     * 不会误导成"匹配到了某句词"。
     */
    var idleNotice: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 暂停时不显示提示。 */
    var paused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BG_COLOR
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
    }

    private val rectF = RectF()

    /** 当前是否真的需要显示（有真实文案） */
    private val showing: Boolean
        get() = barEnabled && !paused && !text.isNullOrBlank()

    /** 当前是否显示弱化占位条（没匹配到词句，但取帧在跑） */
    private val showingIdle: Boolean
        get() = barEnabled && !paused && idleNotice && text.isNullOrBlank()

    /**
     * 窗口需要的高度（像素）。不显示时返回 0 → 窗口收成 0 高，屏幕上不留痕迹。
     *
     * ### ⚠️ 这个高度有一条**硬性上限**：不得压到游戏的首句文字
     *
     * 游戏把首句固定画在 **y ≈ 0.19**（历次真机日志实测：60/60 次都在 0.19）。
     * 而首句是**取帧时一起被 OCR 的** —— 状态条一旦盖住它，OCR 就读不到首句，
     * 结果是「识别不出新句子」，而且用户还会觉得是识别引擎的问题。
     *
     * 真机事故（v0.11.0）：把效果做成**第二行**后，状态条底边压到首句上，
     * 该局 95 帧里 **90 帧「首句未匹配」**，同时顶部的选项按钮也点不动了。
     *
     * **所以：内容只能加在横向上，不能换行。**
     *
     * 另外 [TOP_DP] 已经从 28f 收紧到 8f —— 原值下底边约 215px，而首句在 228px，
     * **只剩 13px 余量**，等于一直在悬崖边；加一行就掉下去了。
     * 现在底边约 165px，净空约 63px。
     */
    /**
     * 窗口需要的高度（像素）。不显示时返回 0 → 窗口收成 0 高，屏幕上不留痕迹。
     *
     * ### ⚠️ 必须与 [onDraw] 画的框**逐像素对齐**（曾经差一倍）
     *
     * 原实现是 `(TOP_DP * 2 + h)`，而 [onDraw] 画的框只有 `top + boxH`（h 是不含
     * `TOP_DP` 的 `PAD_V` 盒子高度）—— 于是**窗口比可见的框高出一倍多**：
     *
     * ```
     * 窗口 89dp：  [ 8dp 空白 ][ 可见框 73dp ][ 8dp 空白 ]
     * ```
     *
     * 后果（用户报告「占据空间和显示不同步、拖动很卡」）：
     * - 在可见框之外的空白上按下也能拖动
     * - 拖动时手指与框之间存在几十像素的固定偏移
     *
     * 现在窗口高度 = **框高**（与 [onDraw] 的 `boxH` 同一个式子），
     * `TOP_DP` 改成窗口的默认 y 位置（见 `OverlayService.applyStatusLayout`）——
     * 窗口内零空白，框与窗口逐像素一致。
     */
    /**
     * **可拖动时窗口需要的最小高度**（像素）。
     *
     * ### 为什么必须有它（用户报告："屏幕上没有对应诗句后，长条提示框无法拖动"）
     *
     * 原来 [statusWindowHeightPx] 在没文案时返回 **0** → 窗口被收成 0 高 →
     * 那条窗口**根本摸不到**，`onStatusTouch` 永远收不到触摸事件。
     * 而"没有对应诗句"恰恰是最常见的状态之一（还没识别出来 / 已选完）。
     *
     * 所以只要状态条处于启用且未暂停，窗口始终保留一个够手指按到的高度；
     * 此时只是不画内容（视觉上仍然"看不见"），但能被抓住拖动。
     */
    fun minDragHeightPx(): Int = dp(MIN_DRAG_HEIGHT_DP).roundToInt().coerceAtLeast(1)

    /**
     * **窗口的固定参考高度**（像素）—— 有文案没文案都用它。
     *
     * ### 为什么必须固定（"长条框有的时候会消失"的根因）
     *
     * 窗口的纵向基准是 `Gravity.BOTTOM`，所以位置换算为：
     *
     * ```
     * params.y = screenH - screenY - height
     * ```
     *
     * 只要 `height` 变，同一份 `screenY` 算出的 `params.y` 就会变 ⇒ **窗口在屏幕上跳**。
     * 而文案每帧都可能在"有/无"之间切换（识别到 ↔ 没识别到），
     * 高度就在 `框高` 与 [minDragHeightPx] 之间来回变 ⇒ 反复跳几十到一百多像素；
     * 拖到屏幕边缘时直接被跳出屏幕 —— 用户看到的就是"长条框有的时候会消失"。
     *
     * 所以窗口高度**只取这个固定值**：有文案时按文案算出的框高，没文案时退到
     * [minDragHeightPx]。两者取大，保证都能容纳，且**不随文案抖动**。
     *
     * 代价：没文案时窗口比内容略高一点（透明，不可见），换来位置绝对稳定。
     */
    fun referenceHeightPx(): Int {
        val fm = textPaint.fontMetrics
        val boxH = (fm.bottom - fm.top) + PAD_V_DP * 2
        return dp(boxH).roundToInt().coerceAtLeast(minDragHeightPx())
    }

    /**
     * 窗口需要的高度（像素）。
     *
     * ### 关键不变式：**只要状态条启用且未暂停，窗口高度就恒为 [referenceHeightPx]**
     *
     * 这一条同时解决两个用户报告的问题：
     *
     * 1. **"长条框有的时候会突然消失"** —— 原来高度在"有文案 / 无文案"之间切换
     *    （`框高` ↔ `minDragHeightPx` ↔ `0`），窗口尺寸一变就重排；
     *    再叠加位置基准的换算，就会跳、甚至被跳出屏幕。**高度恒定 ⇒ 不会发生**。
     * 2. **"没有对应诗句时拖不动"** —— 高度恒 ≥ [minDragHeightPx]，窗口永远摸得到。
     *
     * 视觉上的"消失"改由 [onDraw] 负责（无文案时不画）——
     * **窗口始终在、位置始终稳，只是内容按需不画**。这才是"能拖动"该有的结构。
     *
     * 只有 `barEnabled=false` 或 `paused=true` 才返回 0（用户明确关掉 / 暂停时
     * 不留隐形的可触摸区域）。
     *
     * @param keepDragHandle `OverlayService` 在布局窗口时传 true，拿到恒定高度。
     */
    fun statusWindowHeightPx(keepDragHandle: Boolean = false): Int {
        if (keepDragHandle && barEnabled && !paused) return referenceHeightPx()
        if (!barEnabled || paused) return 0
        if (!showing) return minDragHeightPx()
        val fm = textPaint.fontMetrics
        // 与 onDraw 的 boxH 完全一致：不含 TOP_DP（它是窗口位置，不是窗口内边距）
        val boxH = (fm.bottom - fm.top) + PAD_V_DP * 2
        return dp(boxH).roundToInt().coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showing && !showingIdle) return
        val t = text
        if (showing && t.isNullOrBlank()) return

        // 占位态用暗色底 + 灰字，**明显弱于真实结论**，不会被误认成匹配结果。
        // 几何与真实态完全一致（同一个 boxH/字号），所以窗口尺寸不变、位置不跳。
        val idle = !showing
        val label = if (idle) IDLE_TEXT else t!!
        val bg = if (idle) IDLE_BG_COLOR else BG_COLOR
        val fg = if (idle) IDLE_TEXT_COLOR else Color.WHITE

        val padH = dp(PAD_H_DP)
        val padV = dp(PAD_V_DP)
        // **从窗口顶部开始画**（不是 dp(TOP_DP)）—— 窗口高度已经不含 TOP_DP，
        // 所以框必须顶到窗口上边缘，否则又会留下一条空白。
        val top = 0f
        val fm = textPaint.fontMetrics
        val boxW = textPaint.measureText(label) + padH * 2
        val boxH = (fm.bottom - fm.top) + padV * 2
        // 水平居中于**窗口**；窗口自身在屏幕上居中，所以也就是居中于屏幕
        val left = (width - boxW) / 2f
        if (left < 0f) return

        bgPaint.color = bg
        textPaint.color = fg
        rectF.set(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rectF, dp(12f), dp(12f), bgPaint)
        canvas.drawText(label, left + padH, top + padV - fm.top, textPaint)
    }

    companion object {
        private const val BG_COLOR = 0xCC000000.toInt()

        /** 占位态的底色：比真实态更淡，避免与真实结论混淆 */
        private const val IDLE_BG_COLOR = 0x99000000.toInt()

        /** 占位态文字颜色：中性灰，明确不是"匹配成功" */
        private const val IDLE_TEXT_COLOR = 0xFFB0B0B0.toInt()

        /** 占位态文案：简短、中性，只表示"取帧在跑、暂无可选" */
        private const val IDLE_TEXT = "···"

        /**
         * 状态条离屏幕顶部的**默认**距离（dp）。
         *
         * ### ⚠️ 它不再参与窗口高度计算（用户抓出来的一个 bug）
         *
         * 原实现把它加进窗口高度：`height = dp(TOP_DP + 框高)`，而 [onDraw] 又把框
         * 画在 `y = dp(TOP_DP)` 起 —— 于是**窗口顶部到框顶之间留下 `TOP_DP` 的空白**：
         *
         * ```
         * 窗口 y=0 ┌────────────┐
         *         │ 8dp 空白   │  ← 窗口内，但不画背景
         * 框顶    ├────────────┤
         *         │ 文字       │
         * 框底 = 窗口底 ───────┘
         * ```
         *
         * 在 480dpi（density 3）上正好是 **24px**，用户报告「新的框还是有 20 多 px 空白」。
         * 更糟的是那片空白**能接收触摸** → 拖动时手指与可见框之间有固定偏移。
         *
         * 现在它只作为**窗口的默认 y 位置**（见 `OverlayService.applyStatusLayout`）：
         * 窗口整体下移 8dp，窗口内零空白、框与窗口逐像素一致。
         *
         * ### 这个值仍与遮挡有关
         *
         * 真机事故（v0.11.0）：原来是 `28f`，当时状态条底边约 215px、游戏首句在 228px
         * —— **只剩 13px 余量**，所以把效果做成第二行后立刻越界，该局 95 帧里
         * 90 帧读不到首句（**App 遮住了自己要读的字**）。
         *
         * 收紧到 `8f` 后底边约 106px，净空约 122px。由 [StatusBarClearanceTest]
         * 与 `OverlayService.guardHeadOcclusion` / `checkStatusBarOcclusion` 守护。
         */
        const val TOP_DP = 8f

        /**
         * 没有文案时窗口保留的可拖动高度（dp）。
         *
         * 取 20dp：够手指按到，又不至于在游戏画面上占出明显的可触摸区域。
         * 见 [minDragHeightPx] 的说明（这是"没有对应诗句时拖不动"的修法）。
         */
        private const val MIN_DRAG_HEIGHT_DP = 20f

        private const val PAD_H_DP = 12f
        private const val PAD_V_DP = 5f
    }
}
