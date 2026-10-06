package com.songci.assist

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 端上事件日志（不依赖 adb / logcat）。
 *
 * 真机排查时最缺的就是"事情按什么顺序发生"：投屏是什么时候建的、什么时候被
 * 系统回收、期间服务有没有被重新拉起。这个环形缓冲把最近 [CAPACITY] 条事件
 * 留在内存里，由引导页直接显示，用户截图即可。
 */
object EventLog {

    private const val CAPACITY = 60

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val events = ArrayDeque<String>()

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
        events.addLast(line)
        while (events.size > CAPACITY) events.removeFirst()
        android.util.Log.i("SongCiEvents", line)
    }

    @Synchronized
    fun dump(): String = events.joinToString("\n")

    @Synchronized
    fun clear() {
        events.clear()
        startedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun isEmpty(): Boolean = events.isEmpty()
}
