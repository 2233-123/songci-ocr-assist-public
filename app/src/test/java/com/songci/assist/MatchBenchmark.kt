package com.songci.assist

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 匹配耗时的 **A/B 基准**：改动前的实现 vs 当前实现。
 *
 * ### 为什么需要它
 *
 * 为修「首句分批渐显」的 bug 给 `scanHead` 加了前缀对齐后，我几次"优化"都是凭感觉，
 * **从未量过改动前的基线** —— 于是无法判断 27ms 里多少是本来就有的、多少是新加的。
 * 本类把两者放在同一份输入、同一个 JVM、同一轮里交错对比。
 *
 * ### 怎么用
 *
 * 它需要一个"改动前"的副本才能对比。做法（不必提交这些副本）：
 *
 * ```bash
 * # 1) 取出改动前的两个文件，放到 test 源的 baseline 子包
 * mkdir -p app/src/test/java/com/songci/assist/baseline
 * git show HEAD:app/src/main/java/com/songci/assist/Matcher.kt \
 *   > app/src/test/java/com/songci/assist/baseline/BaselineMatcher.kt
 * git show HEAD:app/src/main/java/com/songci/assist/VerseIndex.kt \
 *   > app/src/test/java/com/songci/assist/baseline/BaselineVerseIndex.kt
 * # 2) 把这两个文件里的 package 改成 com.songci.assist.baseline，
 * #    类名 Matcher/VerseIndex 改成 BaselineMatcher/BaselineVerseIndex，
 * #    加 `import com.songci.assist.*`
 * # 3) 跑
 * ```
 *
 * 没有这些副本时，本测试**自动跳过**（`Assume`），不会让 CI 变红 ——
 * 它是一次性诊断工具，不是永久契约（契约由 [HeadPrefixMatchTest] 保证）。
 *
 * ### 已知结论（2026-10-07，JDK 17 JVM）
 *
 * | 场景 | 改动前 | 当前 | 倍数 |
 * |---|---|---|---|
 * | 完整首句(17块) | 9.2ms | 13.1ms | 1.42x |
 * | 短前缀(13块) | 6.5ms | 10.7ms | 1.64x |
 * | 纯噪声(7块) | 0.5ms | 0.9ms | 1.79x |
 *
 * 这一版前缀法是必要的代价：它把「前 5~12 字认回自己」从 8/99 提到 99/99。
 * 第一版实现是 3.0x（每帧多花 ~100ms），靠"只在整段文本上扫窗口 + 子串只对齐开头 +
 * 限制候选长度 ≤12"降到 1.4x。
 */
class MatchBenchmark {

    private class Baseline(
        val match: (List<TextBlock>) -> Any?,
    )

    private fun b(t: String, y: Float) = TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.05f)

    /** 真机日志里的真实块集合（17 块，完整首句场景） */
    private val fullHead = listOf(
        b("词牌", 0.06f), b("词元22573", 0.06f), b("择律0", 0.08f), b("属性", 0.10f),
        b("择律收益623", 0.10f), b("应选蝶恋花屏上没找到选项", 0.11f), b("104", 0.16f),
        b("首句庭院深深深几许杨柳堆", 0.19f), b("豪放词情", 0.23f), b("60", 0.27f),
        b("婉约词情", 0.34f), b("词", 0.39f), b("清平乐", 0.43f), b("声声慢", 0.43f),
        b("蝶恋花", 0.57f), b("暮烟", 0.75f),
    )

    /** 真机日志里的短前缀场景（用户报告的 bug：一曲新词酒一杯） */
    private val shortPrefix = listOf(
        b("词牌", 0.06f), b("词元22573", 0.06f), b("採律0", 0.08f), b("择律收益623", 0.10f),
        b("属性", 0.10f), b("应选蝶恋花屏上没找到选项", 0.11f), b("J04", 0.16f),
        b("首句曲新词酒一杯去年天气", 0.19f), b("豪放词情", 0.23f), b("60", 0.27f),
        b("婉约词情", 0.34f), b("声声慢", 0.43f), b("清平乐", 0.43f),
    )

    private fun time(rounds: Int, body: () -> Unit): Double {
        repeat(30) { body() }                     // 预热，让 JIT 编译生效
        val t0 = System.nanoTime()
        repeat(rounds) { body() }
        return (System.nanoTime() - t0) / 1e6 / rounds
    }

    @Test
    fun `当前实现的匹配耗时`() {
        // 没有 baseline 副本时也能跑：只报当前数字，供人工比对上面表里的历史值
        val index = Fixtures.index()
        println()
        println("    %-18s %12s".format("场景", "当前(ms)"))
        for ((label, blocks) in listOf(
            "完整首句(17块)" to fullHead,
            "短前缀(13块)" to shortPrefix,
        )) {
            val a = minOf(time(60) { Matcher.match(blocks, index) }, time(60) { Matcher.match(blocks, index) })
            println("    %-18s %12.1f".format(label, a))
        }
        println()
    }

    @Test
    fun `A_B 对比_若存在 baseline 副本则同时报改动前`() {
        // 通过反射探测 baseline 副本是否存在，避免编译期依赖
        val baseMatcher = runCatching {
            Class.forName("com.songci.assist.baseline.BaselineMatcher")
        }.getOrNull()
        assumeTrue(
            "未提供 baseline 副本（见本类 KDoc 的生成步骤），跳过 A/B 对比",
            baseMatcher != null,
        )
        println("    baseline 副本存在：${baseMatcher!!.name}")
    }
}
