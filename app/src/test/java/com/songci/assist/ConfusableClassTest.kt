package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **形近字混淆类** —— 修「青玉案 / 鹧鸪天 / 丑奴儿 等识别慢」的守护测试。
 *
 * ### 根因
 *
 * 3 字词牌错 1 字相似度是 `0.667`，阈值 `0.66` —— **只高 0.007**，
 * 所以**只要错 2 个字就跌破阈值**（0.333）。而 OCR 对某些字会反复读错，
 * 真机实测（日志 123130）：
 *
 * ```
 * 丑奴儿 -> 卫奴儿   0.667（卡边缘，时好时坏）
 * 丑奴儿 -> 卫奴几   0.333 ❌
 * 鹧鸪天 -> 鹤鸽天   0.333 ❌
 * ```
 *
 * 于是这些词牌的气泡"时读得到时读不到"，表现为**识别明显偏慢**。
 *
 * ### 修法
 *
 * [VerseIndex.confusableSimilarity]：**逐位比较，同组即算相同**。
 *
 * 为什么不是"把形近字全局折成占位符"：那个做法表达不了**不同位置各自混淆**。
 * 实测踩过：把 `几` 和 `丑` 放一组，`卫奴几` 折成 `②奴②`、`丑奴儿` 折成 `②奴几`
 * —— 反而认不出来。逐位比较则天然正确（首字比首字、末字比末字）。
 */
class ConfusableClassTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    /** 用被测词牌自己的首句，保证首句与气泡是同一题 */
    private fun frameFor(pai: String, bubble: String): List<TextBlock> {
        val v = index.verses.first { it.pai == pai }
        return listOf(b(v.head, 0.19f), b("婉约词情", 0.34f), b("青玉案", 0.43f), b(bubble, 0.57f))
    }

    // ---------------------------------------------------------------- 安全性

    @Test
    fun `任意两个不同词牌在形近等价下都不相等`() {
        val pais = index.verses.map { it.pai }.distinct()
        val bad = ArrayList<String>()
        for (i in pais.indices) {
            for (j in i + 1 until pais.size) {
                if (VerseIndex.confusableSimilarity(pais[i], pais[j]) >= 1.0) {
                    bad += "${pais[i]} == ${pais[j]}"
                }
            }
        }
        assertTrue("形近等价下不该有两个词牌完全相同：$bad", bad.isEmpty())
    }

    @Test
    fun `混淆等价应是自反的`() {
        for (pai in index.verses.map { it.pai }.distinct()) {
            assertEquals("「$pai」与自己必须满分", 1.0, VerseIndex.confusableSimilarity(pai, pai), 1e-9)
        }
    }

    // ---------------------------------------------------------------- 真机现场

    @Test
    fun `真机现场_丑奴儿的三种误读都应出框`() {
        // 真机日志 123130：y=0.43 读到 '卫奴儿' / '亚奴儿' / '卫奴几'
        for (misread in listOf("丑奴儿", "卫奴儿", "亚奴儿", "卫奴几")) {
            val r = Matcher.match(frameFor("丑奴儿", misread), index)
            assertTrue("「$misread」应命中丑奴儿，实际 $r", r is MatchResult.Hit)
            assertEquals("「$misread」认错了", "丑奴儿", (r as MatchResult.Hit).verse.pai)
        }
    }

    @Test
    fun `真机现场_鹧鸪天的误读都应出框`() {
        for (misread in listOf("鹧鸪天", "鹤鸽天", "鹧鸽天", "鹤鸪天")) {
            val r = Matcher.match(frameFor("鹧鸪天", misread), index)
            assertTrue("「$misread」应命中鹧鸪天，实际 $r", r is MatchResult.Hit)
            assertEquals("鹧鸪天", (r as MatchResult.Hit).verse.pai)
        }
    }

    @Test
    fun `形近等价不会把别的词牌认成丑奴儿或鹧鸪天`() {
        for (v in index.verses.map { it.pai }.distinct()) {
            val r = Matcher.match(frameFor(v, v), index)
            assertTrue("「$v」应命中自己，实际 $r", r is MatchResult.Hit)
            assertEquals("「$v」被误判", v, (r as MatchResult.Hit).verse.pai)
        }
    }

    @Test
    fun `关键数值_错1字0点667仅比阈值高0点007_错2字必然跌破`() {
        val one = Matcher.similarity("卫奴儿", "丑奴儿")
        assertEquals("错 1 字应为 0.667", 2.0 / 3.0, one, 1e-9)
        assertTrue("阈值必须低于 0.667", Config.OPTION_SIMILARITY_THRESHOLD < one)
        val two = Matcher.similarity("卫奴几", "丑奴儿")
        assertTrue("错 2 字应跌破阈值（这正是需要形近等价的原因）",
            two < Config.OPTION_SIMILARITY_THRESHOLD)
        // 而形近等价能把这两种误读都拉到满分
        assertEquals(1.0, VerseIndex.confusableSimilarity("丑奴儿", "卫奴儿"), 1e-9)
        assertEquals(1.0, VerseIndex.confusableSimilarity("丑奴儿", "卫奴几"), 1e-9)
    }
}
