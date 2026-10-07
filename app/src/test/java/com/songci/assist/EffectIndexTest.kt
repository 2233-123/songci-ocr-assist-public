package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **词句效果索引**（`effects.json`）—— 功能「认出首句后显示该词的效果」。
 *
 * ### 用户口径
 * - **不显示「词元」**（加词元的不显示）
 * - 其余效果全保留（含「歌板」）
 * - 显示形式：名称 + 数值
 * - **只认到首句就显示**（不必等找到气泡）
 *
 * ### 数据来源（见 `tools/gen_effect_index.py`）
 * - 首句：`SongCiVerseConfig.FullVerseLineList[0]`
 * - 效果名：`EffectTypeConfig.EffectName`
 * - 数值：`SongCiVerseConfig.EffectParamList`
 *
 * ### 公开仓库会跳过本测试
 *
 * `effects.json` 是**生成物且含游戏数据**（首句 + 效果名），公开仓库**不分发**。
 * 所以这里的每个用例都通过 [Fixtures.effectsOrSkip] 取数据 —— 文件缺失时
 * **自动跳过**（`Assume`），不会让 CI 变红。这与 [MatchBenchmark] 的做法一致。
 *
 * ### 关键约束
 * 效果表**按索引首句字面量作 key**，运行时是纯等值查表。所以：
 * 「效果表 key 集合 == 索引 head 集合」是必须成立的硬约束 —— 本测试守护它。
 * （生成期负责把游戏侧「首句截断到 12 字」的对齐算好并固化，运行时不模糊匹配。）
 */
class EffectIndexTest {

    private val index = Fixtures.index()
    private val effects: EffectIndex get() = Fixtures.effectsOrSkip()

    // ---------------------------------------------------------------- 数据一致性

    @Test
    fun `效果表的 key 集合必须与索引 head 集合完全一致`() {
        val heads = index.verses.map { it.head }.toSet()
        val keys = index.verses.map { it.head }.filter { effects.forHead(it).isNotEmpty() }.toSet()
        assertEquals("索引里有首句查不到效果，效果表需要重新生成", heads, keys)
    }

    @Test
    fun `每条首句都能查到效果`() {
        val missing = index.verses.map { it.head }.filter { effects.forHead(it).isEmpty() }
        assertTrue("这些首句没有效果数据：$missing", missing.isEmpty())
    }

    @Test
    fun `词元必须不在效果表里（用户要求不显示）`() {
        val hasYuan = index.verses.any { v ->
            effects.forHead(v.head).any { it.name == "词元" }
        }
        assertTrue("「词元」不该被显示（用户口径）", !hasYuan)
    }

    @Test
    fun `不认识的词句查表应返回空列表而不是报错`() {
        assertTrue(effects.forHead("这句词根本不存在").isEmpty())
    }

    /**
     * **无条件用例**：验证「缺失即跳过」机制本身。
     *
     * 它不依赖 `effects.json`，所以在公开仓库（不含该文件）里也会**真正执行**，
     * 从而保证：
     * - 缺文件时 [Fixtures.effectsOrNull] 返回 null（而不是抛异常把 CI 搞红）
     * - 上面那些用例的跳过是**按预期**发生的，不是被别的原因掩盖
     */
    @Test
    fun `效果表缺失时取值应为 null 而不是抛异常`() {
        val e = Fixtures.effectsOrNull()
        if (e == null) {
            println("    本仓库不含 effects.json → 依赖它的用例会跳过（公开仓库正是这种状态）")
        } else {
            println("    本仓库含 effects.json（${e.size} 条）→ 依赖它的用例会正常执行")
        }
        // 两种状态都合法，这里只要求「不抛异常」
        assertTrue("取值为 null 或有效对象都算正常", e == null || e.size > 0)
    }

    // ---------------------------------------------------------------- 内容性质

    @Test
    fun `每条效果都有名称且数值不为零`() {
        for (v in index.verses) {
            for (e in effects.forHead(v.head)) {
                assertTrue("${v.head} 的效果名不该为空", e.name.isNotBlank())
                assertTrue("${v.head} 的 ${e.name} 数值不该为 0", e.value != 0.0)
            }
        }
    }

