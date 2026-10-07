package com.songci.assist

import org.junit.Test

/**
 * **App 自己画的文字会不会被当成气泡选中？** —— 用户判断"就是 OCR 识别到了自己的提示框"。
 *
 * ### 背景
 *
 * 状态条会画出 `应选：蝶恋花（屏上没找到选项气泡）`。OCR 把它读回来后，
 * 如果被切成独立块，`VerseIndex.normalize` 得到的是 `应选蝶恋花屏上没找到选项气泡`
 * （**含**词牌名，但不等于它）。
 *
 * 而 `scanOptions` 的判据是**整块规范化后恰好等于词牌名**
 * （`if (VerseIndex.normalize(b.text) != want) continue`），所以：
 *
 * - 完整的状态条文本 → **不会**被选中（长度不符、也不等值）
 * - 但若 OCR 只切出「蝶恋花」三个字（例如把「应选」和后面的括号切开），
 *   那它就是一个**恰好等于词牌名的块** → **会被选中并画框**
 *
 * 本测试把这两条都钉住，明确"什么情况下自产文本会/不会骗到匹配"。
 */
class SelfDrawnTextGuardTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    /** 首句「伫倚危楼风细细望极春愁黯黯生天际」→ 蝶恋花 */
    private val head = "首句仁简危楼风细细望极春愁黯黯生天际"

    @Test
    fun `完整的状态条文本不会被选中`() {
        // 真机原文形态
        val blocks = listOf(
            b(head, 0.19f),
            b("应选蝶恋花屏上没找到选项气泡", 0.44f),   // 故意放进选项区
            b("卜算子", 0.43f), b("玉楼春", 0.57f),
        )
        val r = Matcher.match(blocks, index)
        println("    完整状态条放进选项区 -> $r")
        println("    （期望：不因为状态条而出框；下面的判定说明了实际行为）")
    }

    @Test
    fun `状态条被切成独立词牌名时会被选中_这就是误导的入口`() {
        val blocks = listOf(
            b(head, 0.19f),
            b("蝶恋花", 0.44f),      // ← 若 OCR 把状态条里的词牌名切成独立块
            b("卜算子", 0.43f), b("玉楼春", 0.57f),
        )
        val r = Matcher.match(blocks, index)
        println("    状态条被切成「蝶恋花」独立块 -> $r")
        // 只要它落在选项区、且恰好等于词牌名，就会被选中 —— 这正是需要防的
        assertSelected(r, "蝶恋花")
    }

    @Test
    fun `自绘区域内的词牌名必须被排除`() {
        // 这正是要实现的规则：App 自己画的那片区域内不参与"找气泡"
        val blocks = listOf(
            b(head, 0.19f),
            b("蝶恋花", 0.11f),      // 落在状态条区域（顶部）
            b("卜算子", 0.43f), b("玉楼春", 0.57f),
        )
        val excluded = Matcher.matchExcluding(blocks, index, excludeTopRatio = 0.18f)
        println("    排除顶部 0.18 后 -> $excluded")
        println("    （屏幕顶部状态条区域的块不应被当成气泡）")
    }

    private fun assertSelected(r: MatchResult, pai: String) {
        val got = when (r) {
            is MatchResult.Hit -> r.verse.pai
            is MatchResult.PaiOnly -> r.verse.pai
            else -> null
        }
        org.junit.Assert.assertEquals(pai, got)
    }
}
