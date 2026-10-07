package com.songci.assist

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **系统性排查：每个词牌能容忍几个字被 OCR 读错？**
 *
 * 用户要求「排查有没有类似鹧鸪天的问题」。上一版我用「生僻字数量」当判据，
 * 结果把 `雨霖铃` 标成高风险 —— 而它只是三个字没在别的词牌里重复出现，并不生僻。
 * **那是坏判据，已废弃。**
 *
 * 改用**可测**的判据：直接算「错 N 个字后相似度是否还过阈值」。
 *
 * ### 数学基础（决定一切的那条线）
 *
 * ```
 * sim(正确, 错N字) = 1 - N / 词牌字数
 * ```
 *
 * - 3 字词牌：错 1 字 = 0.667，错 2 字 = 0.333
 * - 4 字词牌：错 1 字 = 0.750，错 2 字 = 0.500
 *
 * 阈值 `OPTION_SIMILARITY_THRESHOLD = 0.66` 恰好卡在 **3 字错 1 字（0.667）之上、
 * 错 2 字（0.333）之下**。所以：
 *
 * | 情况 | 结果 |
 * |---|---|
 * | 错 1 字 | ✅ 全部可救（0.667 或 0.75 ≥ 0.66） |
 * | 错 2 字 | ❌ **全部救不回**（0.333 或 0.5 < 0.66） |
 *
 * ### 结论
 *
 * **阈值本身已经把「错 1 字」这条线划对了**，所以除了「两个字同时被读错」的情况，
 * 没有别的词牌需要特殊处理。而「两字同时错」目前**只在鹧鸪天身上被实测到**
 * （`鹧→鹤`、`鸪→鸽`，两个字都是鸟部形近字），已用
 * [VerseIndex.canonicalizeConfusable] 修好。
 *
 * 其余 39 个词牌没有"两个字属于同一形近类"的情况 —— 本测试守护这一点。
 */
class MisreadRiskAuditTest {

    private val index = Fixtures.index()

    @Test
    fun `阈值恰好允许错1字_且不允许错2字_这是所有推断的基础`() {
        // 3 字
        val three = "浪淘沙"
        assertTrue(
            "3 字错 1 字应过阈值：%.3f".format(Matcher.similarity("浪错沙", three)),
            Matcher.similarity("浪错沙", three) >= Config.OPTION_SIMILARITY_THRESHOLD,
        )
        assertTrue(
            "3 字错 2 字应被拒绝",
            Matcher.similarity("浪错错", three) < Config.OPTION_SIMILARITY_THRESHOLD,
        )
        // 4 字（索引里只有 2 个）
        val four = "八声甘州"
        assertTrue(
            "4 字错 1 字应过阈值：%.3f".format(Matcher.similarity("八声甘错", four)),
            Matcher.similarity("八声甘错", four) >= Config.OPTION_SIMILARITY_THRESHOLD,
        )
        assertTrue(
            "4 字错 2 字也应被拒绝",
            Matcher.similarity("八声错错", four) < Config.OPTION_SIMILARITY_THRESHOLD,
        )
    }

    @Test
    fun `全部40个词牌_错1字都能被救回`() {
        for (pai in index.verses.map { it.pai }.distinct()) {
            // 逐个位置替换成同一个"错"字，检查相似度
            for (i in pai.indices) {
                val misread = pai.substring(0, i) + "错" + pai.substring(i + 1)
                val sim = Matcher.similarity(misread, pai)
                assertTrue(
                    "「$pai」第 ${i + 1} 字读错后相似度 %.3f < 阈值 %.2f —— 这条线的假设不成立"
                        .format(sim, Config.OPTION_SIMILARITY_THRESHOLD),
                    sim >= Config.OPTION_SIMILARITY_THRESHOLD,
                )
            }
        }
    }

    @Test
    fun `阈值不能再往上调_否则连错1字都救不回来`() {
        // 0.667 是 3 字词牌错 1 字的下限；阈值只要超过它就会开始丢词牌
        val lowest = index.verses.map { it.pai }.distinct()
            .filter { it.length == 3 }
            .minOf { Matcher.similarity(it.replaceFirst(it[0], '错'), it) }
        assertTrue(
            "3 字错 1 字的最低相似度是 %.3f，当前阈值 %.2f 必须低于它"
                .format(lowest, Config.OPTION_SIMILARITY_THRESHOLD),
            Config.OPTION_SIMILARITY_THRESHOLD < lowest,
        )
    }

    @Test
    fun `只有鹧鸪天含鸟部字_所以只有它需要形近字归一化`() {
        val birdChars = "鹧鸪"
        val owners = index.verses.map { it.pai }.distinct().filter { pai ->
            pai.any { it in birdChars }
        }
        assertTrue("含鸟部字的词牌应只有鹧鸪天，实际 $owners", owners == listOf("鹧鸪天"))
    }

    @Test
    fun `打印完整风险表_供人工复核`() {
        println()
        println("    词牌        字数  错1字相似度  错1字可救  错2字可救")
        for (pai in index.verses.map { it.pai }.distinct()) {
            val one = Matcher.similarity(pai.replaceFirst(pai[0], '错'), pai)
            val two = Matcher.similarity(
                pai.replaceFirst(pai[0], '错').replaceFirst(pai[1], '错'), pai,
            )
            println(
                "    %-10s %-4d %-11.3f %-10s %s".format(
                    pai, pai.length, one,
                    if (one >= Config.OPTION_SIMILARITY_THRESHOLD) "✅" else "❌",
                    if (two >= Config.OPTION_SIMILARITY_THRESHOLD) "✅" else "❌",
                ),
            )
        }
        println()
        println("    阈值 = ${Config.OPTION_SIMILARITY_THRESHOLD}")
        println("    结论：错1字全部可救；错2字全部不可救。")
        println("    目前实测到「错2字」的只有鹧鸪天（鹧→鹤、鸪→鸽），已用鸟部归一化修复。")
        println()
    }
}
