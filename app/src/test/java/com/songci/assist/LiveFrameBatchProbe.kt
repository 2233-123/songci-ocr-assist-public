package com.songci.assist

import org.junit.Test

/**
 * 用**真机日志里 frame.ocr 的原始块**，量化 v0.8.0 匹配修复的实际收益。
 *
 * 日志来自 v0.7.2（修复前），里面大量 `首句未匹配 → 二次:限流跳过`。
 * 这些帧的块文本被完整打印在 `frame.ocr` 行里，直接贴进来跑当前实现，
 * 就能知道：**修复后这些帧能不能认出首句**（而不用再录一次屏）。
 */
class LiveFrameBatchProbe {

    private val index = VerseIndex.fromJson(Fixtures.loadJson())

    /** `y=0.06:词牌 ｜ y=0.19:首句...` → 块列表 */
    private fun parse(dump: String): List<TextBlock> =
        dump.split("｜").mapNotNull { seg ->
            val m = Regex("y=([\\d.]+):(.*)").find(seg.trim()) ?: return@mapNotNull null
            val y = m.groupValues[1].toFloat()
            TextBlock.normalized(m.groupValues[2].trim(), 0.30f, y, 0.70f, y + 0.04f)
        }

    private fun probe(label: String, dump: String, expect: String?) {
        val r = Matcher.match(parse(dump), index)
        val got = when (r) {
            is MatchResult.Hit -> "${r.verse.pai} 出框"
            is MatchResult.PaiOnly -> "${r.verse.pai} 仅词牌"
            else -> "未匹配"
        }
        val ok = expect == null || got.startsWith(expect)
        println("    %-6s %-22s %s".format(if (ok) "OK" else "★", label, got))
    }

    @Test
    fun `真机日志里的关键帧_修复后的判定`() {
        // 第 161 帧：用户报告的场景（正是它触发了这次排查）
        probe(
            "帧161",
            "y=0.06:词牌 ｜ y=0.06:词元22573 ｜ y=0.08:採律O ｜ y=0.10:择律收益623 ｜ " +
                "y=0.10:属性 ｜ y=0.11:应选蝶恋花屏上没找到选项气泡 ｜ y=0.16:N04 ｜ " +
                "y=0.19:首句曲新词酒一杯去年天气旧亭台阳西下几时回 ｜ y=0.23:豪放词情 ｜ " +
                "y=0.27:60 ｜ y=0.34:婉约词情 ｜ y=0.39:词 ｜ y=0.43:声声慢 ｜ y=0.43:清平乐 ｜ " +
                "y=0.57:浣溪沙",
            "浣溪沙",
        )
        // 第 181 帧：同一题稍后（「一」被读成「1」）
        probe(
            "帧181",
            "y=0.06:词牌 ｜ y=0.06:词元22573 ｜ y=0.10:择律收益623 ｜ y=0.11:属性 ｜ " +
                "y=0.11:应选蝶恋花屏上没找到选项气泡 ｜ y=0.16:J04 ｜ " +
                "y=0.19:首句1曲新词酒一杯去年天气旧亭台阳西下几时回 ｜ y=0.23:豪放词情 ｜ " +
                "y=0.27:60 ｜ y=0.34:婉约词情 ｜ y=0.39:词 ｜ y=0.43:声声慢 ｜ y=0.43:清平乐 ｜ " +
                "y=0.57:浣溪沙",
            "浣溪沙",
        )
        // 第 21 帧：青玉案那一题（首句读得比较全）
        probe(
            "帧21",
            "y=0.06:词牌 ｜ y=0.07:词元21668 ｜ y=0.08:擇律0 ｜ y=0.10:择律收益609 ｜ " +
                "y=0.10:属性 ｜ y=0.11:东凤夜放花千树更吹落星雨一青玉案085 ｜ y=0.16:N04 ｜ " +
                "y=0.23:豪放词情 ｜ y=0.33:词牌楼阁 ｜ y=0.34:婉约词情 ｜ y=0.39:词 ｜ " +
                "y=0.71:宜写节序繁华而托意深运之作 ｜ y=0.76:弃疾以此比牌写千古名句",
            null,
        )
        // 第 141 帧：少年游那一题
        probe(
            "帧141",
            "y=0.06:词元22513 ｜ y=0.06:同牌 ｜ y=0.08:捍律 ｜ y=0.10:属性 ｜ " +
                "y=0.10:择律收益622 ｜ y=0.11:并刀如水吴盐胜雪纤手玻新橙少年游ノ100 ｜ " +
                "y=0.16:104 ｜ y=0.19:首句院深深深几许杨柳堆烟帘幕元重数 ｜ y=0.23:豪放词情 ｜ " +
                "y=0.27:66 ｜ y=0.34:婉约词情 ｜ y=0.39:词 ｜ y=0.44:蝶恋花",
            null,
        )
    }
}
