package com.songci.assist

import org.junit.Test

/**
 * **App 自己画的文字被 OCR 读回来，会不会被当成气泡？**
 *
 * 真机日志里反复出现这种"自产文本"：
 *
 * ```
 * y=0.11  应选雨霖铃屏上没找到选项气泡
 * y=0.56  匹配:浪淘沙          ← 调试面板
 * y=0.61  状态:认出词
 * ```
 *
 * 面板/状态条是 App 自己画在屏上的，而 OCR 会把它们读成普通文本块。
 * 如果这些块**恰好落在选项区（0.30~0.80）**，`scanOptions` 就可能把它们
 * 当成气泡 —— 那样画出来的框会落在面板上，而不是游戏气泡上。
 *
 * 更麻烦的是：`OPTION_SIMILARITY_MARGIN` 要求"唯一最优"，
 * 这些自产文本可能把正确气泡的 margin 挤掉。
 *
 * 本测试量化这个风险，而不是靠猜。
 */
class SelfTextInterferenceTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    @Test
    fun `App自产的应选文本落在选项区时_会不会被当成气泡`() {
        // 状态条文案：应选XX（屏上没找到选项气泡）
        // 它本身较长，但不是词牌名，先看会不会被命中
        val blocks = listOf(
            b("首句寒蝉凄切对长亭晚骤雨", 0.19f),
            // 把状态条文本放在选项区里（真机 y=0.11 时在区外，这里测试"万一落进去"）
            b("应选雨霖铃屏上没找到选项气泡", 0.44f),
            b("少年游", 0.57f),
        )
        val r = Matcher.match(blocks, index)
        println("    [自产状态条落在选项区] -> $r")
        if (r is MatchResult.Hit) {
            println("    ★ 被判为气泡！框会落在自产文字上，target=${r.target}")
        }
    }

    @Test
    fun `调试面板文本落在选项区时_会不会被当成气泡`() {
        val blocks = listOf(
            b("首句寒蝉凄切对长亭晚骤雨", 0.19f),
            b("匹配:雨霖铃", 0.44f),
            b("状态:认出词牌", 0.57f),
        )
        val r = Matcher.match(blocks, index)
        println("    [面板文本落在选项区] -> $r")
        if (r is MatchResult.Hit) {
            println("    ★ 被判为气泡！target=${r.target}")
        }
    }

    @Test
    fun `自产文本会不会把正确气泡的唯一最优挤掉`() {
        // 正确气泡「鹧鸪天」在 y=0.57；同时在选项区塞入两个形近的自产文本
        val withNoise = listOf(
            b("首句重过阊门万事非同来何事不同归", 0.19f),
            b("匹配:鹧鸪天", 0.43f),
            b("状态:认出词牌鹧鸪天", 0.44f),
            b("鹧鸪天", 0.57f),
        )
        val r1 = Matcher.match(withNoise, index)
        val r2 = Matcher.match(
            listOf(
                b("首句重过阊门万事非同来何事不同归", 0.19f),
                b("鹧鸪天", 0.57f),
            ),
            index,
        )
        println("    有自产噪声 -> $r1")
        println("    无自产噪声 -> $r2")
    }
}
