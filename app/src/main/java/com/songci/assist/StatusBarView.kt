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

    /**
     * 副行文案 —— **词句效果**，例如 `民心-10 ｜ 战斗力+10`。
     *
     * 单独一行而不是接在主行后面：主行（`应选：X` / `首句 → 词牌 ✓ 0.88`）本来就不短，
     * 再接一长串效果会接近屏宽、右侧还会被游戏的「词牌属性」面板压住。
     *
     * null / 空白 = 不显示（窗口高度按一行算）。
     */
    var subText: String? = null
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

    /**
     * 副行（效果）用稍小、稍暗的字：信息密度更高，但视觉上不能抢主行的注意力。
     *
     * 字号取 14sp 而非更小 —— 游戏手书字体在 15sp 以下可读性明显下降
     * （实测气泡字号 18px 已是下限）。
     */
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EFFECT_COLOR
        textSize = dp(14f)
    }

    private val rectF = RectF()

    /** 当前是否真的需要显示 */
    private val showing: Boolean
        get() = barEnabled && !paused && !text.isNullOrBlank()

    /** 是否需要画第二行（词句效果）。 */
    private val showingSub: Boolean
        get() = showing && !subText.isNullOrBlank()

    /**
     * 窗口需要的高度（像素）。不显示时返回 0 → 窗口收成 0 高，屏幕上不留痕迹。
     *
     * 由 [OverlayService] 在调整窗口时调用，保证 view 与窗口尺寸一致。
     * 有副行（效果）时高度按两行算。
     */
    fun statusWindowHeightPx(): Int {
        if (!showing) return 0
        val fm = textPaint.fontMetrics
        var h = (fm.bottom - fm.top) + PAD_V_DP * 2
        if (showingSub) {
            val subFm = subPaint.fontMetrics
            h += SUB_GAP_DP + (subFm.bottom - subFm.top)
        }
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
        val sub = subText
        val subFm = subPaint.fontMetrics

        // 宽度取两行里更宽的那个
        val mainW = textPaint.measureText(t) + padH * 2
        val subW = if (showingSub) subPaint.measureText(sub) + padH * 2 else 0f
        val boxW = maxOf(mainW, subW)
        val boxH = if (showingSub) {
            (fm.bottom - fm.top) + PAD_V_DP * 2 + dp(SUB_GAP_DP) + (subFm.bottom - subFm.top)
        } else {
            (fm.bottom - fm.top) + PAD_V_DP * 2
        }
        // 水平居中于**窗口**；窗口宽度 = 整屏宽度，所以也就是居中于屏幕
        val left = (width - boxW) / 2f
        if (left < 0f) return

        rectF.set(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rectF, dp(12f), dp(12f), bgPaint)
        // 主行左对齐（居中会随长度抖动），整体盒子居中
        canvas.drawText(t, left + padH, top + padV - fm.top, textPaint)
        if (showingSub) {
            val subTop = top + padV + (fm.bottom - fm.top) + dp(SUB_GAP_DP)
            // sub 的类型是 String?（Kotlin 无法从 showingSub 这个自定义 getter 推断非空）
            canvas.drawText(sub.orEmpty(), left + padH, subTop - subFm.top, subPaint)
        }
    }

    companion object {
        private const val BG_COLOR = 0xCC000000.toInt()

        /** 副行（效果）颜色：偏暖黄，与高亮框同色系但更柔 */
        private const val EFFECT_COLOR = 0xFFFFD98A.toInt()

        /** 距屏幕顶部 */
        private const val TOP_DP = 28f
        private const val PAD_H_DP = 12f
        private const val PAD_V_DP = 7f

        /** 主行与副行之间的间距 */
        private const val SUB_GAP_DP = 4f
    }
}
