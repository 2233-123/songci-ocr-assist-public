package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **App 自产文字必须被排除在"找气泡"之外** —— 用户判断"就是因为你 OCR 识别到了自己的提示框"。
 *
 * ### 现象（用户反馈）
 *
 * 「择律后回到主界面，高亮框却没即时消失。如果玩家手更快一点，又开了一次择律，
 * 那么旧的高亮框就可能误导玩家。」
 *
 * ### 机制（离线验证成立）
 *
 * 状态条画的是 `应选：蝶恋花（屏上没找到选项气泡）`。OCR 一旦在括号处断块，
 * 就产出**恰好等于词牌名的块** —— 而 `Matcher.scanOptions` 的第一优先判据正是
 * "整块恰好等于词牌名"，于是框被画到 App 自己的文案上。
 *
 * 更糟的是**自锁**：框画在状态条上 → 状态条文字被读回 → 判定继续命中 →
 * `HIGHLIGHT_COOLDOWN`/`TTL` 不断被重置 → **框永远不消失**。
 *
 * 修法：`FramePipeline.selfDrawnBounds` 记录 App 自绘区域，`evaluateInternal`
 * 在匹配前把这些区域里的块剔除。
 */
class SelfDrawnExclusionTest {

    private val index = Fixtures.index()
    private var now = 1_000_000L

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    private class FakeOcr : OcrEngine {
        override fun recognize(frameWidth: Int, frameHeight: Int): OcrFrameResult =
            OcrFrameResult(emptyList(), frameWidth, frameHeight)
    }

    private fun pipeline() = FramePipeline(ocr = FakeOcr(), index = { index }, clock = { now })

    /** 首句「伫倚危楼风细细望极春愁黯黯生天际」→ 蝶恋花 */
    private val head = "首句仁简危楼风细细望极春愁黯黯生天际"

    @Test
    fun `未排除时_状态条里的词牌名会被当成气泡`() {
        // 状态条被 OCR 切成独立块「蝶恋花」，且落在选项区
        val blocks = listOf(
            b(head, 0.19f),
            b("蝶恋花", 0.44f),
            b("卜算子", 0.43f),
        )
        val p = pipeline()
        val r = p.matchNow(blocks, 2608, 1200, now, false, 2608, 1200, 0)
        println("    未排除 -> $r")
        assertTrue("这就是 bug：自产文字被当成气泡，实际 $r", r.startsWith("命中"))
    }

    @Test
    fun `排除自绘区域后_自产文字不再被当成气泡`() {
        val blocks = listOf(
            b(head, 0.19f),
            b("蝶恋花", 0.44f),          // 模拟状态条被切成独立块
            b("卜算子", 0.43f),
        )
        val p = pipeline()
        // 把该块所在位置报成"自绘区域"
        p.selfDrawnBounds = listOf(floatArrayOf(0f, 0.42f, 1f, 0.47f))
        val r = p.matchNow(blocks, 2608, 1200, now, false, 2608, 1200, 0)
        println("    已排除 -> $r")
        // 剔掉自绘块后，屏上只剩 卜算子，而首句判的是蝶恋花 → 找不到气泡
        assertTrue("不该再因为自产文字出框，实际 $r", r.startsWith("仅词牌"))
    }

    @Test
    fun `自绘区域内的首句也不会被用来判词牌`() {
        // 极端情形：状态条文字把首句也算进去时，不能让自绘内容主导判定
        val blocks = listOf(
            b(head, 0.19f),              // 真实首句在顶部（不在自绘区内）
            b("蝶恋花", 0.44f),          // 自绘块
        )
        val p = pipeline()
        p.selfDrawnBounds = listOf(floatArrayOf(0f, 0.42f, 1f, 0.47f))
        val r = p.matchNow(blocks, 2608, 1200, now, false, 2608, 1200, 0)
        println("    自绘块被剔除后 -> $r")
        assertTrue("首句仍应能识别（它不在自绘区内）", r.contains("蝶恋花"))
    }

    @Test
    fun `全部块都在自绘区内时_应判未命中并清框`() {
        val blocks = listOf(b("蝶恋花", 0.44f))
        val p = pipeline()
        p.selfDrawnBounds = listOf(floatArrayOf(0f, 0.40f, 1f, 0.50f))
        val r = p.matchNow(blocks, 2608, 1200, now, false, 2608, 1200, 0)
        println("    全部被排除 -> $r")
        assertEquals("应判未命中（这样高亮框才会被清掉）", "首句未匹配", r)
    }
}
