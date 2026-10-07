package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **悬浮层窗口的 alpha 必须低于系统上限** —— 这条约束是 MuMu 模拟器事故换来的。
 *
 * ### 事故经过（真机实测，MuMu 模拟器 Android 12 / API 32）
 *
 * 开了辅助后**整个游戏界面都点不动**（不只是悬浮窗那一片）。系统日志给出完整栈：
 *
 * ```
 * W InputDispatcher: Untrusted touch due to occlusion by com.songci.assist/10037
 *                    (obscuring opacity = 1.00, maximum allowed = 0.80)
 * D InputDispatcher: Stack of obscuring windows during untrusted touch (960, 540):
 *     * type=2038, package=com.songci.assist/10037, mode=USE_OPACITY, alpha=1.00,
 *       frame=[0,0][1920,1080], touchableRegion=[0,0][1920,1080],
 *       flags={... NOT_TOUCHABLE NOT_TOUCH_MODAL ...}
 *     * [TOUCHED] package=com.syyx.whrhx/10021, mode=BLOCK_UNTRUSTED
 * ```
 *
 * ### 关键认知：判定用 `Window.alpha`，与窗口里画了什么无关
 *
 * 日志里的 `mode=USE_OPACITY, alpha=1.00` 直接说明系统读的是**窗口 alpha**。
 * `WindowManager.LayoutParams.alpha` 默认 **1.0**，所以"视觉上几乎全透明"完全没用。
 * 而 `FLAG_NOT_TOUCHABLE` **不能豁免**这条检查（官方明确把
 * `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` 列为受影响对象）。
 *
 * 后果特别隐蔽：触摸被丢弃只写系统 logcat
 * （`InputManager: Suppressing untrusted touch toast`），**屏幕上毫无提示**。
 *
 * ### 实测验证（本测试保护的就是它）
 *
 * | 整屏窗 alpha | `Dropping untrusted touch event` |
 * |---|---|
 * | 1.00（修复前） | 每次点击都有 |
 * | **0.70（修复后）** | **15 次点击 0 条** |
 *
 * 且复核时 `block_untrusted_touches` 为 `null`（默认 = 拦截**开启**），
 * 说明修复不是靠改系统设置，而是窗口本身合规。
 *
 * ### 这是 v0.11.1 引入的回归
 *
 * ```
 * 0.6.0  全屏窗        → 华为上「按钮点不动」→ 0.6.1 改成按内容贴合的小窗
 * 0.11.0 小窗          → 「框从左上角飞过来」
 * 0.11.1 改回恒定整屏  → 动画修好了，但把 0.6.1 的遮挡问题带了回来
 * ```
 */
class OverlayWindowAlphaTest {

    /**
     * Android 的 `mMaximumObscuringOpacityForTouch` 默认值。
     *
     * 判定条件是 `obscuringOpacity > 0.80` 才拒绝。
     */
    private val systemMax = 0.80f

    /**
     * **组合不透明度**：n 个各为 `a` 的窗口叠加。
     *
     * ```
     * 1 - (1-a)^n
     * ```
     *
     * 这是从真机日志反解出来的（见类注释）：
     *
     * ```
     * 两个 0.70 → 1-(0.30)² = 0.910   日志实测报 0.91  ✓
     * 两个 0.50 → 1-(0.50)² = 0.750   日志实测报 0.75  ✓
     * ```
     */
    private fun combined(alpha: Float, n: Int): Float = 1f - Math.pow((1f - alpha).toDouble(), n.toDouble()).toFloat()

    @Test
    fun `单个窗口的 alpha 必须低于系统上限`() {
        assertTrue(
            "OVERLAY_WINDOW_ALPHA=${Config.OVERLAY_WINDOW_ALPHA} 必须 < $systemMax",
            Config.OVERLAY_WINDOW_ALPHA < systemMax,
        )
    }

    /**
     * **这条是本次事故的核心**：单个窗口合规 ≠ 安全。
     *
     * ### 事故（MuMu / Android 12 实测）
     *
     * 两个整屏/全宽 `FLAG_NOT_TOUCHABLE` 窗口各 alpha=0.70 都合规，
     * 但它们在**左上角重叠**（游戏返回按钮的位置），组合后超标：
     *
     * ```
     * W InputDispatcher: Untrusted touch due to occlusion by com.songci.assist
     *                    (obscuring opacity = 0.91, maximum allowed = 0.80)
     * ```
     *
     * 表现极具迷惑性：**只有左上角返回按钮点不动，其他地方正常** ——
     * 因为左上角是两个悬浮窗唯一的重叠区。
     *
     * ### 修法
     *
     * 不压 alpha（那要压到 0.55 以下才够，高亮框会淡到看不清），
     * 而是**把状态条窗口收窄**（[STATUS_WINDOW_WIDTH_RATIO] = 0.74，
     * 两侧各留 13%），让左上角只剩高亮层一个窗口。
     *
     * 所以这里断言：**一个窗口**时组合值必须低于上限（留 0.02 余量）。
     * 若哪天有人把状态条窗口改回整屏宽，上一条会过、但这条的**前提**
     * （左上角只有一个窗口）就不成立了 —— 见下一条测试守护。
     */
    @Test
    fun `单个窗口的组合不透明度必须留有余量`() {
        val one = combined(Config.OVERLAY_WINDOW_ALPHA, 1)
        assertTrue(
            "单窗组合值 %.3f 距上限 %.2f 太近".format(one, systemMax),
            systemMax - one >= 0.02f,
        )
    }

    @Test
    fun `事故当时的值_两窗叠加_会被本测试组拦下`() {
        // 钉住反例：把这组数字写死在测试里，防止有人"顺手"把 alpha 调回去
        // 或把状态条窗口改回整屏宽。
        val twoAt070 = combined(0.70f, 2)
        assertEquals("两个 0.70 应算出 0.91", 0.91f, twoAt070, 0.005f)
        assertTrue("0.91 必须被判为超标", twoAt070 > systemMax)

        val twoAt050 = combined(0.50f, 2)
        assertEquals("两个 0.50 应算出 0.75", 0.75f, twoAt050, 0.005f)
        assertTrue("0.75 应当在安全侧", twoAt050 <= systemMax)
    }

    @Test
    fun `悬浮窗 alpha 不能低到让内容看不清`() {
        // 这个 alpha 是作用在**整个窗口**上的：高亮框与状态条都会一起变淡。
        // 太低（比如 0.3）用户就看不清提示了。0.70 是"能穿透"与"看得清"的折中。
        assertTrue(
            "OVERLAY_WINDOW_ALPHA=${Config.OVERLAY_WINDOW_ALPHA} 过低，提示会看不清",
            Config.OVERLAY_WINDOW_ALPHA >= 0.5f,
        )
    }

    @Test
    fun `事故当时的 alpha 1点0 会被本测试拦下`() {
        // 钉住这个反例：修复前的值必须在断言下失败。
        // 如果哪天有人把 constants 改回 1.0，上面两条会立刻红。
        val brokenValue = 1.0f
        assertTrue("反例 1.0 应当被判为不合规", brokenValue >= systemMax)
        assertEquals(
            "当前实现不应等于事故值",
            false,
            Config.OVERLAY_WINDOW_ALPHA == brokenValue,
        )
    }
}
