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

    // ---------------------------------------------------------------- 重叠占比判定

    @Test
    fun `合并块_一半压在自绘区上_必须被排除`() {
        // **这是原实现的漏洞**：OCR 常把状态条文字与相邻内容并成一个大块。
        // 那个大块的中心点跑到了自绘矩形之外，而旧判据只看中心点 → 整块排不掉
        // → 状态条里的词牌名仍能被 scanOptions 选中 → 框画回自己文案上并自锁。
        //
        // 自绘区 y = 0.40~0.50。块 y = 0.30~0.50 → 正好一半在里面，
        // 中心 y = 0.40（边界上）。旧判据的边界行为不可靠，新判据按面积算 = 0.5。
        val merged = TextBlock.normalized("蝶恋花", 0.30f, 0.30f, 0.70f, 0.50f)
        val r = floatArrayOf(0f, 0.40f, 1f, 0.50f)
        val ratio = FramePreprocessor.overlapRatioOf(merged.normalized(), r)
        println("    合并块重叠占比 = %.2f（阈值 %.2f）".format(ratio, Config.SELF_DRAWN_OVERLAP_RATIO))
        assertTrue(
            "一半压在自绘区上的块必须达到排除阈值，实际 %.2f".format(ratio),
            ratio >= Config.SELF_DRAWN_OVERLAP_RATIO,
        )

        // 端到端：该块被排除后，屏上只剩 卜算子，而首句判的是蝶恋花 → 找不到气泡
        val blocks = listOf(b(head, 0.19f), merged, b("卜算子", 0.43f))
        val p = pipeline()
        p.selfDrawnBounds = listOf(r)
        val out = p.matchNow(blocks, 2608, 1200, now, false, 2608, 1200, 0)
        println("    合并块场景 -> $out")
        assertTrue("合并块必须被排除，否则会框到自己文案上，实际 $out", out.startsWith("仅词牌"))
    }

    @Test
    fun `只是挨着自绘区_但大部分在区外的块_不该被误伤`() {
        // 反向保护：阈值不能太低，否则紧挨状态条的正常游戏文字会被一起剔掉 → 漏识别
        val neighbor = TextBlock.normalized("卜算子", 0.30f, 0.50f, 0.70f, 0.54f)
        val r = floatArrayOf(0f, 0.40f, 1f, 0.50f)   // 只在上边界处相接，无面积重叠
        val ratio = FramePreprocessor.overlapRatioOf(neighbor.normalized(), r)
        println("    紧邻块重叠占比 = %.2f".format(ratio))
        assertTrue("只是相接不算重叠，不该被排除", ratio < Config.SELF_DRAWN_OVERLAP_RATIO)
    }

    @Test
    fun `重叠占比函数的基本性质`() {
        // 自绘矩形：整屏宽、纵向 0.4~0.5（模拟一条顶部状态条）
        val r = floatArrayOf(0f, 0.4f, 1f, 0.5f)

        // 1) 块完全落在矩形内 → 1.0（块再小也是 1.0：占比的分母是块自身面积）
        val inside = TextBlock.normalized("x", 0.3f, 0.42f, 0.5f, 0.48f)
        assertEquals(1f, FramePreprocessor.overlapRatioOf(inside.normalized(), r), 1e-4f)

        // 2) 完全不重叠（在矩形下方）→ 0
        val below = TextBlock.normalized("x", 0.3f, 0.55f, 0.5f, 0.6f)
        assertEquals(0f, FramePreprocessor.overlapRatioOf(below.normalized(), r), 1e-4f)

        // 3) 正好相接（底边贴着矩形顶边）→ 0，不应算作重叠
        val touching = TextBlock.normalized("x", 0.3f, 0.3f, 0.5f, 0.4f)
        assertEquals(0f, FramePreprocessor.overlapRatioOf(touching.normalized(), r), 1e-4f)

        // 4) 块有一半压在矩形里 → 0.5
        //    块 y∈[0.35, 0.55]（高 0.2），与矩形 y∈[0.4, 0.5] 交叠 0.1 → 0.1/0.2
        val half = TextBlock.normalized("x", 0.3f, 0.35f, 0.5f, 0.55f)
        assertEquals(0.5f, FramePreprocessor.overlapRatioOf(half.normalized(), r), 1e-4f)

        // 5) 退化矩形（零面积）不能除零
        val degenerate = TextBlock.normalized("x", 0.3f, 0.3f, 0.3f, 0.3f)
        assertEquals(0f, FramePreprocessor.overlapRatioOf(degenerate.normalized(), r), 1e-4f)
    }
}
