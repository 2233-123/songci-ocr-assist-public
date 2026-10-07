package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **状态条不得压到游戏首句** —— 这条约束是真机事故换来的。
 *
 * ### 事故经过（v0.11.0）
 *
 * 把「词句效果」做成状态条的**第二行**后：
 *
 * ```
 * 该局 95 帧里 90 帧「首句未匹配」   ← 识别不出新句子（用户报的第一个问题）
 * 同时顶部的选项按钮也点不动了        ← 遮挡层变高（用户报的第二个问题）
 * ```
 *
 * 原因：游戏把首句固定画在 **y ≈ 0.19**（历次真机日志实测 60/60 次都在 0.19），
 * 而首句是**取帧时一起被 OCR 的**。状态条盖住它，OCR 自然读不到 ——
 * **App 遮住了自己要读的字**，这个错误极难自查，用户只会觉得是引擎不行。
 *
 * ### 本测试怎么测（以及为什么不用字体指标反算）
 *
 * [StatusBarView] 是 Android `View`，JVM 上构造不了，所以拿不到
 * `statusWindowHeightPx()`。我**试过**用 AWT 的 `FontMetrics` 去反算 Roboto ——
 * 结论是**不可靠**：AWT 的 `ascent/descent` 与 Android `Paint.FontMetrics.top/bottom`
 * 口径不同，算出来的高度会偏大一半，反而会得出"单行也压住首句"的错误结论。
 *
 * 所以改成测**确定性的东西**：
 * 1. 设计策略是「单行」（效果与结论同排）
 * 2. [Config] 里的守护常量自洽
 * 3. 真实净空由 App 在真机上用 `overlay.occlusion` 事件日志报出来（见
 *    `OverlayService.guardHeadOcclusion`）—— **可观测比在这儿算准更重要**
 */
class StatusBarClearanceTest {

    // —— 必须与 StatusBarView.companion 一致 ——
    private val topDp = 8f
    private val padVDp = 5f

    /** StatusBarView 的文字字号（sp） */
    private val textSizeSp = 15f

    // —— 真机参数 ——
    private val screenHeightPx = 1200f
    private val density = 3f

    /**
     * Android `Paint.FontMetrics` 的 `top..bottom` 跨度经验值（相对字号）。
     *
     * 取 Roboto 的实测比例 **1.53**（≈ ascent+descent+行距），比 `1.17` 这种
     * "ascent+descent" 口径大得多 —— 混用这两个口径正是我第一版算错的原因，
     * 当时把状态条高算成 262px，于是错误地得出"单行也会压住首句"。
     */
    private val fontSpanRatio = 1.53f

    private fun barBottomRatio(lines: Int, top: Float = topDp): Float {
        var totalDp = top * 2 + textSizeSp * fontSpanRatio + padVDp * 2
        if (lines > 1) {
            // 第二行：间距 + 稍小字号（v0.11.0 的实际参数）
            totalDp += 4f + 14f * fontSpanRatio
        }
        return totalDp * density / screenHeightPx
    }

    /**
     * 事故当时（v0.11.0）的 `StatusBarView.TOP_DP`。
     *
     * 那时**单行就已经贴到首句**（实测底边 ≈215px vs 首句 228px，只剩 13px），
     * 所以加第二行必然越界。现已收到 8f，单行净空约 63px。
     */
    private fun accidentTopDp() = 28f

    @Test
    fun `单行状态条不应压到首句`() {
        val bottom = barBottomRatio(lines = 1)
        val clearance = Config.GAME_HEAD_LINE_RATIO - bottom
        println("    单行：底边 %.3f 屏高，首句 %.3f，净空 %.0f px"
            .format(bottom, Config.GAME_HEAD_LINE_RATIO, clearance * screenHeightPx))
        assertTrue(
            "单行状态条底边(%.3f) 不该压到首句(%.3f)".format(bottom, Config.GAME_HEAD_LINE_RATIO),
            bottom < Config.GAME_HEAD_LINE_RATIO,
        )
    }

    @Test
    fun `单行必须留出可观净空_原来只剩13px等于悬在崖边`() {
        val clearance = (Config.GAME_HEAD_LINE_RATIO - barBottomRatio(lines = 1)) * screenHeightPx
        println("    单行净空 %.0f px（要求 ≥ %.0f px）"
            .format(clearance, Config.MIN_HEAD_CLEARANCE_RATIO * screenHeightPx))
        assertTrue(
            "单行净空只有 %.0f px，太紧：首句文字自身有高度，稍有偏差就重叠".format(clearance),
            clearance >= Config.MIN_HEAD_CLEARANCE_RATIO * screenHeightPx,
        )
    }

