package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二次识别（裁带 + 放大）的坐标映射与去重合并。
 *
 * 真机背景：游戏的首句/词牌是艺术字体，原生分辨率下 ML Kit 时常读错或漏读；
 * 补救办法是把对应横带裁出来放大 2 倍再识别一次，因此**带内放大坐标必须能正确
 * 换算回整帧坐标**——算错会让 Matcher 的「顶部 45% / 中部 30~80%」判断整体偏移，
 * 表现就是"读到了却不出框"。
 */
class BandRetryMappingTest {

    private val frameW = 2608
    private val frameH = 1200

    @Test
    fun `放大带内的像素坐标能换算回整帧归一化坐标`() {
        // 裁 y ∈ [0, 0.45]（540px 高），放大 2 倍 → 1080px
        val blocks = listOf(
            TextBlock.pixels(
                text = "首句对潇潇暮雨洒江天一番洗清秋",
                left = 730, top = 400, right = 1880, bottom = 520,
                frameWidth = frameW, frameHeight = 1080,   // 放大后的尺寸
            ),
        )
        val mapped = FramePreprocessorImages.mapBlocksToFullFrame(blocks, 0f, 0.45f, 2)
        assertEquals(1, mapped.size)
        val b = mapped[0].normalized()
        // x 不变（700/2608 ≈ 0.2684...），y 落在带内并平移
        assertEquals(730f / frameW, b.left, 1e-4f)
        assertEquals(1880f / frameW, b.right, 1e-4f)
        assertEquals(400f / 1080f * 0.45f, b.top, 1e-4f)
        assertEquals(520f / 1080f * 0.45f, b.bottom, 1e-4f)
        // 换算后必须仍落在顶部区域内，否则 Matcher 找不到首句
        assertTrue("换算后应在顶部区(y<=0.45)，实际 centerY=${b.centerY}", b.centerY <= 0.45f)
    }

    @Test
    fun `中部带的换算结果应落在选项区内`() {
        val blocks = listOf(
            TextBlock.pixels(
                text = "定风波",
                left = 1150, top = 100, right = 1500, bottom = 220,
                frameWidth = frameW, frameHeight = 1200,   // 0.30~0.80 带 = 600px，放大 2 倍
            ),
        )
        val mapped = FramePreprocessorImages.mapBlocksToFullFrame(blocks, 0.30f, 0.80f, 2)
        val cy = mapped[0].normalized().centerY
        assertTrue("换算后应落在中部选项区(0.30~0.80)，实际 $cy", cy in 0.30f..0.80f)
    }

    @Test
    fun `文字与位置都相同的块会被去重`() {
        val a = listOf(TextBlock.normalized("定风波", 0.44f, 0.50f, 0.58f, 0.56f))
        val b = listOf(TextBlock.normalized("定风波", 0.44f, 0.50f, 0.58f, 0.56f))
        assertEquals(1, FramePreprocessorImages.mergeBlocks(a, b).size)
    }

    @Test
    fun `文字不同则都保留_交给匹配挑更好的`() {
        // 第一次读错「定风护」、第二次读对「定风波」→ 两条都要留
        val a = listOf(TextBlock.normalized("定风护", 0.44f, 0.50f, 0.58f, 0.56f))
        val b = listOf(TextBlock.normalized("定风波", 0.44f, 0.50f, 0.58f, 0.56f))
        assertEquals(2, FramePreprocessorImages.mergeBlocks(a, b).size)
    }

    @Test
    fun `二次识别的补充块能让原本漏读的气泡被框住`() {
        val index = Fixtures.index()
        // 第一遍：气泡漏读（只有首句）
        val first = listOf(
            TextBlock.normalized("首句少日春怀似酒浓插花走马醉千钟", 0.28f, 0.17f, 0.72f, 0.22f),
            TextBlock.normalized("长相思", 0.30f, 0.40f, 0.42f, 0.46f),
        )
        val r1 = Matcher.match(first, index)
        assertTrue("第一遍应只认出词牌，实际 $r1", r1 is MatchResult.PaiOnly)
        assertEquals("定风波", (r1 as MatchResult.PaiOnly).verse.pai)

        // 第二遍：放大后补读到「定风波」
        val extra = listOf(TextBlock.normalized("定风波", 0.46f, 0.54f, 0.61f, 0.60f))
        val merged = FramePreprocessorImages.mergeBlocks(first, extra)
        val r2 = Matcher.match(merged, index)
        assertTrue("合并后应能框住，实际 $r2", r2 is MatchResult.Hit)
        assertEquals("定风波", (r2 as MatchResult.Hit).verse.pai)
        assertEquals(0.46f, r2.target.left, 1e-6f)
    }
}
