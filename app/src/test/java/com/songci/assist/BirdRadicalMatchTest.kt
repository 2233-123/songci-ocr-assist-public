package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **鹧鸪天 永远找不到气泡** —— 真机实测的根因与回归测试。
 *
 * ### 现象
 *
 * 用户反馈：「选项找鹧鸪天的时候，基本没有找到过选项。」
 * 日志核实：在所有「应选=鹧鸪天」的帧里，`鹧鸪天` 这三个字**从未出现在任何 OCR 块中**。
 *
 * ### 根因
 *
 * 气泡其实**读到了**（`y=0.57`，落在选项区 0.30~0.80 内），但**三个字错了两个**：
 *
 * ```
 * 正确   鹧 鸪 天
 * OCR    鹤 鸽 天
 * ```
 *
 * `鹧→鹤`、`鸪→鸽` 都是**鸟部形近字**混淆。而相似度按编辑距离算：
 *
 * ```
 * sim(鹧鸪天, 鹤鸽天) = 1 - 2/3 = 0.333   << 阈值 0.66
 * ```
 *
 * 所以模糊匹配也救不回来 —— **必须两个都改对才行**，而两个字都读错的概率不低。
 *
 * ### 修法
 *
 * 40 个词牌里，鸟部字**只出现在「鹧鸪天」**（已用索引校验）。
 * 所以把鸟部形近字统一归一化成一个占位符再比较：
 *
 * ```
 * 鹧鸪天 -> ●●天      鹤鸽天 -> ●●天      -> 命中
 * ```
 *
 * 归一化后 40 个词牌**零碰撞**，且只有「鹧鸪天」的名称会变 —— 安全性由数据保证。
 */
class BirdRadicalMatchTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    /** 用被测词牌**自己的首句**，配合给定的气泡文本 —— 避免首句与气泡不是同一题 */
    private fun frameFor(pai: String, bubbleText: String): List<TextBlock> {
        val v = index.verses.first { it.pai == pai }
        return listOf(
            b(v.head, 0.19f),
            b("婉约词情", 0.34f),
            b("八声甘州", 0.43f),
            b(bubbleText, 0.57f),
        )
    }

    @Test
    fun `真机现场_鹤鸽天 应能匹配到鹧鸪天`() {
        // 真机日志原文：y=0.57:'鹤鸽天'
        val r = Matcher.match(frameFor("鹧鸪天", "鹤鸽天"), index)
        println("    [鹤鸽天] -> $r")
        assertTrue("应命中并画出鹧鸪天的框，实际 $r", r is MatchResult.Hit)
        assertEquals("鹧鸪天", (r as MatchResult.Hit).verse.pai)
    }

    @Test
    fun `只错一个字的各种变体都应命中`() {
        for (t in listOf("鹧鸪天", "鹤鸽天", "鹧鸽天", "鹤鸪天", "鹊鸪天", "鹧鸪天")) {
            val r = Matcher.match(frameFor("鹧鸪天", t), index)
            assertTrue("「$t」应命中鹧鸪天，实际 $r", r is MatchResult.Hit)
            assertEquals("「$t」认错了", "鹧鸪天", (r as MatchResult.Hit).verse.pai)
        }
    }

    @Test
    fun `形近等价下40个不同词牌两两都不等价`() {
        // 「归一化后零碰撞」的等价表述 —— 现在用逐位形近等价（见 ConfusableClassTest），
        // 所以判据是：任意两个不同词牌的形近相似度都不能满分。
        val distinctPais = index.verses.map { it.pai }.distinct()
        assertEquals("索引里应有 40 个不同的词牌", 40, distinctPais.size)
        for (i in distinctPais.indices) {
            for (j in i + 1 until distinctPais.size) {
                assertTrue(
                    "「${distinctPais[i]}」与「${distinctPais[j]}」形近等价了 —— 修法不再安全",
                    VerseIndex.confusableSimilarity(distinctPais[i], distinctPais[j]) < 1.0,
                )
            }
        }
    }

    @Test
    fun `形近等价只覆盖预期的词牌_且不产生串牌`() {
        // 这条替代旧的「只有鹧鸪天会被改动」断言 —— 归一化已改为**逐位等价比较**
        // （见 ConfusableClassTest），"改动名称"不再是判据。
        // 这里改为验证：只有含形近字的词牌会受益，其余词牌之间不会互相等价。
        val affected = listOf("鹧鸪天", "丑奴儿")
        for (pai in index.verses.map { it.pai }.distinct()) {
            if (pai in affected) continue
            // 不含形近字的词牌：与自己之外任何词牌的形近相似度都不该满分
            for (other in index.verses.map { it.pai }.distinct()) {
                if (other == pai) continue
                assertTrue(
                    "「$pai」与「$other」不该形近等价",
                    VerseIndex.confusableSimilarity(pai, other) < 1.0,
                )
            }
        }
    }

    @Test
    fun `归一化不应把别的词牌误判成鹧鸪天`() {
        // 反例检查：其它 39 个词牌「自己的首句 + 自己的气泡名」都必须认回自己
        for (v in index.verses) {
            if (v.pai == "鹧鸪天") continue
            val r = Matcher.match(frameFor(v.pai, v.pai), index)
            assertTrue("「${v.pai}」应命中自己，实际 $r", r is MatchResult.Hit)
            assertEquals(
                "「${v.pai}」被误判成别的词牌",
                v.pai,
                (r as MatchResult.Hit).verse.pai,
            )
        }
    }
}
