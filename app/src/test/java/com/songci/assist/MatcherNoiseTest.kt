package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 噪声鲁棒性：每条首句随机注入 1/2/3 个错字，检验两件事。
 *
 * **第一件（硬约束）**：不考虑阈值时，最近邻绝不能判成**别的词牌**——那会画错框。
 * 这一条在所有错字量下都必须 100% 成立。
 *
 * **第二件（可调阈值）**：相似度必须 ≥ [Config.SIMILARITY_THRESHOLD] 才会提示；
 * 短首句 + 多个随机错字时，正确词句的第一名相似度可能掉到阈值以下，此时的结果是
 * **不提示**（安全，符合「宁可不提示，也不误报」）。
 *
 * 阈值 0.72 下的实测见 `tools/tune_matcher.py`（阈值扫描表，可复算）；本测试的判据是：
 * 1 错字 100% 提示；2 错字提示率 ≥95%；3 错字提示率 ≥80%；三者判错词牌数都必须是 0。
 * 注意本测试用 `kotlin.random.Random`，Python 工具用梅森旋转，因此**样本实例**不同，
 * 但都不产生误报（多组随机种子实测一致）。
 *
 * 设计文档 §2 预研里写的「1/2/3 个错字最近邻 100% 命中」与实测不符：预研用的是
 * difflib 相似度且错字模型不同。按设计文档 §2 约定的**归一化编辑距离**复测，
 * 短首句 + 多个随机整字替换时覆盖率会下降，但**从不误报**。真机调参用
 * `Config.SIMILARITY_THRESHOLD` 下调（0.65 时 3 错字提示率约 97%，实测仍 0 误报）。
 */
class MatcherNoiseTest {

    private val index = Fixtures.index()

    private val distractors = "错字乱码雨天风雪山水云月花鸟人心情愁夜灯".toCharArray()

    /** 1 个错字：要求 100% 给出结论且词牌正确。 */
    @Test
    fun `注入1个错字_全部给出正确词牌`() {
        val stats = measure(errors = 1, perVerse = 20)
        assertTrue("1 错字不应判错词牌，实际 ${stats.wrong}", stats.wrong == 0)
        assertTrue("1 错字不应被阈值拦住，实际不提示 ${stats.dropped} 例（最差相似度 ${stats.worst}）",
            stats.dropped == 0)
        assertTrue("最差相似度 ${stats.worst} 应 ≥ 阈值", stats.worst >= Config.SIMILARITY_THRESHOLD)
    }

    /** 2 个错字：绝不判错；提示率 ≥ 95。 */
    @Test
    fun `注入2个错字_绝不判错且提示率不低于95`() {
        val stats = measure(errors = 2, perVerse = 5)
        assertTrue("2 错字不应判错词牌，实际 ${stats.wrong}", stats.wrong == 0)
        assertTrue("提示率 ${stats.recallPercent} 应 ≥ 95（不提示 ${stats.dropped} 例）",
            stats.recallPercent >= 95.0)
    }

    /** 3 个错字：绝不判错；提示率 ≥ 80（短首句会安全地不提示）。 */
    @Test
    fun `注入3个错字_绝不判错且提示率不低于80`() {
        val stats = measure(errors = 3, perVerse = 2)
        assertTrue("3 错字不应判错词牌，实际 ${stats.wrong}", stats.wrong == 0)
        assertTrue("提示率 ${stats.recallPercent} 应 ≥ 80（不提示 ${stats.dropped} 例）",
            stats.recallPercent >= 80.0)
    }

    private data class Stats(
        val samples: Int,
        val correct: Int,
        val wrong: Int,
        val dropped: Int,
        val worst: Double,
    ) {
        /** 在「最近邻没判错」的样本里，有多少真的提示出了正确词牌。 */
        val recallPercent: Double get() = 100.0 * correct / (samples - wrong).coerceAtLeast(1)
    }

    private fun measure(errors: Int, perVerse: Int): Stats {
        val random = Random(20261005)
        var samples = 0
        var correct = 0
        var wrong = 0
        var dropped = 0
        var worst = 1.0

        for (verse in index.verses) {
            repeat(perVerse) {
                val noisy = injectErrors(verse.head, errors, random)
                if (noisy == verse.head) return@repeat
                samples++

                // 1) 不考虑阈值的最近邻必须就是正确的词牌 —— 这是「不画错框」的底线。
                //    注意不能用 index.nearest()：它在「低于阈值」时也返回 null，
                //    而那属于安全失败（不提示），不是判错。
                val near = index.nearestAny(noisy)
                if (near == null || near.first.pai != verse.pai) {
                    wrong++
                    return@repeat
                }
                if (near.second < worst) worst = near.second

                // 2) 走完整 Matcher（覆盖裁剪区 + 行拼接 + 查表 + 找气泡）
                val result = Matcher.match(
                    listOf(TextBlock.normalized(noisy, 0.08f, 0.11f, 0.92f, 0.16f)),
                    index,
                )
                when {
                    result is MatchResult.Hit && result.verse.pai == verse.pai -> correct++
                    result is MatchResult.PaiOnly && result.verse.pai == verse.pai -> correct++
                    result is MatchResult.None -> dropped++
                    else -> wrong++
                }
            }
        }
        return Stats(samples, correct, wrong, dropped, worst)
    }

    /** 在 [text] 上随机替换 [errors] 个位置（替换字可能与原字相同）。 */
    private fun injectErrors(text: String, errors: Int, random: Random): String {
        if (text.isEmpty()) return text
        val chars = text.toCharArray()
        repeat(errors) {
            chars[random.nextInt(chars.size)] = distractors[random.nextInt(distractors.size)]
        }
        return String(chars)
    }
}
