package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**实机第 21 帧的真实 OCR 输出**锁定首句匹配。
 *
 * 背景：真机上 OCR 明确读到了
 * `y=0.19:首句对潇潇暮雨洒江天1番洗清秋`（注意「一」被读成数字「1」），
 * 但 App 内 scanHead 返回未匹配。本测试把这组数据固定下来，
 * 只要它通过，就说明"数据 → 匹配"这一步没问题，问题在别处。
 */
class RealFrameMatchTest {

    private val index: VerseIndex = Fixtures.index()

    /** 实机第 21 帧的 16 个块（y=centerY，x 按截图估计） */
    private fun realFrameBlocks(headText: String): List<TextBlock> = listOf(
        block("词元469", 0.78f, 0.05f, 0.92f, 0.09f),
        block("屏幕共享中", 0.44f, 0.02f, 0.58f, 0.06f),
        block("择律0", 0.78f, 0.07f, 0.88f, 0.11f),
        block("择律收益314", 0.78f, 0.09f, 0.95f, 0.13f),
        block("属性", 0.68f, 0.10f, 0.74f, 0.14f),
        block("66", 0.94f, 0.14f, 0.98f, 0.18f),
        block(headText, 0.28f, 0.17f, 0.72f, 0.22f),
        block("豪放词情", 0.90f, 0.21f, 0.99f, 0.25f),
        block("60", 0.94f, 0.25f, 0.98f, 0.29f),
        block("婉约词情", 0.90f, 0.32f, 0.99f, 0.36f),
        block("长相思", 0.30f, 0.40f, 0.42f, 0.46f),
        block("踏莎行", 0.62f, 0.40f, 0.74f, 0.46f),
        block("词", 0.02f, 0.51f, 0.08f, 0.55f),
        block("八声甘州", 0.46f, 0.54f, 0.61f, 0.60f),
        block("暮烟", 0.10f, 0.73f, 0.20f, 0.77f),
        block("当前歌板89", 0.76f, 0.94f, 0.95f, 0.98f),
    )

    private fun block(t: String, l: Float, tp: Float, r: Float, b: Float) =
        TextBlock.normalized(t, l, tp, r, b)

    @Test
    fun `实机第21帧_OCR原文应命中八声甘州`() {
        val blocks = realFrameBlocks("首句对潇潇暮雨洒江天1番洗清秋")
        val head = Matcher.scanHead(blocks, index)
        assertTrue("应命中，实际 $head", head is HeadMatch.Hit)
        head as HeadMatch.Hit
        assertEquals("八声甘州", head.verse.pai)
    }

    @Test
    fun `汉字一的版本也应命中`() {
        val blocks = realFrameBlocks("首句对潇潇暮雨洒江天一番洗清秋")
        val head = Matcher.scanHead(blocks, index)
        assertTrue("应命中，实际 $head", head is HeadMatch.Hit)
        assertEquals("八声甘州", (head as HeadMatch.Hit).verse.pai)
    }

    @Test
    fun `整条匹配链路应给出完整命中`() {
        val blocks = realFrameBlocks("首句对潇潇暮雨洒江天1番洗清秋")
        val result = Matcher.match(blocks, index)
        assertTrue("应得出完整命中，实际 $result", result is MatchResult.Hit)
        val hit = result as MatchResult.Hit
        assertEquals("八声甘州", hit.verse.pai)
        // 气泡应在中部的「八声甘州」上
        assertEquals(0.46f, hit.target.left, 1e-6f)
    }

    @Test
    fun `顶部文本采样应能取到首句_需给足行数`() {
        val blocks = realFrameBlocks("首句对潇潇暮雨洒江天1番洗清秋")
        // 顶部区域内比首句更靠上的还有 6 个 UI 块，所以 maxLines 必须够大，
        // 否则首句会被截掉 —— 这正是之前诊断日志"看不到首句"的原因
        val tooFew = Matcher.topTextSample(blocks, maxLines = 3)
        assertTrue("行数太少确实取不到首句: $tooFew", !tooFew.contains("潇潇"))

        val enough = Matcher.topTextSample(blocks, maxLines = 8)
        assertTrue("给足行数应包含首句，实际: $enough", enough.contains("潇潇"))
    }

    // ------------------------------------------------------------------
    // 气泡（词牌名）被 OCR 读错一个字：过去只认精确等值 → 能看到字却没有框。
    // 词牌名全部 3/4 字且不存在同长度近似对，所以「同长度 + 错 1 字」可以安全地救回来。
    // ------------------------------------------------------------------

