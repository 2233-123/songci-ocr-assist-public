package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **真机日志暴露的矛盾**：App 报「仅词牌 X（未找到气泡）」，但同一帧的 `frame.ocr`
 * 明细里，X 的气泡**明明被读到了、坐标也在选项区内**。
 *
 * ### 现场数据（v0.8.2 真机日志 0107-014003）
 *
 * 「仅词牌 如梦令」段（帧48~56）逐帧统计：
 *
 * ```
 * 帧48  真词牌块: （无）
 * 帧49  （无）
 * 帧50  ['如梦令']    ← 气泡在屏上
 * 帧51  ['如梦令']
 * 帧52  ['如梦令']
 * 帧53  ['如梦令','定风波']
 * 帧54  ['如梦令']
 * 帧55  ['如梦令']
 * ```
 *
 * 连续 6 帧读到正确气泡，App 却一路报「未找到气泡」，直到帧 57 才出框 —— 白等 2.8 秒。
 *
 * 第 41 帧的完整块（App 报「未找到气泡 破阵子」）：
 *
 * ```
 * y=0.19:首句子来时新社梨花落后清明
 * y=0.43:苏幕遮 ｜ y=0.44:江城子 ｜ y=0.57:破阵子    ← 三个气泡都在
 * ```
 *
 * 本测试把这个矛盾钉住：只要块里存在正确气泡、坐标又在区内，
 * [Matcher.scanOptions] 就**必须**找得到。
 */
class OptionBubblePresenceTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    /** 第 41 帧的原始块（真机日志原文） */
    private val frame41 = listOf(
        b("词牌", 0.06f), b("词元23736", 0.06f), b("保律", 0.08f),
        b("择律收益642", 0.10f), b("属性", 0.10f),
        b("应选破阵子屏上没找到选项气泡", 0.11f), b("N04", 0.16f),
        b("首句子来时新社梨花落后清明", 0.19f), b("豪放词情", 0.23f), b("60", 0.27f),
        b("婉约词情", 0.34f), b("苏幕遮", 0.43f), b("江城子", 0.44f),
        b("破阵子", 0.57f), b("暮烟", 0.75f),
    )

    @Test
    fun `第41帧_破阵子气泡在屏上且坐标在区内_应该能画框`() {
        val r = Matcher.match(frame41, index)
        println("    [帧41] -> $r")
        // 首句应是破阵子
        val head = when (r) {
            is MatchResult.Hit -> r.verse.pai
            is MatchResult.PaiOnly -> r.verse.pai
            else -> null
        }
        assertEquals("首句应判定为破阵子", "破阵子", head)
        assertTrue(
            "气泡「破阵子」就在块里(y=0.57，落在 0.30~0.80)，不该报『未找到气泡』；实际 $r",
            r is MatchResult.Hit,
        )
    }

    @Test
    fun `气泡块独立存在时_必须命中`() {
        // 最小复现：只有首句 + 正确气泡
        val blocks = listOf(
            b("首句子来时新社梨花落后清明", 0.19f),
            b("苏幕遮", 0.43f), b("江城子", 0.44f), b("破阵子", 0.57f),
        )
        val r = Matcher.match(blocks, index)
        println("    [最小复现] -> $r")
        assertTrue("应命中并画框，实际 $r", r is MatchResult.Hit)
        assertEquals("破阵子", (r as MatchResult.Hit).verse.pai)
    }

    @Test
    fun `帧53的情形_如梦令与定风波同时在屏_应命中如梦令`() {
        val blocks = listOf(
            b("首句为向东坡传语人在玉堂深处", 0.19f),
            b("婉约词情", 0.34f), b("如梦令", 0.44f), b("定风波", 0.57f),
        )
        val r = Matcher.match(blocks, index)
        println("    [帧53] -> $r")
        assertTrue("应命中并画框，实际 $r", r is MatchResult.Hit)
        assertEquals("如梦令", (r as MatchResult.Hit).verse.pai)
    }
}
