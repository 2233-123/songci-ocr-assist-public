package com.songci.assist

import org.junit.Test

/**
 * 用**真机日志里的原始块**直接跑当前实现，确认修复是否真的生效。
 *
 * 之前我一直在 Python 里复刻算法，但复刻可能与 Kotlin 实现有偏差。
 * 这里贴的是日志原文（第 161 / 155 帧的 `frame.ocr` 行），不做任何加工。
 */
class LiveFrameProbe {

    private val index = VerseIndex.fromJson(Fixtures.loadJson())

    /** 直接按「y=0.19」这类日志坐标造块 */
    private fun fromLog(vararg pairs: Pair<String, Float>) = pairs.map { (t, y) ->
        TextBlock.normalized(t, 0.30f, y, 0.70f, y + 0.04f)
    }

    @Test
    fun `第161帧_一曲新词酒一杯_应命中浣溪沙`() {
        val blocks = fromLog(
            "词牌" to 0.06f, "词元22573" to 0.06f, "採律O" to 0.08f,
            "择律收益623" to 0.10f, "属性" to 0.10f,
            "应选蝶恋花屏上没找到选项气泡" to 0.11f, "N04" to 0.16f,
            "首句曲新词酒一杯去年天气旧亭台阳西下几时回" to 0.19f,
            "豪放词情" to 0.23f, "婉约词情" to 0.34f, "词" to 0.39f,
            "声声慢" to 0.43f, "清平乐" to 0.43f, "浣溪沙" to 0.57f,
        )
        val r = Matcher.scanHead(blocks, index)
        println("    [第161帧] -> $r")
        when (r) {
            is HeadMatch.Hit -> println("    命中 ${r.verse.pai} sim=%.3f".format(r.similarity))
            else -> println("    ★ 未命中")
        }
    }

    @Test
    fun `第155帧_残留上一题首句_当前实现的选择`() {
        // 真机日志 155 帧顶部第一条就是上一题的残留
        val blocks = fromLog(
            "周牌" to 0.06f, "词元22573" to 0.06f, "捍律" to 0.08f,
            "庭院深深深几许杨柳堆烟帘" to 0.06f,
            "择律收益623" to 0.10f, "104" to 0.16f,
            "首句一曲新词酒一杯去年天" to 0.19f,
            "豪放词情" to 0.23f, "婉约词情" to 0.34f, "词" to 0.39f,
            "声声慢" to 0.43f, "清平乐" to 0.43f,
        )
        val r = Matcher.scanHead(blocks, index)
        println("    [第155帧] -> $r")
        when (r) {
            is HeadMatch.Hit -> println("    命中 ${r.verse.pai} sim=%.3f  (正确应为 浣溪沙)".format(r.similarity))
            else -> println("    未命中")
        }
    }
}
