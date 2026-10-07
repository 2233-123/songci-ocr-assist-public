package com.songci.assist

import org.junit.runner.JUnitCore
import org.junit.runner.Result
import org.junit.runner.notification.Failure

/**
 * 开发用统计器：把「真正跑了」与「被 Assume 跳过」分开报出来。
 *
 * 为什么需要它：`JUnitCore` 对跳过的用例只打印为 `.`，输出与真正通过**完全一样**
 * （都是 `OK (N tests)`）。于是「公开仓库里依赖 effects.json 的用例是否真的跳过了」
 * 这件事**从输出上无法分辨** —— 我一度因此无法确认跳过机制是否生效。
 *
 * 用法：
 * ```
 * java -cp <...> com.songci.assist.TestStats <测试类全名...>
 * ```
 */
object TestStats {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("用法: TestStats <测试类全名...>")
            return
        }
        val classes = args.map { Class.forName(it) }.toTypedArray()
        val result: Result = JUnitCore().run(*classes)

        val assumed = result.failures.filter { it.isAssumptionFailure() }
        val real = result.failures.filterNot { it.isAssumptionFailure() }

        println()
        println("=".repeat(64))
        println("用例总数      : ${result.runCount}")
        println("  真正执行并通过: ${result.runCount - result.ignoreCount - result.assumptionFailureCount}")
        println("  被跳过(Assume) : ${result.assumptionFailureCount}")
        println("  失败           : ${real.size}")
        println("  被忽略(@Ignore) : ${result.ignoreCount}")
        println("耗时          : ${result.runTime} ms")
        if (assumed.isNotEmpty()) {
            println()
            println("被跳过的用例（前 20 条）:")
            assumed.take(20).forEach { println("  - ${it.testHeader}") }
            if (assumed.size > 20) println("  ... 另 ${assumed.size - 20} 条")
        }
        if (real.isNotEmpty()) {
            println()
            println("失败用例:")
            real.forEach {
                println("  ✗ ${it.testHeader}")
                println("    ${it.message?.lineSequence()?.firstOrNull() ?: ""}")
            }
        }
        println("=".repeat(64))
        if (real.isNotEmpty()) kotlin.system.exitProcess(1)
    }

    /** JUnit4 里「Assume 失败」表现为一个带 `AssumptionViolatedException` 的 failure。 */
    private fun Failure.isAssumptionFailure(): Boolean =
        exception is org.junit.internal.AssumptionViolatedException ||
            exception.javaClass.name.contains("AssumptionViolated")
}
