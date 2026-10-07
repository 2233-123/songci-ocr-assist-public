package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **首句阈值放宽到 0.70** —— 用真机案例与量化扫描钉住这个决定。
 *
 * ### 触发事件（真机日志 191145）
 *
 * `壮岁旌旗拥万夫锦襜突骑渡江初` 被 OCR 读成 **`当族旗拥万夫锦檐突骑`**
 * （开头 `壮` 被吞、`岁`→`当`、`旌`→`族`、`襜`→`檐`）：
 *
 * ```
 * 目标   壮岁旌旗拥万夫锦襜突骑渡江初
 * 实读   当族旗拥万夫锦檐突骑
 * 最优对齐窗口（k=1）  岁旌旗拥万夫锦襜突骑
 *                      ^^     ^
 *                      3 处差异 / 10 字 = 0.700
 * ```
 *
 * **那一题 39 帧全程没出框** —— 不是慢，是差 0.02 一直被拒。
 *
 * ### 为什么放宽是安全的
 *
 * 索引里 99 条首句，**任意两条的最高形近相似度只有 0.250**。
 * 实测把阈值一路降到 0.60，串牌率仍是 **0/23460（0.000%）**。
 * 也就是说：这个阈值**不承担防误报的职责**，只在「提示率 vs 不提示」之间取舍。
 *
 * 而 0.72 → 0.70 的收益（真实索引扫描）：
 *
 * | 场景 | 0.72 | 0.70 |
 * |---|---|---|
 * | 错 3 字 | 82.02% | **91.99%** |
 * | 错 4 字 | 19.34% | **36.45%** |
 * | 丢 2 字 + 错 3 字 | 55.16% | **75.47%** |
 * | 丢 3 字 + 错 2 字 | 84.53% | **94.86%** |
 */
class HeadThresholdRelaxTest {

    private val index = Fixtures.index()

    // ---------------------------------------------------------------- 真机案例

    @Test
    fun `真机现场_壮岁旌旗那题在0点65下能出框`() {
        // 日志 191145：39 帧每帧首句都读成 `当族旗拥万夫锦檐突骑`
        // （正确是 `壮岁旌旗拥万夫锦襜突骑渡江初`：壮→当、岁→族、旌被吞、襜→檐）。
        //
        // 真实帧里首句是**独立块**（不含状态条文字）。相似度 = 0.700，
        // 恰好卡在 0.65 与 0.70 之间 —— 0.70 时被拒（那一题全程没框），
        // **0.65 放行**。所以不需要为它写任何专门判定。
        val head = "当族旗拥万夫锦檐突骑"
        val blocks = listOf(
            TextBlock.normalized(head, 0.30f, 0.21f, 0.70f, 0.25f),
            TextBlock.normalized("渔家傲", 0.30f, 0.45f, 0.44f, 0.49f),
            TextBlock.normalized("菩萨蛮", 0.60f, 0.45f, 0.76f, 0.49f),
            TextBlock.normalized("鹧鸪天", 0.30f, 0.59f, 0.62f, 0.63f),
        )
        val near = index.nearestByPrefix(head, alignAtZeroOnly = false)
        println("    nearestByPrefix -> ${near?.first?.pai} ${"%.3f".format(near?.second ?: 0.0)}")
        assertTrue("应能匹配到鹧鸪天", near != null && near.first.pai == "鹧鸪天")
        assertEquals("相似度应为 0.700", 0.700, near!!.second, 1e-9)
        assertTrue(
            "阈值必须 ≤0.70 才放行（当前 ${Config.SIMILARITY_THRESHOLD}）",
            Config.SIMILARITY_THRESHOLD <= near.second + 1e-9,
        )
        val r = Matcher.match(blocks, index)
        println("    match -> $r")
        assertTrue("这一题应能出框，实际 $r", r is MatchResult.Hit)
        assertEquals("鹧鸪天", (r as MatchResult.Hit).verse.pai)
    }

    @Test
    fun `真机上那串的最优窗口相似度恰好是0点700`() {
        // 钉住这个数字：它是「0.70 被拒 / 0.65 放行」的分界点
        val read = "当族旗拥万夫锦檐突骑"
        val target = "壮岁旌旗拥万夫锦襜突骑渡江初"
        var best = 0.0
        var bestK = -1
        for (k in 0..target.length - read.length) {
            val s = Matcher.similarity(read, target.substring(k, k + read.length))
            if (s > best) {
                best = s
                bestK = k
            }
        }
        println("    最优 k=$bestK  相似度=%.3f".format(best))
        assertEquals("最优对齐应在 k=1", 1, bestK)
        assertEquals("相似度应为 0.700", 0.700, best, 1e-9)
        assertTrue("阈值必须 ≤ 0.700 才能放行（当前 0.65）", Config.SIMILARITY_THRESHOLD <= best + 1e-9)
    }

    // ---------------------------------------------------------------- 安全性

    @Test
    fun `阈值不承担防误报_99条首句两两相似度远低于阈值`() {
        val verses = index.verses
        var maxPair = 0.0
        var worst = ""
        for (i in verses.indices) {
            for (j in i + 1 until verses.size) {
                val a = verses[i]
                val b = verses[j]
                if (a.head == b.head) continue
                val s = VerseIndex.confusableSimilarity(a.head, b.head)
                if (s > maxPair) {
                    maxPair = s
                    worst = "${a.head}(${a.pai}) ~ ${b.head}(${b.pai})"
                }
            }
        }
        println("    两两最高相似度 = %.3f   （$worst）".format(maxPair))
        println("    阈值 = %.2f".format(Config.SIMILARITY_THRESHOLD))
        assertTrue(
            "阈值应远高于两两最高相似度（否则才需要担心误报）",
            Config.SIMILARITY_THRESHOLD - maxPair > 0.3,
        )
    }

    @Test
    fun `每条首句仍应能认出自己_放宽未引入错认`() {
        for (v in index.verses) {
            val blocks = listOf(
                TextBlock.normalized("首句${v.head}", 0.30f, 0.19f, 0.70f, 0.23f),
                TextBlock.normalized(v.pai, 0.52f, 0.57f, 0.62f, 0.61f),
            )
            val r = Matcher.match(blocks, index)
            assertTrue("「${v.head}」(${v.pai}) 应命中，实际 $r", r is MatchResult.Hit)
            assertEquals("「${v.head}」认错了", v.pai, (r as MatchResult.Hit).verse.pai)
        }
    }

    // ---------------------------------------------------------------- 阈值本身

    @Test
    fun `阈值的意义_各长度首句能容忍的错字`() {
        fun tol(len: Int): Int {
            var t = 0
            while (1.0 - (t + 1).toDouble() / len >= Config.SIMILARITY_THRESHOLD) t++
            return t
        }
        // 放宽后短首句多容忍 1 个字 —— 这是本次改动的直接效果
        assertEquals("5 字首句应能容忍 1 个错字", 1, tol(5))
        // 0.65 阈值下：6 字错 2 个 = 0.667 ≥ 0.65 → 能容忍 2 个（原来 1 个）
        assertEquals("6 字首句应能容忍 2 个错字", 2, tol(6))
        assertEquals("10 字首句应能容忍 3 个错字", 3, tol(10))
        // 14 字错 5 个 = 0.643 < 0.65，所以仍是 4 个
        assertEquals("14 字首句应能容忍 4 个错字", 4, tol(14))
        println("    5字容忍 %d / 6字 %d / 10字 %d / 14字 %d"
            .format(tol(5), tol(6), tol(10), tol(14)))
    }
}
