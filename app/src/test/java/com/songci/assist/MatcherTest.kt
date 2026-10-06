package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Matcher] 的主用例。
 *
 * 背景：`明月别枝惊鹊` → 词句 id 75 → 词牌「西江月」，这是设计文档里用户在实机截图上
 * 验过的那一条，三个选项里「西江月」是第二个气泡。fixture 里的文本块与坐标就是按
 * 截图场景（顶部首句 + 中部三个词牌气泡）写的，坐标用归一化比例，与分辨率无关。
 */
class MatcherTest {

    private val index: VerseIndex = Fixtures.index()

    @Test
    fun `实机截图场景_命中西江月并高亮第二个气泡`() {
        val blocks = Fixtures.screenshotBlocks()
        val head = Matcher.scanHead(blocks, index)
        assertTrue("首句应命中，实际 $head", head is HeadMatch.Hit)
        head as HeadMatch.Hit
        assertEquals("西江月", head.verse.pai)
        assertEquals(75, head.verse.id)
        assertEquals(1.0, head.similarity, 1e-9)

        val result = Matcher.match(blocks, index)
        assertTrue("应得出完整命中，实际 $result", result is MatchResult.Hit)
        result as MatchResult.Hit
        assertEquals("西江月", result.verse.pai)
        // 第二个气泡（fixtures 里气泡 y 依次为 0.52 / 0.60 / 0.68）
        assertEquals(Fixtures.BUBBLE_2, result.target)
    }

    // ------------------------------------------------------------------
    // 2026-10-05 实机截图：顶部横条「（首句）候馆梅残，溪桥柳细，草薰风暖摇征辔」，
    // 三个气泡是 踏莎行 / 醉花阴 / 苏幕遮。
    // 去掉标点后 OCR 文本会**多出"首句"两个字**（前缀噪声），整体相似度只剩 0.88，
    // 再错一个字就跌破 0.72 而完全不提示 —— 这正是真机「看不到高亮」的根因之一。
    // ------------------------------------------------------------------

    @Test
    fun `实机截图_带首句前缀也能命中踏莎行`() {
        val blocks = page60Blocks("（首句）候馆梅残，溪桥柳细，草薰风暖摇征辔")
        val head = Matcher.scanHead(blocks, index)
        assertTrue("应命中，实际 $head", head is HeadMatch.Hit)
        head as HeadMatch.Hit
        assertEquals("踏莎行", head.verse.pai)

        val result = Matcher.match(blocks, index)
        assertTrue("应框住第一个气泡，实际 $result", result is MatchResult.Hit)
        result as MatchResult.Hit
        assertEquals("踏莎行", result.verse.pai)
        assertEquals(0.28f, result.target.left, 1e-6f)
    }

    @Test
    fun `实机截图_前缀噪声外加一个字错也仍命中`() {
        // 「辔」被 OCR 读成「错」
        val blocks = page60Blocks("（首句）候馆梅残，溪桥柳细，草薰风暖摇征错")
        val result = Matcher.match(blocks, index)
        assertTrue("带前缀+错字仍应命中，实际 $result", result is MatchResult.Hit)
        assertEquals("踏莎行", (result as MatchResult.Hit).verse.pai)
    }

    @Test
    fun `实机截图_没有前缀时精确命中`() {
        val blocks = page60Blocks("候馆梅残，溪桥柳细，草薰风暖摇征辔")
        val head = Matcher.scanHead(blocks, index) as HeadMatch.Hit
        assertEquals("踏莎行", head.verse.pai)
        assertEquals(1.0, head.similarity, 1e-9)
    }

    /** 按实机截图的相对坐标构造一页：顶部首句 + 三个气泡。 */
    private fun page60Blocks(headLine: String): List<TextBlock> = listOf(
        TextBlock.normalized(headLine, 0.24f, 0.19f, 0.76f, 0.23f),
        TextBlock.normalized("踏莎行", 0.28f, 0.42f, 0.42f, 0.47f),
        TextBlock.normalized("醉花阴", 0.46f, 0.57f, 0.60f, 0.62f),
        TextBlock.normalized("苏幕遮", 0.63f, 0.43f, 0.77f, 0.48f),
    )

