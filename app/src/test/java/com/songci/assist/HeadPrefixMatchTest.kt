package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「首句分批渐显」时的匹配 —— 真机 bug 的回归测试。
 *
 * ### 真实场景
 *
 * 游戏的首句是**一次显示一段**的（截图证实：「一曲新词酒一杯，去年天气旧亭台，
 * 夕阳西下几时回」分批出现），而 OCR 读到的就是当时那一段。用户报告：
 * 屏幕上是「一曲新词酒一杯」时，程序认不出正确答案（浣溪沙）。
 *
 * ### 根因
 *
 * 相似度 `sim = 1 - 距离 / max(len(a), len(b))` 的分母是**较长者**：
 *
 * ```
 * 候选「一曲新词酒一杯」(7) vs 首句(42)  →  即使 7 字一字不差是前缀，也只有 1-35/42 = 0.167
 * ```
 *
 * 远低于阈值 0.72 → 整条词永远认不出来。
 *
 * ### 修法
 *
 * 新增 `VerseIndex.nearestByPrefix`：候选与首句的**等长前缀**比较（前缀对齐即为 1.0）。
 * 安全性由数据保证：99 条首句的 4~12 字前缀**全部互不相同**（零重复组）。
 */
class HeadPrefixMatchTest {

    private val index = Fixtures.index()

    private fun headOf(pai: String) =
        index.verses.first { it.pai == pai }.head

    @Test
    fun `用户报告的场景_只显示前7字也应认出浣溪沙`() {
        // 现场 OCR 原文（第 161 帧）：前面带「首句」标签，且「一」被漏读成「1」
        val ocrText = "首句1曲新词酒一杯去年天"
        val hit = Matcher.scanHead(
            listOf(TextBlock.normalized(ocrText, 0.28f, 0.17f, 0.72f, 0.22f)),
            index,
        )
        assertTrue("应命中，实际 $hit", hit is HeadMatch.Hit)
        assertEquals("浣溪沙", (hit as HeadMatch.Hit).verse.pai)
    }

    @Test
    fun `不带标签的纯前缀也应命中`() {
        for (text in listOf("一曲新词酒一杯", "曲新词酒一杯", "一曲新词酒一杯去年天气")) {
            val hit = Matcher.scanHead(
                listOf(TextBlock.normalized(text, 0.28f, 0.17f, 0.72f, 0.22f)),
                index,
            )
            assertTrue("「$text」应命中，实际 $hit", hit is HeadMatch.Hit)
            assertEquals("「$text」应命中浣溪沙", "浣溪沙", (hit as HeadMatch.Hit).verse.pai)
        }
    }

    @Test
    fun `全部99条_任意前5到12字都应认回自己`() {
        // 这是本次修法的核心保证：不管游戏显示到第几段，都能认出来。
        var checked = 0
        for (v in index.verses) {
            for (n in 5..12) {
                if (v.head.length < n) continue
                val prefix = v.head.substring(0, n)
                val hit = Matcher.scanHead(
                    listOf(TextBlock.normalized(prefix, 0.28f, 0.17f, 0.72f, 0.22f)),
                    index,
                )
                assertTrue(
                    "「$prefix」(前 $n 字) 应命中自己，实际 $hit",
                    hit is HeadMatch.Hit,
                )
                assertEquals(
                    "「$prefix」(前 $n 字) 认错了",
                    v.pai,
                    (hit as HeadMatch.Hit).verse.pai,
                )
                checked++
            }
        }
        assertTrue("至少要覆盖几百个组合，实际 $checked", checked >= 500)
    }

    @Test
    fun `前缀匹配不应抢走完整首句的匹配`() {
        // 完整首句必须仍然精确命中自己（回归：别为了救前缀而破坏原有行为）
        for (v in index.verses) {
            val hit = Matcher.scanHead(
                listOf(TextBlock.normalized(v.head, 0.28f, 0.17f, 0.72f, 0.22f)),
                index,
            )
            assertTrue("完整首句应命中，实际 $hit", hit is HeadMatch.Hit)
            assertEquals(v.pai, (hit as HeadMatch.Hit).verse.pai)
            assertEquals("完整首句应精确命中 1.0", 1.0, (hit as HeadMatch.Hit).similarity, 1e-9)
        }
    }

