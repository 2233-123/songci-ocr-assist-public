package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护随包提交的索引 `app/src/main/assets/verses.json`。
 *
 * ### 为什么需要这个测试
 *
 * 仓库**不分发游戏数据**（data 目录下的原始 JSON 属于游戏素材，已 gitignore），
 * 因此 CI 里跑不了 `gen_verse_index.py`（它需要那两份原始 JSON），
 * 只能校验索引进版本的**一致性**（`check_index_committed.py`）。
 *
 * 于是「索引内容是否仍然合法」就缺少把关 —— 比如误提交了一个被截断、
 * 被手改或字段缺失的 `verses.json`，CI 是发现不了的。
 * 这个测试补上这一环：它直接读随包资源，检查索引的**结构与关键性质**。
 */
class CommittedIndexTest {

    private val index: VerseIndex = Fixtures.index()

    @Test
    fun `索引规模符合预期`() {
        // 与 README「数据资产」一致：99 条词句 / 40 个词牌
        assertEquals("词句条数", 99, index.verses.size)
        assertEquals("词牌个数", 40, index.paiList.size)
    }

    @Test
    fun `每条词的字段都完整合法`() {
        for (v in index.verses) {
            assertTrue("id 必须为正: ${v.id}", v.id > 0)
            assertTrue("首句不能为空: id=${v.id}", v.head.isNotEmpty())
            assertTrue("首句长度应 >= ${Config.MIN_HEAD_LEN}: ${v.head}", v.head.length >= Config.MIN_HEAD_LEN)
            assertTrue("词牌不能为空: id=${v.id}", v.pai.isNotEmpty())
            assertTrue("词人不能为空: id=${v.id}", v.poet.isNotEmpty())
            assertTrue("style 应为 1 或 2: id=${v.id} style=${v.style}", v.style in 1..2)
            // 首句必须是已规范化的形式（无标点/空白），否则精确查表会永远失配
            assertEquals(
                "首句应已规范化（无标点/空白）: id=${v.id}",
                v.head,
                VerseIndex.normalize(v.head),
            )
        }
    }

    @Test
    fun `id 与首句都不重复`() {
        val ids = index.verses.map { it.id }
        assertEquals("id 有重复", ids.size, ids.toSet().size)

        val heads = index.verses.map { it.head }
        assertEquals("首句有重复（会导致查表歧义）", heads.size, heads.toSet().size)
    }

    @Test
    fun `每个词牌都至少对应一条词`() {
        val usedPai = index.verses.map { it.pai }.toSet()
        val orphan = index.paiList - usedPai
        assertTrue("词牌表里有没被任何词句使用的项: $orphan", orphan.isEmpty())
    }

    @Test
    fun `每条首句都能精确查回自己`() {
        // 索引自洽性的最强检查：查表命中且是同一条
        for (v in index.verses) {
            val hit = index.lookup(v.head)
            assertTrue("查不到自己的首句: id=${v.id} head=${v.head}", hit != null)
            assertEquals("查到的不是自己: id=${v.id}", v.id, hit!!.id)
        }
    }

    @Test
    fun `词牌名长度分布符合模糊匹配的安全前提`() {
        // Matcher 对气泡允许「同长度、错 1 字」的模糊匹配，其安全性依赖：
        // 词牌名只有 3/4 字，且不存在「长度相同且相似度 >= 0.6」的词牌对。
        // 一旦索引引入了新的近似词牌，模糊匹配就可能把 A 认成 B —— 这条测试把守这个前提。
        val list = index.paiList.toList()
        val badLen = list.filter { it.length !in 2..4 }
        assertTrue("出现长度异常的曲牌名: $badLen", badLen.isEmpty())

        val pairs = mutableListOf<String>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (a.length != b.length) continue
                val sim = Matcher.similarity(a, b)
                if (sim >= 0.6) pairs += a + " vs " + b + " = %.3f".format(sim)
            }
        }
        assertTrue(
            "存在同长度且近似的词牌对，会让模糊匹配误判：$pairs",
            pairs.isEmpty(),
        )
    }
}
