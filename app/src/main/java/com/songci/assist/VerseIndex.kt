package com.songci.assist

import org.json.JSONObject

/**
 * `assets/verses.json` 的一条词句。
 *
 * @param head 首句（去标点空白后的匹配键）
 */
data class Verse(
    val id: Int,
    val name: String,
    val head: String,
    val pai: String,
    val style: Int,
    val poet: String,
    val poetIds: List<Int>,
)

/**
 * 词句索引：载入 + 规范化 + 精确查表 + 最近邻兜底。
 *
 * 纯 Kotlin/org.json，不碰 Android 框架类，因此可以在 JVM 单测里直接构造字符串使用
 * （见 `VerseIndexTest`）。
 */
class VerseIndex private constructor(
    /** generated_at（仅用于日志/调试） */
    val generatedAt: String,
    val verses: List<Verse>,
    /** 40 个词牌（去重、排序） */
    val paiList: Set<String>,
) {

    private val byHead: Map<String, Verse> = verses.associateBy { it.head }

    init {
        require(verses.isNotEmpty()) { "词句索引为空：$ASSET_NAME 没有 verses" }
    }

    /** 精确查表：规范化后的首句 → 词句。 */
    fun lookup(text: String): Verse? = byHead[normalize(text)]

    /**
     * 最近邻兜底：跟全部 99 条首句算相似度，返回最高分（低于 [Config.SIMILARITY_THRESHOLD]
     * 或长度不足时返回 null）。最多错 3 个字时预研里 100% 命中正确词牌。
     */
    fun nearest(text: String): Pair<Verse, Double>? {
        val (hit, score) = nearestAny(text) ?: return null
        return if (score >= Config.SIMILARITY_THRESHOLD) hit to score else null
    }

    /**
     * 不考虑阈值的最近邻：只要长度够就给出相似度最高的那一条（可能低于阈值）。
     *
     * 与 [nearest] 的区别很重要：`nearest` 返回 null 有两种完全不同的含义 ——
     * 「长度不够」和「最高相似度低于阈值（此时结果是**不提示**，不是判错）」。
     * 诊断/测试要区分这两者时用本方法。
     */
    fun nearestAny(text: String): Pair<Verse, Double>? {
        val t = normalize(text)
        if (t.length < Config.MIN_HEAD_LEN) return null
        var best: Verse? = null
        var bestScore = 0.0
        for (v in verses) {
            val s = Matcher.similarity(t, v.head)
            if (s > bestScore) {
                bestScore = s
                best = v
            }
        }
        val hit = best ?: return null
        return hit to bestScore
    }

    /** 精确查表优先，未中则最近邻。返回 (词句, 相似度, 是否精确命中)。 */
    fun resolve(text: String): Triple<Verse, Double, Boolean>? {
        lookup(text)?.let { return Triple(it, 1.0, true) }
        val near = nearest(text) ?: return null
        return Triple(near.first, near.second, false)
    }

    /** 词牌名是否在索引里（用于过滤噪声：屏幕上出现的"词牌"才可能是选项）。 */
    fun isKnownPai(text: String): Boolean = normalize(text).let { it.isNotEmpty() && it in paiList }

    companion object {
        const val ASSET_NAME = "verses.json"

        /** 从 assets 文本构造（Android 侧 `assets.open(ASSET_NAME)` / 单测读 classpath 资源）。 */
        fun fromJson(json: String): VerseIndex {
            val root = JSONObject(json)
            val arr = root.optJSONArray("verses")
                ?: throw IllegalArgumentException("verses.json 缺少 verses 数组")
            val list = ArrayList<Verse>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val poetIdsArr = o.optJSONArray("poet_ids")
                val poetIds = if (poetIdsArr == null) {
                    emptyList()
                } else {
                    (0 until poetIdsArr.length()).map { poetIdsArr.optInt(it) }
                }
                list += Verse(
                    id = o.optInt("id"),
                    name = o.optString("name"),
                    head = o.optString("head"),
                    pai = o.optString("pai"),
                    style = o.optInt("style"),
                    poet = o.optString("poet"),
                    poetIds = poetIds,
                )
            }
            val paiFromJson = root.optJSONArray("pai_list")
            val pai = if (paiFromJson == null) {
                list.map { it.pai }.filter { it.isNotEmpty() }.toSortedSet()
            } else {
                (0 until paiFromJson.length()).map { paiFromJson.optString(it) }.toSortedSet()
            }
            return VerseIndex(
                generatedAt = root.optString("generated_at"),
                verses = list.sortedBy { it.id },
                paiList = pai,
            )
        }

        /**
         * 匹配键规范化：去掉全部中文/英文标点与空白，全角转半角，去掉 BOM。
         *
         * 与 `tools/gen_verse_index.py` 的 `first_clause()` **同一口径**，两端必须同步修改。
         */
        fun normalize(text: String?): String {
            if (text.isNullOrEmpty()) return ""
            val sb = StringBuilder(text.length)
            for (ch in text) {
                val c = when {
                    // 全角 ASCII（！…～）→ 半角
                    ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    // 全角空格 / BOM
                    ch.code == 0x3000 || ch.code == 0xFEFF -> ' '
                    else -> ch
                }
                if (isPunctuationOrSpace(c)) continue
                sb.append(c)
            }
            return sb.toString()
        }

        private fun isPunctuationOrSpace(c: Char): Boolean {
            if (c.isWhitespace()) return true
            if (c.isDigit() || c.isLetter()) return false
            // 其余按 Unicode 类别判断：标点 / 符号（含数学符号）一律剔除
            return when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION,
                Character.DASH_PUNCTUATION,
                Character.START_PUNCTUATION,
                Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION,
                Character.MATH_SYMBOL,
                Character.CURRENCY_SYMBOL,
                Character.MODIFIER_SYMBOL,
                Character.OTHER_SYMBOL,
                -> true

                else -> false
            }
        }
    }
}
