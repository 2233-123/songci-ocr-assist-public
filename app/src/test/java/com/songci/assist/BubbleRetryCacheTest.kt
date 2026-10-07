package com.songci.assist

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「开调试面板很快、关掉很慢」的真正根因** —— 缓存把"没找到气泡"当成结论复用了。
 *
 * ### 用户观察
 *
 * 「开启调试面板后，锁定速度非常快，但是关闭后就很慢。」
 *
 * ### 根因（`FramePipeline`）
 *
 * ```kotlin
 * // matchNow / onFrame
 * val inCooldown = !debug && !shouldRunOptionsStage(frameAt)
 * ```
 *
 * `shouldRunOptionsStage` = `now - lastHitAt >= HIT_COOLDOWN_MS`（3000ms）。
 * 于是**调试面板开着（debug=true）时 `inCooldown` 永远为 false** —— 每帧都重新扫气泡；
 * 关掉时，命中后 3 秒内 `inCooldown=true`，`evaluateInternal` 直接返回缓存：
 *
 * ```kotlin
 * if (inCooldown && cached != null && cachedVerseId == head.verse.id) {
 *     return Evaluated(cached, fromCache = true, ...)   // ← 把上次的 PaiOnly 原样返回
 * }
 * ```
 *
 * 而缓存里存的可能是 **`PaiOnly`（"没找到气泡"）** —— 那本是一个**需要重试的失败结论**，
 * 却因为"省电"被当作结论复用了 3 秒。
 *
 * 这正好解释了真机日志里每题约 2.5~3.2 秒的固定等待（= 冷却期长度），
 * 也解释了为什么开面板就快（debug 短路掉了冷却）。
 *
 * ### 修法
 *
 * **只复用"命中"结论，不复用"没命中"** —— 后者本来就是要去重试的状态。
 * 冷却的意义（同一局结果不变，省一次扫描）只对成功结论成立。
 */
class BubbleRetryCacheTest {

    private val index = Fixtures.index()
    private var now = 1_000_000L

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    private class FakeOcr : OcrEngine {
        override fun recognize(frameWidth: Int, frameHeight: Int): OcrFrameResult =
            OcrFrameResult(emptyList(), frameWidth, frameHeight)
    }

    private fun pipeline() = FramePipeline(ocr = FakeOcr(), index = { index }, clock = { now })

    /** 首句（判为浣溪沙）+ 不含气泡的块 */
    private fun headOnly() = listOf(
        b("首句小院闲窗春色深重帘未卷影沉沉", 0.19f),
        b("婉约词情", 0.34f),
        b("豪放词情", 0.23f),
    )

    /** 同上，但气泡已被 OCR 读到 */
    private fun headAndBubble() = headOnly() + listOf(b("浣溪沙", 0.57f))

    @Test
    fun `首帧只认出首句_随后气泡出现_冷却期内也必须能出框`() {
        val p = pipeline()

        // 第 1 帧：只有首句，没有气泡 → 应判 PaiOnly（此时"未找到"是合理的）
        val first = p.matchNow(headAndBubble().let { headOnly() }, 2608, 1200, now, false, 2608, 1200, 0)
        println("    第1帧（无气泡）-> $first")
        assertTrue("应认出词牌但找不到气泡，实际 $first", first.startsWith("仅词牌"))

        // 第 2 帧：气泡读到了。**仍在冷却期内**（远小于 HIT_COOLDOWN_MS=3000）
        now += 180
        val second = p.matchNow(headAndBubble(), 2608, 1200, now, false, 2608, 1200, 0)
        println("    第2帧（有气泡，间隔180ms）-> $second")
        assertTrue(
            "气泡已读到，冷却期不该把它挡回去（缓存复用了『没找到』）。实际 $second",
            second.startsWith("命中"),
        )
    }

    @Test
    fun `连续多帧都读到气泡时_不应出现仅词牌与命中交替`() {
        val p = pipeline()
        p.matchNow(headOnly(), 2608, 1200, now, false, 2608, 1200, 0)
        val results = ArrayList<String>()
        repeat(6) {
            now += 180
            results += p.matchNow(headAndBubble(), 2608, 1200, now, false, 2608, 1200, 0)
        }
        println("    连续 6 帧 -> $results")
        assertTrue(
            "读到气泡后不该再退回『仅词牌』，实际 $results",
            results.all { it.startsWith("命中") },
        )
    }

    @Test
    fun `debug 开关不应改变同一批块的结论`() {
        val p1 = pipeline()
        val a = p1.matchNow(headAndBubble(), 2608, 1200, now, false, 2608, 1200, 0)
        val p2 = pipeline()
        p2.matchNow(headOnly(), 2608, 1200, now, false, 2608, 1200, 0)
        now += 180
        val withDebug = p2.matchNow(headAndBubble(), 2608, 1200, now, true, 2608, 1200, 0)
        now += 180
        val p3 = pipeline()
        p3.matchNow(headOnly(), 2608, 1200, now, false, 2608, 1200, 0)
        now += 180
        val withoutDebug = p3.matchNow(headAndBubble(), 2608, 1200, now, false, 2608, 1200, 0)
        println("    debug=true  -> $withDebug")
        println("    debug=false -> $withoutDebug")
        assertTrue(
            "同一批块在 debug 开/关下结论必须一致，实际 [$withDebug] vs [$withoutDebug]",
            withDebug.startsWith("命中") == withoutDebug.startsWith("命中"),
        )
    }
}
