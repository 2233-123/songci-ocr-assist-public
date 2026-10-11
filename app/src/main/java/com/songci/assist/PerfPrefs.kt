package com.songci.assist

import android.content.Context

/**
 * **性能设置**的持久化（用户需求）。
 *
 * | 设置项 | 取值 | 说明 |
 * |---|---|---|
 * | 取帧档位 | [Config.FPS_CHOICES]（1/2/4/8/15/不限速） | 决定帧间隔；`0` = 不加时间闸 |
 * | 命中后降速 | `Boolean` | 高亮框已指向正确选项后放慢取帧，等玩家点击再恢复 |
 *
 * ### 为什么放到 SharedPreferences 而不是 Config
 *
 * `Config` 是**编译期常量**（用于阈值、比例这类不该变的东西）。
 * 这两项是**用户可调**的，必须在运行时读写、且重启后保留。
 *
 * 读写都只用 `Context`，不持有静态引用 —— 避免内存泄漏，也方便单测单独测
 * [Config.intervalMsForFps] 的映射（纯函数，不依赖 Context）。
 */
object PerfPrefs {

    /** 与 [OverlayService.PREFS] 同一份偏好文件（避免多份偏好分散） */
    private const val PREFS = OverlayService.PREFS

    private const val KEY_FPS = "perf_fps"
    private const val KEY_SLOWDOWN = "perf_slowdown_after_hit"

    /**
     * 读用户选定的取帧档位。
     *
     * 非法值（不在 [Config.FPS_CHOICES] 里）一律回落到 [Config.DEFAULT_FPS] ——
     * 防止旧版本残留值或手改偏好把间隔算成负数/除零。
     */
    fun fps(context: Context): Int {
        val raw = prefs(context).getInt(KEY_FPS, Config.DEFAULT_FPS)
        return if (raw in Config.FPS_CHOICES) raw else Config.DEFAULT_FPS
    }

    fun setFps(context: Context, fps: Int) {
        val safe = if (fps in Config.FPS_CHOICES) fps else Config.DEFAULT_FPS
        prefs(context).edit().putInt(KEY_FPS, safe).apply()
    }

    /** 命中后是否降速等待玩家点击（默认开 —— 这是用户明确要的省电行为） */
    fun slowdownAfterHit(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SLOWDOWN, true)

    fun setSlowdownAfterHit(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SLOWDOWN, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