    @Test
    fun `选项顺序颠倒也认得出`() {
        // 第一次：西江月 在中间气泡（位置 2）
        val middle = Fixtures.headOnly() + Fixtures.optionBlocks("念奴娇", "西江月", "水调歌头")
        val hitMiddle = Matcher.match(middle, index) as MatchResult.Hit
        assertEquals("西江月", hitMiddle.verse.pai)
        assertEquals(Fixtures.BUBBLE_2, hitMiddle.target)

        // 第二次：西江月 在第一个气泡 —— 框必须跟着文字走，而不是钉在某个位置
        val first = Fixtures.headOnly() + Fixtures.optionBlocks("西江月", "念奴娇", "水调歌头")
        val hitFirst = Matcher.match(first, index) as MatchResult.Hit
        assertEquals("西江月", hitFirst.verse.pai)
        assertEquals(Fixtures.BUBBLE_1, hitFirst.target)

        // 第三次：西江月 在最后一个气泡
        val last = Fixtures.headOnly() + Fixtures.optionBlocks("念奴娇", "水调歌头", "西江月")
        val hitLast = Matcher.match(last, index) as MatchResult.Hit
        assertEquals("西江月", hitLast.verse.pai)
        assertEquals(Fixtures.BUBBLE_3, hitLast.target)
    }

    @Test
    fun `屏幕上有同名词牌时取中部面积最大的块`() {
        // 首句 + 三个选项气泡（西江月面积最大）
        val blocks = (Fixtures.headOnly() + Fixtures.optionBlocks("西江月", "念奴娇", "水调歌头")).toMutableList()
        // 中部还有一小块同名词牌（例如历史记录小字），面积明显更小 → 不能被选中
        blocks += TextBlock.normalized("西江月", 0.06f, 0.29f, 0.14f, 0.33f)
        // 顶部提示文案（不在中部选项区，且与首句之间有空隙、不会和首句拼成一句）
        blocks += TextBlock.normalized("提示：上局答对了", 0.05f, 0.03f, 0.95f, 0.06f)

        val result = Matcher.match(blocks, index) as MatchResult.Hit
        assertEquals("西江月", result.verse.pai)
        assertEquals(Fixtures.BUBBLE_1, result.target)
    }

    @Test
    fun `首句被拆成两行时先拼接再匹配`() {
        val blocks = Fixtures.headSplitIntoTwoLines() + Fixtures.optionBlocks("西江月", "念奴娇", "水调歌头")
        val head = Matcher.scanHead(blocks, index)
        assertTrue("拆行后应命中，实际 $head", head is HeadMatch.Hit)
        assertEquals("西江月", (head as HeadMatch.Hit).verse.pai)
        // 拼接顺序按 y 坐标，不能把下句拼到上句前面
        assertEquals("明月别枝惊鹊清风半夜鸣蝉", head.matchedText)
    }

    @Test
    fun `首句被折成三行时只拼相邻两行_得到前两行`() {
        val blocks = Fixtures.headSplitIntoThreeLines() + Fixtures.optionBlocks("西江月", "念奴娇", "水调歌头")
        // MAX_MERGED_LINES=2 生效：第三行不参与合并，结论来自前两行
        val result = Matcher.match(blocks, index)
        assertTrue("应命中，实际 $result", result is MatchResult.Hit)
        result as MatchResult.Hit
        assertEquals("西江月", result.verse.pai)
        assertEquals("明月别枝惊鹊清风半夜鸣蝉", result.matchedText)
    }

    @Test
    fun `认得出词牌但屏上没有气泡_输出应选`() {
        val blocks = Fixtures.headOnly() // 只有首句，没有选项气泡
        val result = Matcher.match(blocks, index)
        assertTrue("应得出 PaiOnly，实际 $result", result is MatchResult.PaiOnly)
        result as MatchResult.PaiOnly
        assertEquals("西江月", result.verse.pai)
        assertEquals(1.0, result.similarity, 1e-9)
        assertEquals(75, result.verse.id)
    }

    @Test
    fun `气泡在顶部区域外时按找不到处理`() {
        // 词牌名只在 y=0.15（顶部提示条里），不在中部选项区
        val blocks = Fixtures.headOnly() +
            TextBlock.normalized("西江月", 0.40f, 0.13f, 0.60f, 0.17f)
        val result = Matcher.match(blocks, index)
        assertTrue("顶部同名块不应被当成气泡，实际 $result", result is MatchResult.PaiOnly)
    }

