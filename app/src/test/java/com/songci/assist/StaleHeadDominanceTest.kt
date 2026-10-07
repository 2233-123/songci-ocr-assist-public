package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **换题瞬间「旧首句残留」不应继续主导判定** —— 用户反馈"框停在那里、容易误触"。
 *
 * ### 真机证据（0107-114756，695 帧）
 *
 * ```
 * 段数: 命中 48 段 / 仅词牌 53 段 / 首句未匹配 7 段
 * 最长连续段: 诉衷情 43 帧 / 7.3 秒
 * ```
 *
 * 并且出现**同屏两题来回抖**（相似度都是满分，分数无法区分）：
 *
 * ```
 * 帧193 如梦令 -> 帧194 诉衷情 -> 帧195 如梦令 -> 帧196 诉衷情
 * 帧269 少年游 -> 帧270 丑奴儿 -> 帧271 少年游 -> 帧272 丑奴儿
 * ```
 *
 * ### 两行间距决定了走哪条路径（实测）
 *
 * | 两行间距 | 结果 |
 * |---|---|
 * | **0.12**（真机现场：残留 y=0.08 / 当前 y=0.19） | 位置优先生效 → 选**新题** ✅ |
 * | 0.02（人为逼近） | 被 `mergeAdjacent` 并成一组 → 位置比较失效，选旧题 |
 *
 * 0.02 那种间距在换题时不会出现（真机两行差 0.11），所以**位置优先是有效修法**；
 * 但把它记下来 —— 它说明"判定依赖两行间距"这件事本身是个脆弱点。
 */
class StaleHeadDominanceTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    /** 旧题（临江仙）：梦后楼台高锁酒醒帘幕低垂 */
    private val oldHead = "首句梦后楼台高锁酒醒帘幕低垂"

    /** 新题（浣溪沙）：小院闲窗春色深重帘未卷影沉沉 */
    private val newHead = "首句小院闲窗春色深重帘未卷"

    @Test
    fun `真实间距下_应选更靠下的当前题而不是上方残留的旧题`() {
        // 间距 0.12，与真机现场一致（残留 y=0.08 / 当前 y=0.19）
        val blocks = listOf(
            b(oldHead, 0.11f),
            b(newHead, 0.23f),
            b("婉约词情", 0.40f),
            b("临江仙", 0.43f), b("浣溪沙", 0.57f),
        )
        val head = Matcher.scanHead(blocks, index)
        println("    旧 y=0.11 / 新 y=0.23 -> $head")
        assertTrue("应命中，实际 $head", head is HeadMatch.Hit)
        assertEquals("应选当前题（更靠下）", "浣溪沙", (head as HeadMatch.Hit).verse.pai)
    }

    @Test
    fun `只有旧题残留时才允许给出旧词牌`() {
        val blocks = listOf(
            b(oldHead, 0.19f),
            b("临江仙", 0.43f), b("浣溪沙", 0.57f),
        )
        val head = Matcher.scanHead(blocks, index)
        assertTrue(head is HeadMatch.Hit)
        assertEquals("临江仙", (head as HeadMatch.Hit).verse.pai)
    }

    @Test
    fun `两题相似度都是满分时_位置是唯一可用的区分依据`() {
        val old = Matcher.scanHead(listOf(b(oldHead, 0.11f)), index)
        val new = Matcher.scanHead(listOf(b(newHead, 0.23f)), index)
        assertEquals("两题各自都应满分", 1.0, (old as HeadMatch.Hit).similarity, 1e-9)
        assertEquals(1.0, (new as HeadMatch.Hit).similarity, 1e-9)
        // 分数相同 → 只能靠位置；所以这条规则必须存在
        val both = Matcher.scanHead(
            listOf(b(oldHead, 0.11f), b(newHead, 0.23f)), index,
        )
        assertEquals(
            "分数相同时必须选更靠下的那一行",
            "浣溪沙",
            (both as HeadMatch.Hit).verse.pai,
        )
    }

    @Test
    fun `两行过近会被并成一组_这是位置比较的失效边界`() {
        // 记录这个边界：间距 0.02 时两行会被 mergeAdjacent 合并，位置比较无从生效
        val merged = Matcher.scanHead(
            listOf(b(oldHead, 0.17f), b(newHead, 0.19f)), index,
        )
        println("    间距 0.02 -> $merged")
        println("    （真机换题时两行差约 0.11，所以此边界在日常使用中不触发）")
    }
}