    /** 构造一页：顶部首句（钗头凤·红酥手）+ 中部三个气泡（气泡文字可指定） */
    private fun pageWithOptions(opt1: String, opt2: String, opt3: String): List<TextBlock> = listOf(
        block("首句红酥手黄縢酒满城春色宫墙柳", 0.28f, 0.17f, 0.72f, 0.22f),
        block(opt1, 0.30f, 0.40f, 0.42f, 0.46f),
        block(opt2, 0.46f, 0.54f, 0.61f, 0.60f),
        block(opt3, 0.62f, 0.40f, 0.74f, 0.46f),
    )

    @Test
    fun `钗头凤_气泡文字被读错一个字也能框住`() {
        // 正确答案是 钗头凤，OCR 把「钗」读成了「叙」
        val blocks = pageWithOptions("叙头凤", "长相思", "踏莎行")
        val result = Matcher.match(blocks, index)
        assertTrue("应命中并框住气泡，实际 $result", result is MatchResult.Hit)
        result as MatchResult.Hit
        assertEquals("钗头凤", result.verse.pai)
        assertEquals(0.30f, result.target.left, 1e-6f)
    }

    @Test
    fun `钗头凤_精确识别时当然也能框住`() {
        val blocks = pageWithOptions("钗头凤", "长相思", "踏莎行")
        val result = Matcher.match(blocks, index) as MatchResult.Hit
        assertEquals("钗头凤", result.verse.pai)
    }

    @Test
    fun `模糊匹配不会把A词牌认成B词牌`() {
        // 三个气泡都读错一个字，但只有正确答案的字面接近「钗头凤」
        val blocks = pageWithOptions("叙头凤", "长想思", "踏沙行")
        val result = Matcher.match(blocks, index) as MatchResult.Hit
        assertEquals("钗头凤", result.verse.pai)
        // 必须框在第一个气泡上，而不是被「长想思/踏沙行」抢走
        assertEquals(0.30f, result.target.left, 1e-6f)
    }

    @Test
    fun `更接近的候选胜出_且胜出裕度足够`() {
        // 「钗头凤」被读成「叙头凤」；另一块「叙头风」距离更远（编辑距离 2 vs 1）
        val a = block("叙头凤", 0.30f, 0.40f, 0.42f, 0.46f)
        val b = block("叙头风", 0.46f, 0.54f, 0.61f, 0.60f)
        val sa = Matcher.similarity(VerseIndex.normalize(a.text), "钗头凤")
        val sb = Matcher.similarity(VerseIndex.normalize(b.text), "钗头凤")
        assertEquals("叙头凤 应得 0.667", 0.667, sa, 0.01)
        assertEquals("叙头风 应得 0.333（差两个字）", 0.333, sb, 0.01)

        val blocks = listOf(
            block("首句红酥手黄縢酒满城春色宫墙柳", 0.28f, 0.17f, 0.72f, 0.22f),
            a,
            b,
        )
        val target = Matcher.scanOptions(blocks, "钗头凤", index)
        org.junit.Assert.assertNotNull("应框住更接近的那个", target)
        assertEquals("应框住第一个气泡", 0.30f, target!!.left, 1e-6f)

        val result = Matcher.match(blocks, index) as MatchResult.Hit
        assertEquals("钗头凤", result.verse.pai)
        assertEquals(0.30f, result.target.left, 1e-6f)
    }

    @Test
    fun `两个候选同分时宁可不出框`() {
        // 两块与「钗头凤」的相似度完全相同 → 分不出高下 → 不画框
        val blocks = listOf(
            block("首句红酥手黄縢酒满城春色宫墙柳", 0.28f, 0.17f, 0.72f, 0.22f),
            block("叙头凤", 0.30f, 0.40f, 0.42f, 0.46f),
            block("钗头叙", 0.46f, 0.54f, 0.61f, 0.60f),   // 同样是 1 个字不同
        )
        val s1 = Matcher.similarity(VerseIndex.normalize("叙头凤"), "钗头凤")
        val s2 = Matcher.similarity(VerseIndex.normalize("钗头叙"), "钗头凤")
        assertEquals("两者应同分", s1, s2, 1e-9)

        val target = Matcher.scanOptions(blocks, "钗头凤", index)
        org.junit.Assert.assertNull("同分时必须返回 null，实际 $target", target)
        assertTrue(
            "不应画框，实际 ${Matcher.match(blocks, index)}",
            Matcher.match(blocks, index) !is MatchResult.Hit,
        )
    }
}