    @Test
    fun `选项气泡只认精确等值_含词牌名的长句不算`() {
        val blocks = Fixtures.headOnly() + TextBlock.normalized(
            "上一题答案西江月继续加油",
            0.10f,
            0.55f,
            0.90f,
            0.62f,
        )
        val result = Matcher.match(blocks, index)
        assertTrue("长句不应被当成气泡，实际 $result", result is MatchResult.PaiOnly)
    }

    @Test
    fun `尾部截断的文本零编辑距离不命中_低于最短长度则丢弃`() {
        // 2 个字：低于 MIN_HEAD_LEN，直接不参与匹配
        assertTrue(Matcher.scanHead(listOf(TextBlock.normalized("明月", 0.1f, 0.2f, 0.3f, 0.24f)), index)
            is HeadMatch.Miss)
    }

    @Test
    fun `空块与全噪声不误报`() {
        assertTrue(Matcher.scanHead(emptyList(), index) is HeadMatch.Miss)
        val noise = listOf(
            TextBlock.normalized("12:34", 0.04f, 0.03f, 0.15f, 0.07f),
            TextBlock.normalized("第 3 回合", 0.80f, 0.03f, 0.96f, 0.07f),
            TextBlock.normalized("— 请选择 —", 0.35f, 0.82f, 0.65f, 0.87f),
        )
        assertEquals(MatchResult.None, Matcher.match(noise, index))
    }

    @Test
    fun `裁剪区外的块不参与首句匹配`() {
        // 首句被放在屏幕正中（y≈0.60），超出顶部 0.45 的裁剪区 → 不匹配
        val blocks = listOf(TextBlock.normalized("明月别枝惊鹊清风半夜鸣蝉", 0.06f, 0.58f, 0.94f, 0.63f))
        assertTrue(Matcher.scanHead(blocks, index) is HeadMatch.Miss)
        assertEquals(MatchResult.None, Matcher.match(blocks, index))
    }

    @Test
    fun `坐标归一化_pixels 与 normalized 等价`() {
        val frameW = 1080
        val frameH = 2400
        val normalized = Fixtures.screenshotBlocks()
        val pixel = normalized.map {
            TextBlock.pixels(
                text = it.text,
                left = (it.left * frameW).toInt(),
                top = (it.top * frameH).toInt(),
                right = (it.right * frameW).toInt(),
                bottom = (it.bottom * frameH).toInt(),
                frameWidth = frameW,
                frameHeight = frameH,
            )
        }
        val a = Matcher.match(normalized, index)
        val b = Matcher.match(pixel, index)
        assertTrue("两侧都应命中，实际 a=$a b=$b", a is MatchResult.Hit && b is MatchResult.Hit)
        a as MatchResult.Hit
        b as MatchResult.Hit
        assertEquals(a.verse.id, b.verse.id)
        // 像素取整是**有损**的（0.24*1080=259.2 → 259），所以只能按一个像素的误差比较
        val tolX = 1f / frameW
        val tolY = 1f / frameH
        assertEquals("left", a.target.left, b.target.left, tolX)
        assertEquals("right", a.target.right, b.target.right, tolX)
        assertEquals("top", a.target.top, b.target.top, tolY)
        assertEquals("bottom", a.target.bottom, b.target.bottom, tolY)
    }

    @Test
    fun `scanOptions 对未知词牌返回 null`() {
        assertNull(Matcher.scanOptions(Fixtures.screenshotBlocks(), "不是词牌名", index))
        assertNull(Matcher.scanOptions(emptyList(), "西江月", index))
    }

    @Test
    fun `fixture 自检_三个气泡坐标稳定`() {
        val options = Fixtures.optionBlocks("西江月", "念奴娇", "水调歌头")
        assertEquals(Fixtures.BUBBLE_1, Matcher.scanOptions(options, "西江月", index))
        assertEquals(Fixtures.BUBBLE_2, Matcher.scanOptions(options, "念奴娇", index))
        assertEquals(Fixtures.BUBBLE_3, Matcher.scanOptions(options, "水调歌头", index))
        assertNotNull(Matcher.scanOptions(options, "西江月", index))
    }

    @Test
    fun `重复匹配结果可相等_用于悬浮层去重`() {
        // OverlayService 用 `outcome == lastOutcome` 判断"同一结论重复推送"，
        // 所以 data class 的相等性必须成立
        val a = Matcher.match(Fixtures.screenshotBlocks(), index)
        val b = Matcher.match(Fixtures.screenshotBlocks(), index)
        assertEquals(a, b)
        assertTrue(a.hashCode() == b.hashCode())
    }
}
