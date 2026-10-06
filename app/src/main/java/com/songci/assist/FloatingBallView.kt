package com.songci.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 悬浮球。
 *
 * 它是这个工具的**唯一操作入口**，因为切窗口会改变屏幕方向、而 Android 16
 * 一旦方向变化就停掉投屏 —— 所以"开始录制"必须能在游戏画面上直接完成。
 *
 * | 手势 | 待开始（未取帧） | 取帧中 |
 * |---|---|---|
 * | 单击 | **拉起录屏授权，直接开始取帧** | 暂停 / 继续 |
 * | 拖动 | 移动位置（松手吸附边缘） | 同左 |
 * | 长按 | —— | **停止并退出** |
 *
 * 视觉上区分状态：待开始是**空心**（描边亮青绿），取帧中是**实心**。
 */
class FloatingBallView(context: Context) : View(context) {

    /** 暂停态下的视觉提示（画一条斜杠） */
    var paused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 是否正在取帧（决定单击的行为，也决定空心/实心） */
    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    /** 实心圆（运行中） */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6000000.toInt()
    }

    /** 待开始的空心圆底色（半透明，保证球上的字可读） */
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xB3000000.toInt()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xFF00E5A0.toInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(18f)
        textAlign = Paint.Align.CENTER
    }

    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = 0xFFFF5252.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - ringPaint.strokeWidth
        canvas.drawCircle(cx, cy, r, if (running) fillPaint else idlePaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        val fm = textPaint.fontMetrics
        canvas.drawText("词", cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)

        // 运行中：右下角一个小实心点，进一步区分「在跑」
        if (running) {
            val dot = dp(5f)
            canvas.drawCircle(cx + r * 0.62f, cy - r * 0.62f, dot, ringPaint)
        }

        if (paused) {
            val o = r * 0.72f
            canvas.drawLine(cx - o, cy - o, cx + o, cy + o, slashPaint)
        }
    }
}
