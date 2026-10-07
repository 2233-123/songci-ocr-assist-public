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

    /** 最长首句的字数。用于判断「候选是否可能是某条首句的截断」。 */
    val maxHeadLen: Int = verses.maxOfOrNull { it.head.length } ?: 0

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
            // 用**形近等价**相似度：把「读成形近字」也算作相同（见 [CONFUSABLE_CLASSES]）。
            //
            // 短首句对此尤其敏感 —— 实测「甚矣吾衰矣」（5 字）被读成「甚美吾衰吴」，
            // 两个字错 → 编辑距离相似度只有 `1 - 2/5 = 0.600`，远低于阈值 0.72，
            // **整句废掉**（用户反馈"识别结果不太好"）。形近等价后直接满分。
            //
            // 首句越短越怕错字，这是本项目的一个结构性弱点：
            //   5 字首句最多容忍 1 个错字（错 2 个 = 0.600 ✗）
            //   6 字首句最多容忍 1 个错字（错 2 个 = 0.667 ✗）
            //   8 字首句最多容忍 2 个错字（错 2 个 = 0.750 ✓）
            // 索引里 5~6 字的短首句只有 3 条，都是这类高风险条目。
            val s = confusableSimilarity(t, v.head)
            if (s > bestScore) {
                bestScore = s
                best = v
            }
        }
        val hit = best ?: return null
        return hit to bestScore
    }

    /**
     * **等长窗口**的最近邻：把候选与每条首句的**同长度子串**比较，取最高分。
     *
     * ### 为什么需要它（真机 bug 的根因）
     *
     * 游戏的首句是**分批渐显**的（截图证实：「一曲新词酒一杯，去年天气旧亭台，夕阳西下几时回」
     * 一次只显示一段），OCR 读到的是当时那一段。但 [Matcher.similarity] 的分母是**较长者**：
     *
     * ```
     * 候选「一曲新词酒一杯」(7 字) vs 首句「一曲新词酒一杯…小园香径独徘徊」(42 字)
     * 即使这 7 字一字不差是前缀，相似度也只有 1 - 35/42 = 0.167
     * ```
     *
     * 远低于 0.72 阈值 → **整条词永远认不出来**。实测把 99 条的「前 7 字」喂进旧逻辑，
     * 只有 **8/99** 命中；「前 10 字」64/99。修正后两者都是 99/99。
     *
     * ### 为什么对齐范围分成两档（性能关键）
     *
     * A/B 基准实测：把 [WINDOW_SCAN_HEAD] 个窗口用在**每个子串**上，会让匹配整体慢 **3 倍**
     * （9.0ms → 27.0ms，真机上就是 51ms → 150ms）。因为 `scanHead` 会对一个 21 字的
     * 文本枚举 ~230 个候选，每个候选再乘 5 个窗口 × 99 条。
     *
     * 所以拆开：
     * - [alignAtZeroOnly] = true（子串候选用）：只对齐首句开头。子串本来就取自文本片段，
     *   对齐点几乎总在开头，够用且便宜。
     * - [alignAtZeroOnly] = false（整段文本用）：额外扫开头几个字，救 OCR **漏读首字**
     *   的情况（真机见过「一曲新词酒一杯」被读成「曲新词酒一杯」）。
     *
     * ### 安全性
     *
     * 99 条首句的 **4~12 字前缀全部互不相同**（零重复组），见 [HeadPrefixMatchTest] 的断言。
     */
    fun nearestByPrefix(text: String, alignAtZeroOnly: Boolean = false): Pair<Verse, Double>? {
        val t = normalize(text)
        if (t.length < Config.MIN_HEAD_LEN) return null
        val scan = if (alignAtZeroOnly) 0 else WINDOW_SCAN_HEAD
        var best: Verse? = null
        var bestScore = 0.0
        for (v in verses) {
            val h = v.head
            val s = if (h.length < t.length) {
                // 首句比候选短时只能整体比较
                confusableSimilarity(t, h)
            } else {
                var local = 0.0
                val last = minOf(scan, h.length - t.length)
                for (k in 0..last) {
                    // 同样用形近等价（见 nearestAny 的说明）—— 短首句的错字必须靠它兜住
                    val x = confusableSimilarity(t, h.substring(k, k + t.length))
                    if (x > local) local = x
                    if (local == 1.0) break
                }
                local
            }
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

        /**
         * **形近字混淆类**：同一组里的字在打分时被视作等价。
         *
         * ### 为什么需要（真机实测的两类翻车）
         *
         * 3 字词牌错 1 字时相似度是 `0.667`，而阈值是 `0.66` —— **只高 0.007**，
         * 所以**只要错 2 个字就跌破阈值**（0.333）。而 OCR 对某些字就是会反复读错：
         *
         * | 词牌 | 实测误读 | 相似度 |
         * |---|---|---|
         * | 鹧鸪天 | 鹤鸽天 | 0.333 ❌ |
         * | 丑奴儿 | 卫奴儿 / 亚奴儿 | 0.667（卡边缘） |
         * | 丑奴儿 | 卫奴几 | 0.333 ❌ |
         *
         * ### 为什么必须是"组 → 占位符"而不是"字 → 代表字"
         *
         * 曾经想用「把组内每个字映射到代表字」的写法，结果 `鹧鸪天` 变成 `鹧鹧天`
         * —— 因为 `鹧` 与 `鸪` **是两个不同的字**，都折到同一个代表字就互相吞掉了。
         *
         * 所以改成**每组一个独立占位符**：
         *
         * ```
         * 鹧鸪天 -> ① ① 天      鹤鸽天 -> ① ① 天      -> 等价 ✅
         * 丑奴儿 -> ① 奴 儿      卫奴儿 -> ① 奴 儿      -> 等价 ✅
         * ```
         *
         * 注意每个占位符只被**一组**使用，且每个字只属于一组，所以不会串组。
         *
         * ### 为什么安全
         *
         * 由 [ConfusableClassTest] 断言守护：折叠后 40 个词牌**零碰撞**。
         * 注意只用于**打分**，不改动展示给用户的词牌名。
         */
        private val CONFUSABLE_CLASSES: List<String> = listOf(
            // 鸟部形近字。实测：鹧鸪天 -> 鹤鸽天（两个字都错，相似度 0.333）
            "鹧鸪鹤鸽鹊鸦鹃鸥鸯鸳鹂鹉鹏鸠鸫鸬鹕鹗鹘鹚鹛鹜鹞鹩鹪鹩鸾鹭",
            // 丑 / 卫 / 亚 形近（实测丑奴儿 -> 卫奴儿、亚奴儿）
            "丑卫亚",
            // 儿 / 几 形近（实测丑奴儿 -> 卫奴几，末字读错）
            "儿几",
            // 矣 / 美 / 吴 形近。实测**首句**「甚矣吾衰矣」被读成「甚美吾衰吴」
            // —— 5 字首句错 2 个字，相似度只有 0.600，远低于阈值 0.72，整句废掉。
            // 安全性：索引里「矣」只出现在 id=85（贺新郎）这一条首句里，
            //「美」「吴」不出现在任何首句里 → 折叠后零碰撞。
            "矣美吴",
            // 衰 / 哀 形近（手书字体下下半部极像）
            "衰哀",
        )

        /** 每个字 → 所属组的序号（-1 表示不属于任何组） */
        private val CLASS_OF: Map<Char, Int> = buildMap {
            CONFUSABLE_CLASSES.forEachIndexed { i, group ->
                for (c in group) put(c, i)
            }
        }

        /**
         * 两个字是否**形近等价**：相同，或属于同一个混淆组。
         *
         * 注意比较的是**位置上的字**，所以能正确表达"末字 儿/几 混"与"首字 丑/卫/亚 混"
         * 这类**不同位置各自混淆**的情况 —— 这是"全局字符折成占位符"做不到的
         * （那会把 卫奴几 折成 `②奴②`、丑奴儿 折成 `②奴儿`，反而认不出来）。
         */
        fun confusableEquals(a: Char, b: Char): Boolean {
            if (a == b) return true
            val ga = CLASS_OF[a] ?: return false
            return ga == CLASS_OF[b]
        }

        /**
         * 形近等价下的相似度：逐位比较，同组即算相同。
         *
         * 与 [Matcher.similarity] 的区别：那里用编辑距离，任何不同的字都算"错"；
         * 这里先把形近字视作相同再算，于是"两个字都只是读成形近字"也能满分。
         *
         * ```
         * 鹧鸪天 vs 鹤鸽天  -> 1.0   （组内等价）
         * 丑奴儿 vs 卫奴几  -> 1.0   （丑/卫 同组，儿/几 同组）
         * 丑奴儿 vs 玉楼春  -> 0.0
         * ```
         */
        fun confusableSimilarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val n = maxOf(a.length, b.length)
            var same = 0
            for (i in 0 until minOf(a.length, b.length)) {
                if (confusableEquals(a[i], b[i])) same++
            }
            return same.toDouble() / n
        }

        /**
         * 等长窗口的起始位置扫首句开头这些字。
         *
         * 首句是逐段显示的，对齐点必然靠前；限制它是为了让 [nearestByPrefix]
         * 的每帧开销可控（不限制的话候选 × 窗口 × 99 条会涨一个量级）。
         */
        const val WINDOW_SCAN_HEAD = 4

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
