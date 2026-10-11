package com.songci.assist

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **没匹配到词句时，长条状态条不得消失** —— 这条是真机反馈换来的。
 *
 * ### 用户报告
 *
 * > 「长条框往往和高亮框同时出现同时消失，**在屏幕上没有匹配到对应词句的时候
 * > 长条框就是会消失**」
 *
 * ### 根因（两行代码）
 *
 * `OverlayService.applyOutcome` 在 `FrameOutcome.Empty` / `NoMatch` 分支里：
 *
 * ```kotlin
 * view.clear()            // 高亮框消失
 * setStatusText(null)     // 状态条文案清空
 * ```
 *
 * 而 `StatusBarView.onDraw` 的判据曾经是：
 *
 * ```kotlin
 * if (!showing || t.isNullOrBlank()) return   // ← 一个像素都不画
 * ```
 *
 * 于是"没匹配到"就等于"状态条不见了"，**而且必然和高亮框同时**（同一个分支）。
 *
 * ### 为什么这是 bug 而不是设计
 *
 * "没匹配到"恰恰是最需要拖动状态条的时刻（玩家得先把它挪开再等下一次匹配），
 * 一个看不见的窗口既找不到、也没法抓 —— 这与"状态条可任意拖动"的需求直接冲突。
 *
 * ### 本测试测什么
 *
 * [StatusBarView] 是 Android `View`，JVM 上构造不了（见 [StatusBarClearanceTest]
 * 里同样的限制），所以这里锁**源码级的不变式**：占位态必须存在，
 * 且绘制入口的判断必须把占位态算进去。
 *
 * 这样以后有人把 `idleNotice` 删掉、或者把 `onDraw` 的判据改回 `!showing` 就直接早退，
 * 测试会失败并指向这段历史。
 */
class StatusBarIdleNoticeTest {

    private fun statusBarSource(): File {
        // 依次尝试常见工作目录：Gradle 以工程根为 cwd，IDE 可能以 app/ 为 cwd
        val candidates = listOf(
            File("app/src/main/java/com/songci/assist/StatusBarView.kt"),
            File("src/main/java/com/songci/assist/StatusBarView.kt"),
            File("../app/src/main/java/com/songci/assist/StatusBarView.kt"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 StatusBarView.kt（尝试过：${candidates.joinToString { it.path }}）")
    }

    @Test
    fun `占位态存在_且有独立的判据`() {
        val src = statusBarSource().readText()
        assertTrue(
            "StatusBarView 必须有 idleNotice 属性（没匹配到词句时的占位态）",
            src.contains("var idleNotice"),
        )
        assertTrue(
            "必须有 showingIdle 判据，且它要求 text 为空 —— 否则会与真实结论混在一起",
            src.contains("showingIdle"),
        )
    }

    @Test
    fun `onDraw_不得在无文案时直接早退`() {
        val src = statusBarSource().readText()
        val onDraw = src.substringAfter("override fun onDraw(")
        assertNotNull(onDraw)

        // 曾经那行：`if (!showing || t.isNullOrBlank()) return`
        assertTrue(
            "onDraw 的早退条件必须同时考虑 showing 与 showingIdle —— " +
                "曾经写成 `if (!showing || t.isNullOrBlank()) return`，" +
                "导致没匹配到词句时整条状态条一个像素都不画（用户报的'消失'）",
            onDraw.contains("if (!showing && !showingIdle) return"),
        )
        assertTrue(
            "不得再出现只判 showing 的早退（那会让占位态失效）",
            !onDraw.contains("if (!showing || t.isNullOrBlank()) return"),
        )
    }

    @Test
    fun `占位态与真实结论必须样式可区分`() {
        val src = statusBarSource().readText()
        // 占位必须是"暗底 + 灰字"，不能与真实结论同色 —— 否则用户会误以为匹配到了
        assertTrue("占位态底色必须独立于真实态", src.contains("IDLE_BG_COLOR"))
        assertTrue("占位态文字色必须独立于真实态", src.contains("IDLE_TEXT_COLOR"))
        assertTrue(
            "占位文案要短而中性（当前是 ···），不能像结论那样长",
            src.contains("IDLE_TEXT"),
        )
    }
}