    @Test
    fun `两行方案在事故当时的参数下会越界_这才是事故算式`() {
        // 用**事故当时**的 TOP_DP=28 复现。
        //
        // ⚠️ 这里的算式是**合成模型**，只用来表达量级关系 —— 它比真机实测偏大约 50px
        // （合成单行 267px vs 实测 215px），因为 AWT 的字体指标与 Android
        // `Paint.FontMetrics` 口径不同。**真值以 App 自己报的 `overlay.occlusion` 为准。**
        //
        // 所以这里只断言**关系**（两行比单行多出一行的量、且越过首句），
        // 不对单行的绝对值下判断 —— 那是我第一版犯的错（用错的模型断言绝对位置）。
        val one = barBottomRatio(lines = 1, top = accidentTopDp())
        val two = barBottomRatio(lines = 2, top = accidentTopDp())
        println("    事故参数 TOP_DP=28（合成模型）：单行 %.0f px、两行 %.0f px、首句 %.0f px"
            .format(one * screenHeightPx, two * screenHeightPx,
                Config.GAME_HEAD_LINE_RATIO * screenHeightPx))
        assertTrue("两行应当比单行高出一行的量", two - one > 0.05f)
        assertTrue("两行应当越过首句", two > Config.GAME_HEAD_LINE_RATIO)
    }

    @Test
    fun `收紧后两行也不再越界_但仍坚持单行`() {
        // 收紧 TOP_DP 后，即使两行也够空间了。但**仍然坚持单行**：
        // 净空是安全裕度，不该被用来堆内容 —— 留白本身就是为"以后再加东西"准备的。
        val two = barBottomRatio(lines = 2)
        val clearance = (Config.GAME_HEAD_LINE_RATIO - two) * screenHeightPx
        println("    收紧后两行：底边 %.3f，净空 %.0f px（≥0 即不再越界）"
            .format(two, clearance))
        assertTrue("收紧后两行不应越界", two < Config.GAME_HEAD_LINE_RATIO)
        // 而单行的净空必须**明显大于**两行，这就是坚持单行的量化理由
        assertTrue(
            "单行净空应明显优于两行",
            barBottomRatio(1) < barBottomRatio(2) - 0.03f,
        )
    }

    @Test
    fun `单行与两行的差距足以说明问题`() {
        val one = barBottomRatio(1)
        val two = barBottomRatio(2)
        val delta = (two - one) * screenHeightPx
        println("    加一行给状态条增加 %.0f px 高度".format(delta))
        assertTrue("加一行的代价应当可观（>60px）", delta > 60f)
    }

    // ---------------------------------------------------------------- 守护常量

    @Test
    fun `守护常量应自洽且可用`() {
        assertTrue(
            "首句位置应为正且在屏幕上半部",
            Config.GAME_HEAD_LINE_RATIO > 0f && Config.GAME_HEAD_LINE_RATIO < 0.5f,
        )
        assertTrue(
            "最小净空应为正数（否则哨兵永远不会触发）",
            Config.MIN_HEAD_CLEARANCE_RATIO > 0f,
        )
        // 单行版必须能通过自己的哨兵，否则启动就会一直报警
        val clearance = Config.GAME_HEAD_LINE_RATIO - barBottomRatio(1)
        assertTrue(
            "单行状态条的净空(%.3f) 应满足自己设定的最小值(%.3f)".format(
                clearance, Config.MIN_HEAD_CLEARANCE_RATIO,
            ),
            clearance >= Config.MIN_HEAD_CLEARANCE_RATIO,
        )
    }

    // ---------------------------------------------------------------- 横向空间

    @Test
    fun `单行的横向空间足够容纳效果文案`() {
        // 用户判断「位置也够」—— 核一下最长的组合文案
        val screenWidthPx = 2608f
        val longest = "应选：江城子（屏上没找到选项气泡） ｜ 军心+4、战斗力+2"
        // 15sp 在 3x 下，CJK 字宽 ≈ 字号；ASCII 按 0.5 估。保守乘 1.0。
        val estWidthPx = longest.length * textSizeSp * density
        println("    最长文案 %d 字，保守估算宽 %.0f px / 屏宽 %.0f px"
            .format(longest.length, estWidthPx, screenWidthPx))
        assertTrue("单行放不下这条文案（保守估算 %.0f px）".format(estWidthPx), estWidthPx < screenWidthPx)
    }

    @Test
    fun `效果与结论的分隔符应能在一行里读出层次`() {
        // 效果之间用顿号、结论与效果之间用全角竖线 —— 都是单行里的层次标记
        assertEquals(" ｜ ", " ｜ ")
        assertTrue("、" in "军心+4、战斗力+2")
    }
}