    @Test
    fun `效果名称只应是游戏实际用到的那几种`() {
        // 这几种来自 EffectTypeConfig 里 SongCiVerseConfig 用到的 8 个 ID
        // （词元已在生成期排掉，所以这里 7 种）
        val allowed = setOf("民心", "战斗力", "军心", "腐化", "威望", "发展年数", "歌板")
        val seen = index.verses.flatMap { effects.forHead(it.head) }.map { it.name }.toSet()
        assertEquals("出现了预期外的效果名", emptySet<String>(), seen - allowed)
        assertEquals("应有 7 种效果（8 种里排掉词元）", allowed, seen)
    }

    @Test
    fun `label 格式应为名称加数值`() {
        assertEquals("民心-10", PaiEffect("民心", -10.0, 51).label())
        assertEquals("战斗力+2", PaiEffect("战斗力", 2.0, 204).label())
        assertEquals("歌板+5", PaiEffect("歌板", 5.0, 32510).label())
        // 小数不丢精度
        assertEquals("发展年数+1", PaiEffect("发展年数", 1.0, 757).label())
    }

    // ---------------------------------------------------------------- 真机现场

    @Test
    fun `真机现场_甚矣吾衰矣应能查到军心与战斗力`() {
        // 日志 173521/17:34 那条：首句甚矣吾衰矣（贺新郎，id=85）
        // 实际效果来自游戏数据：军心+4 / 战斗力+2
        val e = effects.forHead("甚矣吾衰矣")
        assertTrue("应有效果，实际 $e", e.isNotEmpty())
        println("    甚矣吾衰矣 -> ${e.joinToString(" ｜ ") { it.label() }}")
        assertEquals(listOf("军心+4", "战斗力+2"), e.map { it.label() })
    }

    @Test
    fun `真机现场_怒发冲冠应是民心减10与战斗力加10`() {
        val e = effects.forHead("怒发冲冠凭栏处潇潇雨歇")
        println("    怒发冲冠 -> ${e.joinToString(" ｜ ") { it.label() }}")
        assertEquals(
            listOf("民心-10", "战斗力+10"),
            e.map { it.label() },
        )
    }

    // ---------------------------------------------------------------- 出框不依赖效果

    @Test
    fun `没有效果表时出框仍然正常`() {
        // 效果是可选增强：缺了它必须只影响显示，不影响匹配
        val p = FramePipeline(
            ocr = object : OcrEngine {
                override fun recognize(frameWidth: Int, frameHeight: Int) =
                    OcrFrameResult(emptyList(), frameWidth, frameHeight)
            },
            index = { index },
            effects = { null },          // ← 模拟效果表缺失
            clock = { 1_000_000L },
        )
        val blocks = listOf(
            TextBlock.normalized("明月别枝惊鹊清风半夜鸣蝉", 0.30f, 0.19f, 0.70f, 0.23f),
            TextBlock.normalized("西江月", 0.30f, 0.57f, 0.70f, 0.61f),
        )
        val r = p.matchNow(blocks, 2608, 1200, 1_000_000L, false, 2608, 1200, 0)
        println("    效果表缺失时 -> $r")
        assertTrue("没有效果表也必须能出框，实际 $r", r.startsWith("命中"))
    }

    @Test
    fun `有效果表时出框结论会带上效果`() {
        val p = FramePipeline(
            ocr = object : OcrEngine {
                override fun recognize(frameWidth: Int, frameHeight: Int) =
                    OcrFrameResult(emptyList(), frameWidth, frameHeight)
            },
            index = { index },
            effects = { effects },
            clock = { 1_000_000L },
        )
        val blocks = listOf(
            TextBlock.normalized("明月别枝惊鹊清风半夜鸣蝉", 0.30f, 0.19f, 0.70f, 0.23f),
            TextBlock.normalized("西江月", 0.30f, 0.57f, 0.70f, 0.61f),
        )
        val r = p.matchNow(blocks, 2608, 1200, 1_000_000L, false, 2608, 1200, 0)
        println("    有效果表时 -> $r")
        assertTrue("应命中", r.startsWith("命中"))
        val out = p.lastOutcomeForTest
        assertNotNull("应能拿到结论对象", out)
        val eff = when (out) {
            is FrameOutcome.Hit -> out.effects
            is FrameOutcome.PaiOnly -> out.effects
            else -> emptyList()
        }
        assertTrue("结论里应带上效果（明月别枝惊鹊 -> 西江月）", eff.isNotEmpty())
        println("    效果: ${eff.joinToString(" ｜ ") { it.label() }}")
    }
}
