package com.songci.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引与规范化的基础测试。
 *
 * `verses.json` 通过 `src/test/resources`（构建脚本里挂了 `src/main/assets`）读到 classpath，
 * 因此这里是**真的**在用随包发布的索引做断言，而不是另造一份假数据。
 */
class VerseIndexTest {

    private val json: String by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream(VerseIndex.ASSET_NAME)
            ?: error("classpath 里没有 ${VerseIndex.ASSET_NAME}（检查 test resources 配置）")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private val index: VerseIndex by lazy { VerseIndex.fromJson(json) }

    @Test
    fun `索引规模与设计文档一致`() {
        assertEquals(99, index.verses.size)
        assertEquals(40, index.paiList.size)
        assertTrue("首句长度不得少于 5", index.verses.all { it.head.length >= Config.MIN_HEAD_LEN })
        assertTrue("首句必须唯一", index.verses.map { it.head }.toSet().size == index.verses.size)
        assertTrue("id 必须唯一", index.verses.map { it.id }.toSet().size == index.verses.size)
    }

    @Test
    fun `剔除需特殊名臣解锁的两条`() {
        assertTrue("不应包含 id=9", index.verses.none { it.id == 9 })
        assertTrue("不应包含 id=87", index.verses.none { it.id == 87 })
    }

    @Test
    fun `99 条首句全部精确命中`() {
        for (verse in index.verses) {
            val hit = index.lookup(verse.head)
            assertNotNull("未命中: id=${verse.id} head=${verse.head}", hit)
            assertEquals(verse.id, hit!!.id)
        }
    }

    @Test
    fun `实机截图那条走精确查表`() {
        val hit = index.lookup("明月别枝惊鹊清风半夜鸣蝉")
        assertNotNull(hit)
        assertEquals("西江月", hit!!.pai)
        assertEquals(75, hit.id)
    }

    @Test
    fun `规范化_中文标点全角与空白`() {
        val expected = "明月别枝惊鹊清风半夜鸣蝉"
        assertEquals(expected, VerseIndex.normalize("明月别枝惊鹊，清风半夜鸣蝉。"))
        assertEquals(expected, VerseIndex.normalize("  明月别枝惊鹊 清风半夜鸣蝉 \n"))
        assertEquals(expected, VerseIndex.normalize("明月别枝惊鹊、清风半夜鸣蝉！"))
        // 全角空格与英文标点
        assertEquals(expected, VerseIndex.normalize("明月别枝惊鹊\u3000清风半夜鸣蝉"))
        assertEquals(expected, VerseIndex.normalize("明月别枝惊鹊,清风半夜鸣蝉!"))
        // 全角字母数字 → 半角
        assertEquals("ABC123", VerseIndex.normalize("ＡＢＣ１２３"))
        // 破折号 / 省略号
        assertEquals(expected, VerseIndex.normalize("明月别枝惊鹊——清风半夜鸣蝉……"))
    }

    @Test
    fun `规范化_空输入与纯标点`() {
        assertEquals("", VerseIndex.normalize(null))
        assertEquals("", VerseIndex.normalize(""))
        assertEquals("", VerseIndex.normalize("   "))
        assertEquals("", VerseIndex.normalize("，。、；：！？"))
        assertNull(index.lookup(""))
        assertNull(index.lookup("，。、"))
        assertNull(index.lookup("   "))
        // lookup 的入参是非空 String；null 必须先过 normalize
        assertNull(index.lookup(VerseIndex.normalize(null)))
    }

    @Test
    fun `未知文本不命中`() {
        assertNull(index.lookup("今天天气不错适合出门走走"))
        assertNull(index.lookup("西江月"))
        assertNull(index.nearest("短"))
    }

    @Test
    fun `词牌表可用于过滤`() {
        assertTrue(index.isKnownPai("西江月"))
        assertTrue(index.isKnownPai(" 念奴娇。"))
        assertTrue(!index.isKnownPai("这不是词牌"))
        assertTrue(!index.isKnownPai(""))
    }

    @Test
    fun `最近邻在索引内部自洽`() {
        // 每条首句对自己做最近邻，必须 100% 命中自己
        for (verse in index.verses) {
            val near = index.nearest(verse.head)
            assertNotNull("未命中: ${verse.head}", near)
            assertEquals(verse.head, near!!.first.head)
            assertEquals(1.0, near.second, 1e-9)
        }
    }

    @Test
    fun `相似度公式与设计文档一致`() {
        // sim = 1 - Levenshtein/max(len)
        assertEquals(1.0, Matcher.similarity("abc", "abc"), 1e-9)
        // 替换 1 个字：距离 1 / max(4,4) = 0.25 → 0.75
        assertEquals(0.75, Matcher.similarity("abcd", "abce"), 1e-9)
        assertEquals(0.0, Matcher.similarity("abc", ""), 1e-9)
        assertEquals(1.0, Matcher.similarity("", ""), 1e-9)
        // 距离 1 / max(3,3) = 1/3 → 2/3
        assertEquals(2.0 / 3.0, Matcher.similarity("abc", "abd"), 1e-9)
        // 长度不同：max 决定分母
        assertEquals(0.5, Matcher.similarity("abcd", "ab"), 1e-9)
        assertEquals(0, Matcher.levenshtein("abc", "abc"))
        assertEquals(1, Matcher.levenshtein("abc", "abd"))
        assertEquals(3, Matcher.levenshtein("abc", ""))
        assertEquals(2, Matcher.levenshtein("abcd", "ab"))
    }
}
