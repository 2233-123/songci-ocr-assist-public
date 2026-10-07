package com.songci.assist

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 端上事件日志（不依赖 adb / logcat）。
 *
 * 真机排查时最缺的就是"事情按什么顺序发生"：投屏是什么时候建的、什么时候被
 * 系统回收、期间服务有没有被重新拉起。这个环形缓冲把最近的事件留在内存里，
 * 由引导页直接显示/导出。
 *
 * ### 两个缓冲，各管一件事
 *
 * 早先只有一个 60 条的环形缓冲，结果**每秒 8 帧的计时日志 7 秒就把它冲满了**，
 * 真正要看的「这一帧花在哪一段」反而被挤掉。所以拆成两个：
 *
 * - [events]：混杂事件（投屏/授权/点击等），**低频**，[EVENT_CAPACITY] 条足够，给界面看
 * - [timings]：每帧的逐段耗时，**高频但必须留得住**，单独一个大缓冲
 *
 * [dump] 会把两者合并输出，供「导出日志」使用。
 */
object EventLog {

    /** 混杂事件（含界面显示）保留条数 */
    private const val EVENT_CAPACITY = 400

    /** 帧计时保留条数（8fps 下约 2 分钟，足够覆盖一次完整测试） */
    private const val TIMING_CAPACITY = 1000

    /** 帧计时日志的 tag 前缀，用来分流 */
    private const val FRAME_TAG_PREFIX = "帧"

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val events = ArrayDeque<String>()

    private val timings = ArrayDeque<String>()

    private var startedAt = SystemClock.elapsedRealtime()

    /** 相对启动时刻的毫秒数（单调时钟，不受系统时间调整影响） */
    @Synchronized
    fun log(tag: String, message: String = "") {
        val rel = SystemClock.elapsedRealtime() - startedAt
        val line = "%s  +%6dms  %-18s %s".format(
            timeFormat.format(Date()),
            rel,
            tag,
            message,
        )
        if (tag.startsWith(FRAME_TAG_PREFIX)) {
            // 帧计时走独立缓冲，避免被高频日志挤掉
            timings.addLast(line)
            while (timings.size > TIMING_CAPACITY) timings.removeFirst()
        } else {
            events.addLast(line)
            while (events.size > EVENT_CAPACITY) events.removeFirst()
        }
        android.util.Log.i("SongCiEvents", line)
    }

    /** 界面展示用：只给混杂事件（帧计时太多，界面放不下） */
    @Synchronized
    fun dump(): String = events.joinToString("\n")

    /** 导出用：混杂事件 + 全部帧计时（按时间顺序） */
    @Synchronized
    fun export(): String {
        val sb = StringBuilder()
        sb.append("=== 混杂事件（${events.size} 条）===\n")
        events.forEach { sb.append(it).append('\n') }
        sb.append("\n=== 帧计时（${timings.size} 条，含逐段耗时）===\n")
        timings.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        events.clear()
        timings.clear()
        startedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun isEmpty(): Boolean = events.isEmpty() && timings.isEmpty()
}
