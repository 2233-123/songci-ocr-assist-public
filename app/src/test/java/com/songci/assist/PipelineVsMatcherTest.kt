package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真机上真正跑的那个方法**（`FramePipeline.evaluate`，而不是 `Matcher.match`）
 * 复现「气泡就在屏上却报未找到」。
 *
 * 之前我在 [OptionBubblePresenceTest] 里直接调 `Matcher.match`，三个用例全过 ——
 * 但真机日志明确显示 App 报「未找到气泡」。所以差异一定在 `FramePipeline` 这一层：
 * 它多做了 `Matcher.topTextSample`、冷却缓存、`midArea` 过滤这几步。
 *
 * 数据来自 v0.8.2 真机日志 0107-014003 第 41 帧（App 报「未找到气泡 破阵子」）。
 */
class PipelineVsMatcherTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    private val frame41 = listOf(
        b("词牌", 0.06f), b("词元23736", 0.06f), b("保律", 0.08f),
        b("择律收益642", 0.10f), b("属性", 0.10f),
        b("应选破阵子屏上没找到选项气泡", 0.11f), b("N04", 0.16f),
        b("首句子来时新社梨花落后清明", 0.19f), b("豪放词情", 0.23f), b("60", 0.27f),
        b("婉约词情", 0.34f), b("苏幕遮", 0.43f), b("江城子", 0.44f),
        b("破阵子", 0.57f), b("暮烟", 0.75f),
    )

    private class FakeOcr : OcrEngine {
        override fun recognize(frameWidth: Int, frameHeight: Int): OcrFrameResult =
            OcrFrameResult(emptyList(), frameWidth, frameHeight)
    }

    private fun pipeline() = FramePipeline(
        ocr = FakeOcr(),
        index = { index },
        clock = { 0L },
    )

    @Test
    fun `对比_Matcher点match 与 FramePipeline点evaluate 在同一批块上的结论`() {
        val m = Matcher.match(frame41, index)
        val p = pipeline().evaluate(frame41, 2608, 1200, now = 0L, inCooldown = false)
        println("    Matcher.match            -> $m")
        println("    FramePipeline.evaluate   -> $p")
        assertEquals(
            "两条路径对同一批块应给出一致结论",
            m is MatchResult.Hit,
            p is FrameOutcome.Hit,
        )
    }

    @Test
    fun `Pipeline 在气泡存在时应给出 Hit`() {
        val p = pipeline().evaluate(frame41, 2608, 1200, now = 0L, inCooldown = false)
        println("    -> $p")
        assertTrue(
            "破阵子气泡在 y=0.57（选项区内），Pipeline 不该判 PaiOnly；实际 $p",
            p is FrameOutcome.Hit,
        )
    }

    @Test
    fun `冷却期也不该把 Hit 退化成 PaiOnly`() {
        val pl = pipeline()
        // 先跑一次（缓存 PaiOnly 或 Hit）
        val first = pl.evaluate(frame41, 2608, 1200, now = 0L, inCooldown = false)
        // 再在冷却期跑一次
        val second = pl.evaluate(frame41, 2608, 1200, now = 100L, inCooldown = true)
        println("    第一次 -> $first")
        println("    冷却期 -> $second")
        assertEquals("冷却期复用的应是同结论", first::class, second::class)
    }

    /**
     * 假设：**调试面板的噪声块把「唯一最优」的 margin 挤掉了**。
     *
     * `scanOptions` 的模糊分支要求「第一名 - 第二名 ≥ 0.15」，否则宁可不出框。
     * 用户做测试时开着调试面板，而面板下半部分（y≈0.30~0.35）**正好落在选项区
     * [MID_CROP_TOP, MID_CROP_BOTTOM] = 0.30~0.80 内**，于是往候选里塞进一批
     * 与词牌同长度、相似度不低的噪声块 —— 一旦它们把第二名顶到 0.15 以内，
     * 正确气泡就会被拒绝。
     *
     * 这里用真机日志里出现过的面板文本（都是 4 字、恰好与「破阵子」同长度）构造。
     */
    @Test
    fun `调试面板噪声块落在选项区时_是否会挤掉正确气泡`() {
        val withNoise = frame41 + listOf(
            // 真机日志里出现过的 4 字块，y 落在选项区上沿
            b("豪放词情", 0.31f), b("婉约词情", 0.33f), b("词牌楼阁", 0.35f),
            b("词元23736", 0.32f), b("择律收益", 0.34f),
        )
        val mid = FramePreprocessor.midArea(withNoise)
        val target = Matcher.scanOptions(mid, "破阵子", index)
        println("    加噪声后 scanOptions(中区) -> $target")
        println("    中区块 = ${mid.map { "%.2f:%s".format(it.normalized().centerY, it.text) }}")
        // 破阵子本身是精确等值，第一优先就该命中 —— 若这里为 null，说明模糊分支
        // 的 margin 逻辑影响到了精确分支（或者噪声里混进了同长度的"破阵子"）
        assertTrue(
            "破阵子在块里是精确等值，scanOptions 不该返回 null；实际 $target",
            target != null,
        )
    }
}
