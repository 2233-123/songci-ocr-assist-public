package com.songci.assist

import android.graphics.Bitmap

/**
 * 取帧前的裁剪/缩放。
 *
 * ### 为什么改成「裁顶部带 + 原生分辨率」
 *
 * 旧实现是把**整帧等比缩到宽 ≤ [Config.MAX_FRAME_WIDTH]**，实机上是
 * `2608x1200 → 1080x496`（缩到 41%）。游戏里的首句/词牌是艺术字体，缩小后
 * ML Kit 基本读不出来（实测：整帧缩放时识别出 0~15 个文本块，且常读成
 * 无关的界面文字）。
 *
 * 而 [Matcher] 本来就在**归一化坐标**下只看两个区域：
 * - 顶部带 `y ∈ [Config.TOP_CROP_TOP, Config.TOP_CROP_BOTTOM]`（首句）
 * - 中部带 `y ∈ [Config.OPTION_REGION_TOP, Config.OPTION_REGION_BOTTOM]`（词牌气泡）
 *
 * 所以正确做法是：**把要看的横带裁出来，按原生分辨率交给 OCR**，而不是缩小整帧。
 * 裁剪后坐标仍是归一化比例，无需换算；代价只是多一次 OCR（两段式本来就需要）。
 */
object FramePreprocessorImages {

    /**
     * 裁出归一化 y ∈ [top, bottom] 的横带。
     *
     * @return 裁剪后的 bitmap；若裁剪区域无效则返回**原图**（调用方用 `!==` 判断是否需回收）
     */
    fun cropBand(src: Bitmap, top: Float, bottom: Float): Bitmap {
        val height = src.height
        val y0 = (height * top).toInt().coerceIn(0, height - 1)
        val y1 = (height * bottom).toInt().coerceIn(y0 + 1, height)
        val h = y1 - y0
        if (h <= 0 || h >= height) return src
        return try {
            Bitmap.createBitmap(src, 0, y0, src.width, h)
        } catch (t: Throwable) {
            src
        }
    }

    /**
     * 把「裁剪并放大」后的识别块坐标换算回整帧归一化坐标。
     *
     * 用于「分带放大二次识别」：先裁 y ∈ [bandTop, bandBottom]，再放大 [scale] 倍做 OCR；
     * ML Kit 的 `boundingBox` 是裁剪图内的像素，必须按 `原图比例 = 裁剪图比例 / scale`
     * 再叠加 `bandTop` 偏移，才能回到整帧坐标系。
     */
    fun mapBlocksToFullFrame(
        blocks: List<TextBlock>,
        bandTop: Float,
        bandBottom: Float,
        scale: Int,
    ): List<TextBlock> {
        require(scale >= 1) { "scale must be >= 1" }
        val bandHeight = (bandBottom - bandTop).coerceAtLeast(0.0001f)
        return blocks.map { b ->
            val sp = b.space
            // 先把块坐标换算成「裁剪带内的归一化比例」
            val inLeft: Float
            val inTop: Float
            val inRight: Float
            val inBottom: Float
            if (sp == CoordSpace.PIXELS) {
                val w = b.frameWidth.coerceAtLeast(1).toFloat()
                val h = b.frameHeight.coerceAtLeast(1).toFloat()
                inLeft = b.left / w
                inTop = b.top / h
                inRight = b.right / w
                inBottom = b.bottom / h
            } else {
                inLeft = b.left
                inTop = b.top
                inRight = b.right
                inBottom = b.bottom
            }
            // 再映射回整帧：x 按裁剪带宽（=整帧宽）不变，y 乘裁剪带高度占比并平移
            TextBlock.normalized(
                text = b.text,
                left = inLeft.coerceIn(0f, 1f),
                top = (bandTop + inTop * bandHeight).coerceIn(0f, 1f),
                right = inRight.coerceIn(0f, 1f),
                bottom = (bandTop + inBottom * bandHeight).coerceIn(0f, 1f),
            )
        }
    }

    /**
     * 合并两批识别块并去重。
     *
     * 同一行文字可能两次都读到（文字相同、位置接近）→ 只留一条；
     * 文字不同则是有效补充（例如第一次读错、第二次读对）→ 两条都留，
     * 交给 Matcher 去挑得分更高的那个。
     */
    fun mergeBlocks(
        primary: List<TextBlock>,
        extra: List<TextBlock>,
        centerTolerance: Float = 0.02f,
    ): List<TextBlock> {
        if (extra.isEmpty()) return primary
        val out = ArrayList<TextBlock>(primary.size + extra.size)
        out += primary
        for (e in extra) {
            val eb = e.normalized()
            val dup = primary.any { p ->
                val pb = p.normalized()
                VerseIndex.normalize(pb.text) == VerseIndex.normalize(eb.text) &&
                    kotlin.math.abs(pb.centerY - eb.centerY) <= centerTolerance &&
                    kotlin.math.abs(pb.centerX - eb.centerX) <= centerTolerance
            }
            if (!dup) out += e
        }
        return out
    }

    /**
     * 等比放大指定倍数（用于二次 OCR：艺术字体放大后笔画更易分离）。
     */
    fun scaleUp(src: Bitmap, scale: Int): Bitmap {
        if (scale <= 1) return src
        val w = src.width * scale
        val h = src.height * scale
        // 防御：过大可能 OOM，超过上限就退回 2 倍
        val safeW = minOf(w, MAX_RETRY_PIXELS_W)
        val ratio = safeW.toFloat() / src.width.toFloat()
        val safeH = (src.height * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(src, safeW, safeH, true)
        } catch (t: Throwable) {
            src
        }
    }

    /** 二次识别允许的最大宽度（约 4000px，足够放大又不至于 OOM） */
    private const val MAX_RETRY_PIXELS_W = 4000

    /**
     * 缩放到指定宽度以内。当前取帧路径已改为原生分辨率，此函数仅作为兜底保留。
     */
    fun scaleDownForOcr(src: Bitmap, maxWidth: Int = Config.MAX_FRAME_WIDTH): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width.toFloat()
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxWidth, h, true)
    }
}
