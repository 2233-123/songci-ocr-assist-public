package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **短首句 + 形近误读** —— 用户反馈「识别『甚矣吾衰矣』这句时结果不太好」。
 *
 * ### 根因链
 *
 * 1. 真机日志（173521）实测该句被读成 **`甚美吾衰吴`** —— 5 个字里错 2 个
 *    （`矣`→`美`、`矣`→`吴`，两个位置都错在「矣」上）
 * 2. 编辑距离相似度 `1 - 2/5 = 0.600`，而阈值是 `0.72` → **整句废掉**
 * 3. 于是报「首句未匹配」，用户看到的就是"识别结果不太好"
 *
 * ### 一个结构性弱点：首句越短越怕错字
 *
 * | 首句长度 | 最多容忍错字 | 错 n+1 个时的相似度 |
 * |---|---|---|
 * | 5 字 | 1 个 | 0.600 ❌ |
 * | 6 字 | 1 个 | 0.667 ❌ |
 * | 8 字 | 2 个 | 0.625 ❌ |
 * | 12 字 | 3 个 | 0.667 ❌ |
 *
 * 索引里 5~6 字的短首句只有 3 条，全是高风险条目。
 *
 * ### 修法
 *
 * 把 `矣/美/吴`、`衰/哀` 加进 `VerseIndex.CONFUSABLE_CLASSES`，
 * 并让**首句匹配**（`nearestAny` / `nearestByPrefix`）也用形近等价打分
 * —— 之前这套机制只用在气泡匹配上。
 *
 * **本测试重点守护"扩展机制到首句"后的安全性**：不能出现两条不同词句因此撞车。
 */
class ShortHeadConfusableTest {

    private val index = Fixtures.index()

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)

    // ---------------------------------------------------------------- 真机现场

    @Test
    fun `真机现场_甚矣吾衰矣被读成甚美吾衰吴_应能认出贺新郎`() {
        // 真机日志 173521 第 681/701/821/841 帧实读 `首句甚美吾衰吴`
        for (misread in listOf("甚矣吾衰矣", "甚美吾衰吴", "甚矣吾衰吴", "甚美吾衰矣", "甚矣吾衰哀")) {
            val r = Matcher.match(
                listOf(b("首句$misread", 0.19f), b("定风波", 0.43f), b("贺新郎", 0.57f)),
                index,
            )
            assertTrue("「$misread」应命中贺新郎，实际 $r", r is MatchResult.Hit)
            assertEquals("「$misread」认错了", "贺新郎", (r as MatchResult.Hit).verse.pai)
        }
    }

    @Test
    fun `修复前后对比_编辑距离判不出但形近等价能判出`() {
        val read = "甚美吾衰吴"
        val real = "甚矣吾衰矣"
        val plain = Matcher.similarity(read, real)
        val conf = VerseIndex.confusableSimilarity(read, real)
        println("    编辑距离相似度 = %.3f（阈值 %.2f → %s）"
            .format(plain, Config.SIMILARITY_THRESHOLD, if (plain >= Config.SIMILARITY_THRESHOLD) "过" else "不过"))
        println("    形近等价相似度 = %.3f".format(conf))
        assertTrue("这就是失败的根因：0.600 < 0.72", plain < Config.SIMILARITY_THRESHOLD)
        assertEquals("形近等价后应满分", 1.0, conf, 1e-9)
    }

    // ---------------------------------------------------------------- 安全性（关键）

    @Test
    fun `扩展形近等价到首句后_任意两条不同词句都不该撞到阈值`() {
        // 这是本次改动最大的风险点：形近等价原本只用于 40 个词牌名，
        // 现在用到了 99 条首句上。必须确认没有两条不同词句因此变得"够像"。
        val verses = index.verses
        val bad = ArrayList<String>()
        for (i in verses.indices) {
            for (j in i + 1 until verses.size) {
                val a = verses[i]
                val b = verses[j]
                if (a.head == b.head) continue  // 索引里本来就有同名不同句的条目
                val s = VerseIndex.confusableSimilarity(a.head, b.head)
                if (s >= Config.SIMILARITY_THRESHOLD) {
                    bad += "${a.head}(${a.pai}) ~ ${b.head}(${b.pai}) = %.3f".format(s)
                }
            }
        }
        assertTrue("不该有两条不同词句在形近等价下达到阈值：$bad", bad.isEmpty())
    }

    @Test
    fun `每条首句都应能认出自己`() {
        for (v in index.verses) {
            val blocks = listOf(b("首句${v.head}", 0.19f), b(v.pai, 0.57f))
            val r = Matcher.match(blocks, index)
            assertTrue("「${v.head}」(${v.pai}) 应命中，实际 $r", r is MatchResult.Hit)
            assertEquals("「${v.head}」认成别的词牌了", v.pai, (r as MatchResult.Hit).verse.pai)
        }
    }

    @Test
    fun `短首句是结构性弱点_记录各长度的错字容忍度`() {
        // 钉住这个表，避免以后有人把阈值往上调而不知道代价
        fun tol(len: Int): Int {
            var t = 0
            // 注意必须写成 Double 除法：`(t + 1) / len` 在 Kotlin 里是整数除法，
            // 会算成 0（曾经因此让断言得到 4 而不是 1，是测试自身的 bug）
            while (1.0 - (t + 1).toDouble() / len >= Config.SIMILARITY_THRESHOLD) t++
            return t
        }
        assertEquals("5 字首句只能容忍 1 个错字", 1, tol(5))
        assertEquals("6 字首句只能容忍 1 个错字", 1, tol(6))
        assertEquals("8 字首句能容忍 2 个错字", 2, tol(8))
        assertEquals("12 字首句能容忍 3 个错字", 3, tol(12))

        // 索引里到底有多少条这种短首句
        val short = index.verses.filter { it.head.length <= 6 }
        println("    5~6 字短首句共 ${short.size} 条：" +
            short.joinToString("、") { "${it.head}(${it.pai})" })
        assertTrue("短首句数量应很少（少才值得单独关注）", short.size <= 6)
    }
}
