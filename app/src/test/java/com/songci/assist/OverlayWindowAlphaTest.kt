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
     * 判定条件是 `obscuringOpacity > 0.80` 才拒绝，所以 0.80 本身是临界可过 ——
     * 但我们**不能压线**，见下一条。
     */
    private val systemMax = 0.80f

    @Test
    fun `悬浮窗 alpha 必须严格低于系统上限`() {
        assertTrue(
            "OVERLAY_WINDOW_ALPHA=${Config.OVERLAY_WINDOW_ALPHA} 必须 < $systemMax，" +
                "否则 Android 12+ 会丢弃所有穿透到下层游戏的触摸",
            Config.OVERLAY_WINDOW_ALPHA < systemMax,
        )
    }

    @Test
    fun `悬浮窗 alpha 要留出余量_不能压线 0点80`() {
        // 0.80 是边界值：系统判定是 `> 0.80`，所以恰好 0.80 可能通过。
        // 但浮点表示、以及"多个窗口组合不透明度"的累加都可能把边界推过去，
        // 所以预留 0.10 余量。
        val margin = systemMax - Config.OVERLAY_WINDOW_ALPHA
        assertTrue(
            "余量只有 $margin，太贴近边界 0.80（浮点/叠加误差可能推过去）",
            margin >= 0.10f,
        )
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
