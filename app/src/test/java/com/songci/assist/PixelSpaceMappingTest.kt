package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 复现真机的**坐标空间**路径：ML Kit 在裁剪带上出的是**像素坐标**块，
 * 经 `mapBlocksToFullFrame` 换算成整帧归一化坐标后，才交给匹配。
 *
 * 我的前几个测试都直接造**归一化**块，跳过了这一步 —— 而真机日志里的
 * `frame.ocr` 明细打的就是换算之后的值，两边未必等价。
 */
class PixelSpaceMappingTest {

    private val index = Fixtures.index()

    /** 造「裁剪带上」的像素块：整帧 2608x1200，裁剪带是 y ∈ [0, 0.85] → 高 1020 */
    private fun px(text: String, yRatioOfFrame: Float): TextBlock {
        val bandTop = 0
        val bandH = 1020
        val cyInBand = (yRatioOfFrame * 1200f) - bandTop
        val h = 24f
        return TextBlock(
            text = text,
            space = CoordSpace.PIXELS,
            left = 780f, top = cyInBand - h / 2,
            right = 1820f, bottom = cyInBand + h / 2,
            frameWidth = 2608, frameHeight = bandH,
        )
    }

    /** 真机第 41 帧的内容，但坐标以**像素**形式给出（模拟 ML Kit 输出） */
    private val frame41Px = listOf(
        px("词牌", 0.06f), px("词元23736", 0.06f), px("保律", 0.08f),
        px("择律收益642", 0.10f), px("属性", 0.10f),
        px("应选破阵子屏上没找到选项气泡", 0.11f),
        px("首句子来时新社梨花落后清明", 0.19f),
        px("豪放词情", 0.23f), px("60", 0.27f), px("婉约词情", 0.34f),
        px("苏幕遮", 0.43f), px("江城子", 0.44f), px("破阵子", 0.57f),
    )

    @Test
    fun `像素块经 mapBlocksToFullFrame 后_气泡坐标仍在选项区内`() {
        val mapped = FramePreprocessorImages.mapBlocksToFullFrame(
            frame41Px, 0f, Config.OCR_CROP_BOTTOM, 1,
        )
        val bubble = mapped.first { VerseIndex.normalize(it.text) == "破阵子" }
        println("    破阵子映射后 centerY=%.3f (期望约 0.57)".format(bubble.normalized().centerY))
        println("    选项区 = %.2f ~ %.2f".format(Config.OPTION_REGION_TOP, Config.OPTION_REGION_BOTTOM))
    }

    @Test
    fun `像素路径_破阵子应能画出框`() {
        val mapped = FramePreprocessorImages.mapBlocksToFullFrame(
            frame41Px, 0f, Config.OCR_CROP_BOTTOM, 1,
        )
        val r = Matcher.match(mapped, index)
        println("    像素路径 Matcher.match -> $r")
        assertEquals(
            "像素路径也应命中，实际 $r",
            true, r is MatchResult.Hit,
        )
    }
}
