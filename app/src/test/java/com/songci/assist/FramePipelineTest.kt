package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 帧流水线的节流与两段式调度（纯逻辑，不需要设备）。
 *
 * OCR 用假实现（[FramePipeline.evaluate] 直接吃文本块，不经过引擎）；
 * 时钟用可变值手工推进，避免依赖真实时间。
 */
class FramePipelineTest {

    private val index = Fixtures.index()

    /** 只用于构造流水线；本测试走 [FramePipeline.evaluate]，不调用引擎 */
    private class FakeOcr : OcrEngine {
        override fun recognize(frameWidth: Int, frameHeight: Int): OcrFrameResult =
            OcrFrameResult(emptyList(), frameWidth, frameHeight)
    }

    private var now = 1_000_000L
    private fun pipeline(): FramePipeline =
        FramePipeline(ocr = FakeOcr(), index = { index }, clock = { now })

    @Test
    fun `空帧返回 Empty`() {
        assertEquals(FrameOutcome.Empty, pipeline().evaluate(emptyList(), 1080, 2400, now))
    }

    @Test
    fun `索引未加载完时不报错`() {
        val p = FramePipeline(ocr = FakeOcr(), index = { null }, clock = { now })
        val blocks = listOf(TextBlock.normalized(Fixtures.HEAD_TEXT, 0.08f, 0.11f, 0.92f, 0.16f))
        assertEquals(FrameOutcome.Empty, p.evaluate(blocks, 1080, 2400, now))
    }

    @Test
    fun `实机截图一屏得到 Hit 且指向第二个气泡`() {
        val outcome = pipeline().evaluate(Fixtures.screenshotBlocks(), 1080, 2400, now)
        assertTrue("应命中，实际 $outcome", outcome is FrameOutcome.Hit)
        outcome as FrameOutcome.Hit
        assertEquals("西江月", outcome.pai)
        assertEquals(Fixtures.BUBBLE_2, outcome.target)
        assertEquals(1.0, outcome.similarity, 1e-9)
    }

    @Test
    fun `只有首句没有气泡时得到 PaiOnly`() {
        val outcome = pipeline().evaluate(Fixtures.headOnly(), 1080, 2400, now)
        assertTrue("应 PaiOnly，实际 $outcome", outcome is FrameOutcome.PaiOnly)
        assertEquals("西江月", (outcome as FrameOutcome.PaiOnly).pai)
    }

    @Test
    fun `画面里没有首句时 NoMatch`() {
        val outcome = pipeline().evaluate(
            listOf(TextBlock.normalized("开始游戏", 0.35f, 0.15f, 0.65f, 0.20f)),
            1080,
            2400,
            now,
        )
        assertEquals(FrameOutcome.NoMatch, outcome)
    }

    @Test
    fun `取帧节流_一个间隔内只受理一帧`() {
        val p = pipeline()
        assertTrue(p.shouldAcceptFrame(now))
        p.markAccepted(now)
        assertFalse("同一时刻不能接着受理", p.shouldAcceptFrame(now))
        // 间隔的一半时刻必须仍然被挡（用比例而不是硬编码毫秒，间隔调整后测试仍然有效）
        val half = now + Config.MIN_FRAME_INTERVAL_MS / 2
        assertFalse("间隔未到时仍然太快", p.shouldAcceptFrame(half))
        assertTrue(
            "到达间隔后可以受理",
            p.shouldAcceptFrame(now + Config.MIN_FRAME_INTERVAL_MS),
        )
    }

    @Test
    fun `节流间隔应与目标帧率一致_且明显快于省电模式`() {
        // 真机基准实测 OCR 只要 ~113ms，节流间隔过大会成为唯一限速器。
        // 这条测试把"间隔 = 1/fps"的契约固定下来，避免以后改 fps 忘了同步。
        assertEquals(
            (1000.0 / Config.FPS_NORMAL).toLong(),
            Config.MIN_FRAME_INTERVAL_MS,
        )
        assertTrue(
            "正常模式必须比省电模式快",
            Config.MIN_FRAME_INTERVAL_MS < Config.IDLE_FRAME_INTERVAL_MS,
        )
        // 高亮存活时间必须大于省电间隔，否则 0.5fps 下框会闪没（原有约束）
        assertTrue(
            "高亮存活时间必须大于省电帧间隔",
            Config.HIGHLIGHT_TTL_MS > Config.IDLE_FRAME_INTERVAL_MS,
        )
    }

    @Test
    fun `连续未命中进入省电模式_帧间隔变 2s`() {
        val p = pipeline()
        repeat(Config.FRAMES_BEFORE_IDLE - 1) { p.noteNoHit() }
        assertFalse("还没到次数不该进省电模式", p.idleMode)
        p.noteNoHit()
        assertTrue("到达次数后进入省电模式", p.idleMode)

        assertTrue(p.shouldAcceptFrame(now))
        p.markAccepted(now)
        assertFalse("省电模式下 1s 还不该受理", p.shouldAcceptFrame(now + 1_000))
        assertTrue("省电模式下 2s 受理", p.shouldAcceptFrame(now + Config.IDLE_FRAME_INTERVAL_MS))
    }

