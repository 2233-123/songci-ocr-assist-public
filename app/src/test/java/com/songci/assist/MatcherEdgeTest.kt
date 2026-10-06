package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边界与防误报：宁可不提示，也不能画错框。
 */
class MatcherEdgeTest {

    private val index = Fixtures.index()

    @Test
    fun `纯数字时间文本不误报`() {
        val blocks = listOf(
            TextBlock.normalized("12:34", 0.04f, 0.02f, 0.15f, 0.06f),
            TextBlock.normalized("2026-10-05", 0.60f, 0.02f, 0.95f, 0.06f),
            TextBlock.normalized("1080P 60fps", 0.05f, 0.90f, 0.4f, 0.96f),
        )
        assertEquals(MatchResult.None, Matcher.match(blocks, index))
    }

    @Test
    fun `拉丁字母与乱码不误报`() {
        val blocks = listOf(
            TextBlock.normalized("abcdefg hijklmn", 0.10f, 0.12f, 0.90f, 0.17f),
            TextBlock.normalized("###$$$%%%", 0.10f, 0.20f, 0.60f, 0.25f),
        )
        assertEquals(MatchResult.None, Matcher.match(blocks, index))
    }

    @Test
    fun `少于最短长度的块直接丢弃`() {
        val blocks = listOf(
            TextBlock.normalized("明月", 0.10f, 0.12f, 0.30f, 0.17f),
            TextBlock.normalized("西江", 0.10f, 0.30f, 0.30f, 0.35f),
        )
        assertTrue(Matcher.scanHead(blocks, index) is HeadMatch.Miss)
        assertEquals(MatchResult.None, Matcher.match(blocks, index))
    }

    @Test
    fun `两条同词牌的首句互相不串味`() {
        // 西江月有 3 条；取两条，注入错字后仍应认出各自那条
        val xi = index.verses.filter { it.pai == "西江月" }
        assertTrue("索引里西江月应有多条，便于验证歧义", xi.size >= 2)
        for (verse in xi) {
            val noisy = verse.head.substring(0, verse.head.length - 1) + "错"
            val near = index.nearest(noisy)
            assertNotNull(near)
            assertEquals(verse.pai, near!!.first.pai)
        }
    }

    @Test
    fun `与首句无关的短句不命中`() {
        val blocks = listOf(TextBlock.normalized("开始游戏", 0.35f, 0.15f, 0.65f, 0.20f))
        assertEquals(MatchResult.None, Matcher.match(blocks, index))
    }

    @Test
    fun `已选完的屏幕_仅剩词牌气泡时不误报`() {
        // 只留下三个气泡（首句已被选走/清屏）
        val blocks = Fixtures.optionBlocks("念奴娇", "西江月", "水调歌头")
        assertEquals(MatchResult.None, Matcher.match(blocks, index))
    }

    @Test
    fun `空白与空串块被忽略`() {
        val blocks = listOf(
            TextBlock.normalized("   ", 0.1f, 0.1f, 0.2f, 0.15f),
            TextBlock.normalized("", 0.1f, 0.2f, 0.2f, 0.25f),
        )
        assertTrue(Matcher.scanHead(blocks, index) is HeadMatch.Miss)
        assertNull(Matcher.scanOptions(blocks, "西江月", index))
    }

    @Test
    fun `像素坐标缺少帧尺寸时按兜底处理不崩溃`() {
        val weird = TextBlock(
            text = "明月别枝惊鹊清风半夜鸣蝉",
            space = CoordSpace.PIXELS,
            left = 10f,
            top = 200f,
            right = 900f,
            bottom = 260f,
        )
        val normalized = weird.normalized()
        assertTrue(normalized.space == CoordSpace.NORMALIZED)
        assertTrue("坐标必须被归一化到 0..1: $normalized",
            normalized.left in 0f..1f && normalized.top in 0f..1f &&
                normalized.right in 0f..1f && normalized.bottom in 0f..1f)
    }
}