    @Test
    fun `前缀法本身_等长前缀一字不差应为满分`() {
        val head = headOf("浣溪沙")   // 一曲新词酒一杯…（42 字）
        val prefix = head.substring(0, 7)
        val r = index.nearestByPrefix(prefix)
        assertNotNull("前缀应能匹配到条目", r)
        assertEquals(head, r!!.first.head)
        assertEquals("等长前缀一字不差应为 1.0", 1.0, r.second, 1e-9)
    }

    @Test
    fun `对比_旧的整体比较对短前缀确实很低`() {
        // 把 bug 的根因钉住：如果哪天有人把 nearestByPrefix 删了，这条会说明为什么不能删
        val head = headOf("浣溪沙")
        val prefix = head.substring(0, 7)
        val old = index.nearestAny(prefix)
        assertTrue(
            "整体比较对 7 字前缀的相似度应远低于阈值（这才是 bug 的根因）",
            old == null || old.second < Config.SIMILARITY_THRESHOLD,
        )
    }

    @Test
    fun `所有首句的短前缀互不相同_这是前缀法安全的前提`() {
        // 前缀法能成立，靠的是"短前缀不歧义"。一旦索引新增了近似首句，这条会先失败。
        for (n in 4..12) {
            val seen = HashMap<String, String>()
            for (v in index.verses) {
                if (v.head.length < n) continue
                val p = v.head.substring(0, n)
                val prev = seen.put(p, v.pai)
                assertTrue(
                    "前 $n 字前缀「$p」重复：${prev} 与 ${v.pai} —— 前缀法不再安全",
                    prev == null,
                )
            }
        }
    }

    @Test
    fun `性能_前缀法不能把匹配拖慢一个量级`() {
        // 前缀法给每个候选又加了「5 个窗口 × 99 条」的比较，必须确认开销可控。
        // 这里只做**相对**断言（JVM 比手机快得多，绝对值无意义）：
        // 用真机日志里的真实块集合，确认单次匹配仍在合理量级。
        fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.05f)
        val hitCase = listOf(
            b("词牌", 0.06f), b("词元22573", 0.06f), b("择律0", 0.08f), b("属性", 0.10f),
            b("择律收益623", 0.10f), b("应选蝶恋花屏上没找到选项", 0.11f), b("104", 0.16f),
            b("首句庭院深深深几许杨柳堆", 0.19f), b("豪放词情", 0.23f), b("60", 0.27f),
            b("婉约词情", 0.34f), b("词", 0.39f), b("清平乐", 0.43f), b("声声慢", 0.43f),
            b("蝶恋花", 0.57f), b("暮烟", 0.75f),
        )
        repeat(20) { Matcher.match(hitCase, index) }   // 预热
        val t0 = System.nanoTime()
        repeat(30) { Matcher.match(hitCase, index) }
        val msPerMatch = (System.nanoTime() - t0) / 1e6 / 30

        // 同时量一下「最坏情况」：完全匹配不上时会把所有子串都走一遍
        val missCase = listOf(
            b("词牌", 0.06f), b("词元22573", 0.06f), b("择律0", 0.08f), b("属性", 0.10f),
            b("择律收益623", 0.10f), b("应选蝶恋花屏上没找到选项", 0.11f), b("104", 0.16f),
            b("首句曲新词酒一杯去年天气", 0.19f), b("豪放词情", 0.23f), b("60", 0.27f),
            b("婉约词情", 0.34f), b("声声慢", 0.43f), b("清平乐", 0.43f),
        )
        repeat(20) { Matcher.match(missCase, index) }
        val t1 = System.nanoTime()
        repeat(30) { Matcher.match(missCase, index) }
        val msMiss = (System.nanoTime() - t1) / 1e6 / 30
        println("    [计时] 命中场景 %.1f ms/次   未命中场景 %.1f ms/次".format(msPerMatch, msMiss))

        // 分别量「第一段」与「第二段」，判断开销在哪一段
        val t2 = System.nanoTime()
        repeat(30) { Matcher.scanHead(missCase, index) }
        val msHead = (System.nanoTime() - t2) / 1e6 / 30
        println("    [计时] 仅 scanHead %.1f ms/次".format(msHead))

        // 真机上匹配中位 51ms、最大 865ms。JVM 上留 25ms 的宽松上限。
        assertTrue(
            "单次匹配耗时 %.1f ms（未命中 %.1f ms，scanHead %.1f ms），超过 25ms 上限"
                .format(msPerMatch, msMiss, msHead),
            msPerMatch < 25.0 && msMiss < 25.0,
        )
    }
}
