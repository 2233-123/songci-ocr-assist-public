package com.songci.assist

/**
 * 匹配过程中的诊断开关。
 *
 * 出框失败时最需要知道的是「顶部区域到底有哪些候选、各自得分多少」——
 * 只靠最终的 `无匹配` 无法区分"OCR 没读到首句"和"读到了但相似度不够"。
 *
 * 由 [CaptureService] 在调试面板打开时置为 true，避免正常运行时刷日志。
 */
object MatcherDebug {
    @Volatile
    var enabled: Boolean = false

    fun log(tag: String, message: String) {
        if (enabled) EventLog.log(tag, message)
    }
}
