package com.songci.assist

/**
 * 文本块坐标空间。
 *
 * [NORMALIZED] 是 [Matcher] 单测与实机 fixture 使用的统一坐标系：
 * x,y 均为相对当帧宽高的比例（0..1）。这样 fixture 与分辨率无关，
 * 而 Android 端只需在进入 [Matcher] 前做一次换算。
 */
enum class CoordSpace { NORMALIZED, PIXELS }

/**
 * OCR 出来的一个文本块。
 *
 * @param text 识别文本（未规范化）
 * @param space 坐标空间
 * @param left 左边界
 * @param top 上边界
 * @param right 右边界
 * @param bottom 下边界
 * @param frameWidth 当帧宽度；[CoordSpace.PIXELS] 时必填
 * @param frameHeight 当帧高度；[CoordSpace.PIXELS] 时必填
 * @param confidence ML Kit 给的块级置信度（0..1），可能为 null（引擎不给）
 * @param lines 该块内部的原始行文本（ML Kit 的 lines），用于调试与日志
 */
data class TextBlock(
    val text: String,
    val space: CoordSpace = CoordSpace.NORMALIZED,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val confidence: Float? = null,
    val lines: List<String> = emptyList(),
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 统一转到归一化坐标（0..1）。像素块缺 frameWidth/Height 时按最大边兜底。 */
    fun normalized(): TextBlock {
        if (space == CoordSpace.NORMALIZED) return this
        val w = if (frameWidth > 0) frameWidth.toFloat() else maxOf(right, bottom, left, 1f)
        val h = if (frameHeight > 0) frameHeight.toFloat() else maxOf(bottom, right, top, 1f)
        return copy(
            space = CoordSpace.NORMALIZED,
            left = (left / w).coerceIn(0f, 1f),
            top = (top / h).coerceIn(0f, 1f),
            right = (right / w).coerceIn(0f, 1f),
            bottom = (bottom / h).coerceIn(0f, 1f),
            frameWidth = 0,
            frameHeight = 0,
        )
    }

    companion object {
        /** 单测/fixture 用：直接给归一化坐标的矩形。 */
        fun normalized(
            text: String,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            confidence: Float? = null,
            lines: List<String> = emptyList(),
        ) = TextBlock(
            text = text,
            space = CoordSpace.NORMALIZED,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = confidence,
            lines = lines,
        )

        /** Android 端用：给像素坐标 + 当帧尺寸。 */
        fun pixels(
            text: String,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            frameWidth: Int,
            frameHeight: Int,
            confidence: Float? = null,
            lines: List<String> = emptyList(),
        ) = TextBlock(
            text = text,
            space = CoordSpace.PIXELS,
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat(),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            confidence = confidence,
            lines = lines,
        )
    }
}

/** 高亮要画在哪个矩形上（归一化坐标）。 */
data class HighlightRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        fun of(block: TextBlock): HighlightRect {
            val b = block.normalized()
            return HighlightRect(b.left, b.top, b.right, b.bottom)
        }
    }
}

/** 第一段（找首句）的结果。 */
sealed interface HeadMatch {
    /** 命中：命中词句 + 相似度 + 实际参与匹配的拼接文本。 */
    data class Hit(
        val verse: Verse,
        val similarity: Double,
        val matchedText: String,
    ) : HeadMatch

    /** 本帧没有可用首句。 */
    data object Miss : HeadMatch
}

/** 整帧的匹配结论。 */
sealed interface MatchResult {
    /** 完全命中：知道该选哪个词牌，并且能在屏上找到气泡。 */
    data class Hit(
        val verse: Verse,
        val similarity: Double,
        val matchedText: String,
        val target: HighlightRect,
    ) : MatchResult

    /** 认出了词牌，但屏上没找到对应气泡（OCR 漏识）→ 状态条显示「应选：X」，不画框。 */
    data class PaiOnly(
        val verse: Verse,
        val similarity: Double,
        val matchedText: String,
    ) : MatchResult

    /** 未命中 → 什么都不显示（宁可不提示，也不误报）。 */
    data object None : MatchResult
}