    @Test
    fun `命中后清零省电计数并退出省电模式`() {
        val p = pipeline()
        repeat(Config.FRAMES_BEFORE_IDLE) { p.noteNoHit() }
        assertTrue(p.idleMode)
        p.noteHit(now)
        assertFalse(p.idleMode)
        repeat(Config.FRAMES_BEFORE_IDLE - 1) { p.noteNoHit() }
        assertFalse("计数应从 0 重新开始", p.idleMode)
    }

    @Test
    fun `命中后 3s 冷却期内复用上次结论`() {
        val p = pipeline()
        val first = p.evaluate(Fixtures.screenshotBlocks(), 1080, 2400, now)

        // 冷却期内：即使把气泡抹掉（模拟第二段不跑），也仍返回上一次的结论
        p.noteHit(now)
        assertFalse("刚命中应处于冷却期", p.shouldRunOptionsStage(now))
        val during = p.evaluate(Fixtures.headOnly(), 1080, 2400, now + 500, inCooldown = true)
        assertEquals(first, during)

        assertTrue("超过冷却期后应重跑第二段", p.shouldRunOptionsStage(now + Config.HIT_COOLDOWN_MS))
        val after = p.evaluate(Fixtures.headOnly(), 1080, 2400, now + Config.HIT_COOLDOWN_MS)
        assertTrue("没有气泡时降级为 PaiOnly", after is FrameOutcome.PaiOnly)
    }

    @Test
    fun `未命中会清掉缓存_不会拿旧结论顶替`() {
        val p = pipeline()
        p.evaluate(Fixtures.screenshotBlocks(), 1080, 2400, now)
        assertEquals(
            FrameOutcome.NoMatch,
            p.evaluate(listOf(TextBlock.normalized("开始游戏", 0.3f, 0.15f, 0.7f, 0.2f)), 1080, 2400, now + 100),
        )
        // 新一局：首句变了、气泡也没了 → 必须是 PaiOnly，不能是上一局的 Hit
        val next = p.evaluate(Fixtures.headOnly(), 1080, 2400, now + 200)
        assertTrue("旧缓存必须被清掉，实际 $next", next is FrameOutcome.PaiOnly)
    }

    @Test
    fun `Config 的自洽性`() {
        assertTrue("高亮存活时间必须大于省电帧间隔，否则 0.5fps 下会闪",
            Config.HIGHLIGHT_TTL_MS > Config.IDLE_FRAME_INTERVAL_MS)
        assertTrue(Config.MIN_FRAME_INTERVAL_MS > 0)
        assertTrue(Config.SIMILARITY_THRESHOLD in 0.5..1.0)
        assertTrue(Config.TOP_CROP_BOTTOM <= Config.MID_CROP_BOTTOM)
        assertTrue("同排归并阈值必须是比例", Config.LINE_X_OVERLAP_MAX in 0f..1f)
        assertTrue("包住判定阈值必须是比例", Config.LINE_CONTAIN_WIDTH_MARGIN in 0f..1f)
    }

    // ------------------------------------------------------------------
    // 下面三个用例走**真正的异步入口** onFrame：缓存与冷却期的缺陷只有在这条路上
    // 才会暴露（evaluate 是纯同步的，覆盖不到 noteHit/复用的交互）
    // ------------------------------------------------------------------

    @Test
    fun `onFrame_冷却期会过期_气泡移动后框跟着更新`() {
        val p = pipeline()
        val latch = CountDownLatch(1)
        val seen = AtomicReference<FrameOutcome>(FrameOutcome.Empty)
        p.listener = { outcome, _, _ ->
            seen.set(outcome)
            latch.countDown()
        }

        val moved = Fixtures.headOnly() + Fixtures.optionBlocks("西江月", "念奴娇", "水调歌头") // 气泡 1
        val later = Fixtures.headOnly() + Fixtures.optionBlocks("念奴娇", "西江月", "水调歌头")  // 气泡 2

        assertTrue(p.onFrame(moved, 1080, 2400, now))
        assertTrue("第一帧应处理完成", latch.await(3, TimeUnit.SECONDS))
        val first = seen.get() as FrameOutcome.Hit
        assertEquals(Fixtures.BUBBLE_1, first.target)

        // 2 fps 推 12 帧（约 6s）：冷却期必须到期，重算第二段并发现气泡换了位置
        var at = now
        for (i in 0 until 12) {
            at += 500
            p.onFrame(later, 1080, 2400, at)
            awaitChange(seen, first)
        }
        val latest = seen.get() as FrameOutcome.Hit
        assertEquals("西江月", latest.pai)
        assertEquals("冷却期结束后必须重算第二段，框要跟着气泡走", Fixtures.BUBBLE_2, latest.target)
    }

