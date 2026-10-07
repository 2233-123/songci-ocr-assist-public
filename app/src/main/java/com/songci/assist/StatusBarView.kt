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

    /** 当前是否真的需要显示 */
    private val showing: Boolean
        get() = barEnabled && !paused && !text.isNullOrBlank()

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
    fun statusWindowHeightPx(): Int {
        if (!showing) return 0
        val fm = textPaint.fontMetrics
        val h = (fm.bottom - fm.top) + PAD_V_DP * 2
        return (TOP_DP * 2 + h).toFloat().let { dp(it) }
            .roundToInt()
            .coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = text
        if (!showing || t.isNullOrBlank()) return

        val padH = dp(PAD_H_DP)
        val padV = dp(PAD_V_DP)
        val top = dp(TOP_DP)
        val fm = textPaint.fontMetrics
        val boxW = textPaint.measureText(t) + padH * 2
        val boxH = (fm.bottom - fm.top) + padV * 2
        // 水平居中于**窗口**；窗口宽度 = 整屏宽度，所以也就是居中于屏幕
        val left = (width - boxW) / 2f
        if (left < 0f) return

        rectF.set(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rectF, dp(12f), dp(12f), bgPaint)
        canvas.drawText(t, left + padH, top + padV - fm.top, textPaint)
    }

    companion object {
        private const val BG_COLOR = 0xCC000000.toInt()

        /**
         * 距屏幕顶部的距离（dp）。
         *
         * **这个值直接决定「状态条会不会遮住游戏首句」**，不要随意加大。
         *
         * 真机事故（v0.11.0）：原来是 `28f`，当时的实测是状态条底边约 215px、
         * 而游戏首句在 228px —— **只剩 13px 余量**。所以把效果做成第二行后
         * 立刻越界，该局 95 帧里 90 帧读不到首句（**App 遮住了自己要读的字**）。
         *
         * 现在收紧到 `8f` 并配 [PAD_V_DP] = 5f，状态条底边约 165px，
         * 净空约 63px（0.05 屏高），能容下以后再微调。
         * 由 [StatusBarClearanceTest] 与 `OverlayService.guardHeadOcclusion` 双重守护。
         */
        private const val TOP_DP = 8f

        private const val PAD_H_DP = 12f
        private const val PAD_V_DP = 5f
    }
}