    @Test
    fun `冷却期内换局必须重新匹配_不得复用上一局结论`() {
        // 直接走同步入口：先把冷却期的状态造出来（命中一次 → 3s 内处于冷却）
        val p = pipeline()
        p.noteHit(now)
        assertFalse("刚命中应处于冷却期", p.shouldRunOptionsStage(now))
        assertTrue(p.evaluate(Fixtures.screenshotBlocks(), 1080, 2400, now) is FrameOutcome.Hit)

        // 600ms 后换了一局（另一句词、另一个词牌），仍在冷却期内：
        // 缓存必须因为「词句 id 变了」而失效，否则会把上一局的词牌画到新一局上
        val nextRound = listOf(
            TextBlock.normalized("明月几时有把酒问青天", 0.08f, 0.11f, 0.92f, 0.16f),
            TextBlock.normalized("水调歌头", 0.24f, 0.50f, 0.76f, 0.545f),
            TextBlock.normalized("念奴娇", 0.24f, 0.575f, 0.76f, 0.62f),
            TextBlock.normalized("西江月", 0.24f, 0.65f, 0.76f, 0.695f),
        )
        val outcome = p.evaluate(nextRound, 1080, 2400, now + 600, inCooldown = true)
        assertTrue("应重新匹配出新一局，实际 $outcome", outcome is FrameOutcome.Hit)
        outcome as FrameOutcome.Hit
        assertEquals("换局后不得复用上一局的词牌", "水调歌头", outcome.pai)
        assertEquals(Fixtures.BUBBLE_1, outcome.target)
    }

    @Test
    fun `onFrame_同局冷却期内复用缓存但不续期冷却`() {
        val p = pipeline()
        val latch = CountDownLatch(1)
        val seen = AtomicReference<FrameOutcome>(FrameOutcome.Empty)
        p.listener = { outcome, _, _ ->
            seen.set(outcome)
            latch.countDown()
        }
        assertTrue("第一帧应被受理", p.onFrame(Fixtures.screenshotBlocks(), 1080, 2400, now))
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        val first = seen.get() as FrameOutcome.Hit
        // 等这一帧真正处理完（busy 复位），否则下一帧会被「同一时刻只处理一帧」挡掉
        assertTrue(awaitIdle(p))

        // 600ms 后同一局再推一帧：冷却期内复用缓存（结论相同），且**不能续期**冷却，
        // 否则 2fps 下每帧都续期，第二段就永远不会重跑
        assertTrue("第二帧应被受理（已过 500ms 节流）", p.onFrame(Fixtures.screenshotBlocks(), 1080, 2400, now + 600))
        assertTrue(awaitOutcome(seen, first))
        assertTrue(
            "冷却必须在 3s 后到期（不能被缓存续期）",
            p.shouldRunOptionsStage(now + Config.HIT_COOLDOWN_MS),
        )
    }

    @Test
    fun `onFrame_命中结束后补发 NoMatch`() {
        val p = pipeline()
        val latch = CountDownLatch(1)
        val seen = AtomicReference<FrameOutcome>(FrameOutcome.Empty)
        p.listener = { outcome, _, _ ->
            seen.set(outcome)
            latch.countDown()
        }
        assertTrue(p.onFrame(Fixtures.screenshotBlocks(), 1080, 2400, now))
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals("西江月", (seen.get() as FrameOutcome.Hit).pai)
        assertTrue("第一帧应处理完", awaitIdle(p))

        // 选完了：屏幕上只剩气泡，没有首句。等冷却过期再推一帧，避免被节流挡住
        val afterPicked = Fixtures.optionBlocks("念奴娇", "西江月", "水调歌头")
        assertTrue("第二帧应被受理", p.onFrame(afterPicked, 1080, 2400, now + Config.HIT_COOLDOWN_MS + 1000))
        assertTrue("命中消失要立刻通知悬浮层清高亮（实际 ${seen.get()}）",
            awaitOutcome(seen, FrameOutcome.NoMatch))
    }

    /** 轮询等监听器被调用出新值（异步流水线，不用 sleep 猜时间）。 */
    private fun awaitChange(seen: AtomicReference<FrameOutcome>, previous: FrameOutcome) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (seen.get() !== previous) return
            Thread.sleep(2)
        }
    }

    /** 轮询等监听器给出某个具体结论。 */
    private fun awaitOutcome(seen: AtomicReference<FrameOutcome>, want: FrameOutcome): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (seen.get() == want) return true
            Thread.sleep(2)
        }
        return false
    }

    /** 等流水线空闲（异步帧处理完，busy 复位）。 */
    private fun awaitIdle(p: FramePipeline): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (p.shouldAcceptFrame(Long.MAX_VALUE)) return true
            Thread.sleep(2)
        }
        return false
    }

    @Test
    fun `两段式裁剪_顶部与中部的归属`() {
        val blocks = Fixtures.screenshotBlocks()
        val top = FramePreprocessor.topArea(blocks).map { VerseIndex.normalize(it.text) }
        val mid = FramePreprocessor.midArea(blocks).map { VerseIndex.normalize(it.text) }
        assertTrue("首句必须落在顶部区域: $top", top.contains(VerseIndex.normalize(Fixtures.HEAD_TEXT)))
        assertTrue("三个气泡必须落在中部区域: $mid", mid.containsAll(listOf("念奴娇", "西江月", "水调歌头")))
        assertTrue("时间戳不应落在中部区域", mid.none { it == "1234" })
    }
}
