package com.songci.assist

/**
 * 纯函数匹配器：`List<TextBlock> → MatchResult`。
 *
 * 不取帧、不绘制、不依赖 Android 框架类，因此可以在 JVM 单测里用假数据（含实机截图
 * 导出的 OCR 文本 + 坐标）完整覆盖，见 `MatcherTest` 等。
 *
 * 两段式流程：
 *  1. [scanHead]：只看顶部区域 [Config.TOP_CROP_TOP]..[Config.TOP_CROP_BOTTOM]，
 *     把 Y 轴相邻的行拼接后与 99 条首句比对（精确查表 + 最近邻兜底）；
 *  2. [scanOptions]：只看中部区域，找文本 == 词牌名的块作为要画框的选项气泡。
 */
object Matcher {

    // ======================================================================
    // 公共 API
    // ======================================================================

    /**
     * 第一段：在文本块集合里找词句首句。
     *
     * @param blocks OCR 出来的全部文本块（内部会自己按裁剪区过滤）
     */
    fun scanHead(blocks: List<TextBlock>, index: VerseIndex): HeadMatch {
        if (blocks.isEmpty()) return HeadMatch.Miss
        val top = mutableListOf<TextBlock>()
        for (raw in blocks) {
            val b = raw.normalized()
            if (b.centerY < Config.TOP_CROP_TOP || b.centerY > Config.TOP_CROP_BOTTOM) continue
            if (VerseIndex.normalize(b.text).isEmpty()) continue
            top += b
        }
        if (top.isEmpty()) return HeadMatch.Miss

        // 单块内部还能拆行（ML Kit 有时把一个区域合成一个块）
        val clusters = clustersOf(lineItems(top)).sortedBy { it.minY }

        var best: HeadMatch.Hit? = null
        var bestLines = Int.MAX_VALUE
        for (group in mergeAdjacent(clusters, Config.MAX_MERGED_LINES)) {
            val text = group.joinToString("") { it.text }
            val normalized = VerseIndex.normalize(text)
            if (normalized.length < Config.MIN_HEAD_LEN) continue

            val hit = bestVerseIn(normalized, index) ?: continue
            // 分数相同时取行数更少的组合：更可能是"真的那句话"，而不是粘上了旁边的东西
            if (best == null || hit.similarity > best.similarity ||
                (hit.similarity == best.similarity && group.size < bestLines)
            ) {
                best = hit
                bestLines = group.size
            }
        }
        // 诊断：输出顶部候选与得分。
        // 只打"顶部块 + 结果"两行，避免把 EventLog 的环形缓冲冲爆。
        run {
            val blockDump = top.joinToString(" ｜ ") {
                "y=%.2f:%s".format(it.centerY, VerseIndex.normalize(it.text).take(18))
            }
            EventLog.log("scanHead块", "$blockDump")
            val top3 = mergeAdjacent(clusters, Config.MAX_MERGED_LINES)
                .map { g ->
                    val t = VerseIndex.normalize(g.joinToString("") { it.text })
                    val h = bestVerseIn(t, index)
                    "「${t.take(22)}」=${h?.let { "%.3f".format(it.similarity) } ?: "无"}"
                }
                .sortedByDescending { it.substringAfterLast('=') }
                .take(3)
                .joinToString(" ｜ ")
            EventLog.log(
                "scanHead果",
                "${if (best != null) "命中 ${best.verse.pai} %.3f".format(best.similarity) else "未匹配"} ｜ ${top3}",
            )
        }
        return best ?: HeadMatch.Miss
    }

    /**
     * 在一段 OCR 文本里找出最像哪条首句。
     *
     * 游戏里首句那行通常是 `（首句）候馆梅残，溪桥柳细，草薰风暖摇征辔`：
     * 去掉标点后变成 `首句候馆梅残溪桥柳细草薰风暖摇征辔`，**前面多了"首句"两个字**。
     * 纯整体相似度只有 0.88，再错一个字就跌破 0.72 —— 而答案其实明明白白在里面。
     *
     * 所以这里先试整体，再试**所有子串**（OCR 多读、少读都覆盖）。
     * 子串数量很小（一句 20 字 → 约 200 个子串），代价可以接受；
     * 精确命中优先，其次取相似度最高的那个子串。
     */
    private fun bestVerseIn(normalized: String, index: VerseIndex): HeadMatch.Hit? {
        var best: HeadMatch.Hit? = null

        fun consider(text: String) {
            if (text.length < Config.MIN_HEAD_LEN) return
            val current = best
            val candidate: HeadMatch.Hit = index.lookup(text)?.let {
                HeadMatch.Hit(it, 1.0, text)
            } ?: run {
                val near = index.nearest(text) ?: return
                HeadMatch.Hit(near.first, near.second, text)
            }
            if (current == null || candidate.similarity > current.similarity) {
                best = candidate
            }
        }

        // 先试整体（精确命中就是 1.0，直接结束）
        consider(normalized)
        if (best?.similarity == 1.0) return best

        // 再试所有长度 ≥ MIN_HEAD_LEN 的子串：覆盖「OCR 多读了前缀/后缀」的情况
        for (start in 0..normalized.length - Config.MIN_HEAD_LEN) {
            for (end in normalized.length downTo start + Config.MIN_HEAD_LEN) {
                consider(normalized.substring(start, end))
                if (best?.similarity == 1.0) return best
            }
        }
        return best
    }

    /**
     * 第二段：在中部区域找「词牌名」的块（即要画框的选项气泡）。
     *
     * ### 为什么允许有限的模糊匹配
     *
     * 气泡里只写词牌名，所以历史上只认**精确等值**（用 contains 会被含该词的正文行抢走）。
     * 但实机发现：游戏里这三个气泡是艺术字体，OCR 会把个别字读错 ——
     * 例如「钗头凤」被读成别的字，于是永远匹配不上，表现就是
     * 「能看到字但没有框」。
     *
     * 好在词牌名集合有一个**很有用的性质**（见 `tools/` 的同名校验）：
     * 全部是 3 字（38 个）或 4 字（2 个），而且**长度相同、相似度 ≥ 0.6 的词牌对一个都没有**。
     * 所以「同长度 + 最多错 1 个字」是安全的：
     * - 3 字错 1 字 → 相似度 0.667，能被区分（最近的同名长词牌也只有 0.333）
     * - 不会把 A 词牌认成 B 词牌
     *
     * 匹配顺序：**先精确、再模糊**，且模糊要求是唯一最优解（避免两个候选分数接近时瞎猜）。
     *
     * @return null 表示屏上没找到（OCR 漏识）→ 调用方显示「应选：X」，不画框。
     */
    fun scanOptions(blocks: List<TextBlock>, pai: String, index: VerseIndex): HighlightRect? {
        val want = VerseIndex.normalize(pai)
        if (want.isEmpty() || blocks.isEmpty()) return null
        if (!index.isKnownPai(want)) return null

        val inRegion = blocks
            .map { it.normalized() }
            .filter { it.centerY >= Config.OPTION_REGION_TOP && it.centerY <= Config.OPTION_REGION_BOTTOM }
        if (inRegion.isEmpty()) return null

        // 第一优先：精确等值（气泡里就只写词牌名）
        var best: TextBlock? = null
        for (b in inRegion) {
            if (VerseIndex.normalize(b.text) != want) continue
            if (best == null || b.area > best.area) best = b
        }
        if (best != null) return HighlightRect.of(best)

        // 第二优先：同长度、相似度 ≥ 阈值的块，且必须是唯一最优解
        val scored = inRegion
            .mapNotNull { b ->
                val t = VerseIndex.normalize(b.text)
                if (t.length != want.length || t.isEmpty()) return@mapNotNull null
                val s = similarity(t, want)
                if (s < Config.OPTION_SIMILARITY_THRESHOLD) null else s to b
            }
            .sortedByDescending { it.first }
        if (scored.isEmpty()) return null

        val (bestScore, bestBlock) = scored.first()
        val runnerUp = scored.getOrNull(1)?.first ?: 0.0
        // 唯一最优：第二名必须明显更低，否则宁可不出框也不画错地方
        if (bestScore - runnerUp < Config.OPTION_SIMILARITY_MARGIN) return null
        return HighlightRect.of(bestBlock)
    }

    /** 两段合一：供真机流水线与单测直接调用。 */
    fun match(blocks: List<TextBlock>, index: VerseIndex): MatchResult =
        when (val head = scanHead(blocks, index)) {
            is HeadMatch.Miss -> MatchResult.None
            is HeadMatch.Hit -> {
                val target = scanOptions(blocks, head.verse.pai, index)
                if (target == null) {
                    MatchResult.PaiOnly(head.verse, head.similarity, head.matchedText)
                } else {
                    MatchResult.Hit(head.verse, head.similarity, head.matchedText, target)
                }
            }
        }

    /**
     * 诊断用：顶部区域实际读到的文本（按 y 排序，最多 [maxLines] 段，用 `|` 分隔）。
     *
     * 未命中时状态条默认什么都不显示，真机上就完全不知道 OCR 读到了什么；
     * 调试面板靠它显示「屏幕顶部到底有什么字」。
     */
    fun topTextSample(blocks: List<TextBlock>, maxLines: Int = 3): String {
        val top = blocks
            .map { it.normalized() }
            .filter { it.centerY in Config.TOP_CROP_TOP..Config.TOP_CROP_BOTTOM }
            .filter { VerseIndex.normalize(it.text).isNotEmpty() }
            .sortedBy { it.top }
            .take(maxLines)
            .map { VerseIndex.normalize(it.text) }
        val text = top.joinToString("|")
        return if (text.length > 60) text.take(60) + "…" else text
    }

    // ======================================================================
    // 相似度：归一化编辑距离（与预研脚本同一公式）
    //   sim(a,b) = 1 − Levenshtein(a,b) / max(len(a), len(b))
    // ======================================================================

    /** 归一化编辑距离相似度，输入应已 [VerseIndex.normalize]。 */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    /** 滚动数组版 Levenshtein 距离，空间 O(min(n,m))。 */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // 保证 s 是较短的串，省内存
        val s = if (a.length <= b.length) a else b
        val t = if (a.length <= b.length) b else a

        var prev = IntArray(s.length + 1) { it }
        var cur = IntArray(s.length + 1)
        for (i in 1..t.length) {
            cur[0] = i
            val tc = t[i - 1]
            for (j in 1..s.length) {
                val cost = if (s[j - 1] == tc) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[s.length]
    }

    // ======================================================================
    // 行拼接
    // ======================================================================

    /** 参与拼接的一行（含近似坐标，用于后续画框）。 */
    private data class LineItem(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerY: Float get() = (top + bottom) / 2f
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
    }

    private data class LineCluster(
        val text: String,
        val minY: Float,
        val maxY: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** 合并进来的行数，用于恢复「单行高度」 */
        val lineCount: Int = 1,
    ) {
        val centerY: Float get() = (minY + maxY) / 2f
        val lineHeight: Float get() = ((maxY - minY) / lineCount).coerceAtLeast(1e-6f)
        val width: Float get() = (right - left).coerceAtLeast(0f)
    }

    /**
     * 把文本块展开成「行」：
     *  - ML Kit 把一段拆成多块 → 每块就是一行；
     *  - ML Kit 把多行合成一块（`text` 里有换行）→ 按换行拆开，Y 轴均分估算。
     */
    private fun lineItems(blocks: List<TextBlock>): List<LineItem> {
        val out = ArrayList<LineItem>(blocks.size)
        for (b in blocks) {
            val lines = if (b.lines.isNotEmpty()) b.lines else b.text.split('\n')
            val cleaned = lines.filter { VerseIndex.normalize(it).isNotEmpty() }
            if (cleaned.isEmpty()) continue
            if (cleaned.size == 1) {
                out += LineItem(cleaned[0], b.left, b.top, b.right, b.bottom)
                continue
            }
            val step = b.height / cleaned.size
            cleaned.forEachIndexed { i, line ->
                val top = b.top + step * i
                out += LineItem(line, b.left, top, b.right, top + step)
            }
        }
        return out
    }

    private fun clustersOf(items: List<LineItem>): List<LineCluster> {
        if (items.isEmpty()) return emptyList()
        val clusters = mutableListOf<LineCluster>()
        for (it in items.sortedBy { it.centerY }) {
            val last = clusters.lastOrNull()
            if (last != null && sameLine(last, it)) {
                clusters[clusters.lastIndex] = LineCluster(
                    text = last.text + it.text,
                    minY = minOf(last.minY, it.top),
                    maxY = maxOf(last.maxY, it.bottom),
                    left = minOf(last.left, it.left),
                    top = minOf(last.top, it.top),
                    right = maxOf(last.right, it.right),
                    bottom = maxOf(last.bottom, it.bottom),
                    lineCount = last.lineCount + 1,
                )
            } else {
                clusters += LineCluster(
                    text = it.text,
                    minY = it.top,
                    maxY = it.bottom,
                    left = it.left,
                    top = it.top,
                    right = it.right,
                    bottom = it.bottom,
                )
            }
        }
        return clusters
    }

    /**
     * 是否同一行：Y 轴同排 **且** X 轴基本不相交（交集 ≤ [Config.LINE_X_OVERLAP_MAX]）。
     *
     * 为什么要 X 不相交：OCR 会把一句话拆成左右两段（两个块），这两段应该合成一行；
     * 但两个**各自独立**的块恰好排在同一行（左半句词 + 右边一个 UI 标签）时，
     * 合起来会把标签粘进句子 —— 详见 [mergeAdjacent] 的注释。
     */
    private fun sameLine(cluster: LineCluster, item: LineItem): Boolean {
        val tol = maxOf(
            (cluster.maxY - cluster.minY) / cluster.lineCount,
            item.height,
        ) * Config.LINE_MERGE_TOLERANCE_RATIO
        if (kotlin.math.abs(cluster.centerY - item.centerY) > tol) return false
        return xDisjoint(cluster.left, cluster.right, item.left, item.right)
    }

    /** 两段 X 轴是否「基本不相交」：交集不超过较窄一段的 [Config.LINE_X_OVERLAP_MAX]。 */
    private fun xDisjoint(l1: Float, r1: Float, l2: Float, r2: Float): Boolean {
        val overlap = minOf(r1, r2) - maxOf(l1, l2)
        val narrow = minOf(r1 - l1, r2 - l2)
        if (narrow <= 0f) return false
        return overlap <= Config.LINE_X_OVERLAP_MAX * narrow
    }

    /**
     * 顺序合并 Y 轴相邻的行：首句折成两行时拼起来再匹配（最多 [maxLines] 行）。
     *
     * 相邻两行要求 X 轴有交集，且**不是一方被另一方明显包住**：整行对整行（宽度相同）
     * 是正常折行，可以拼；而一个小标签整块落在首句那一行里时不能拼进去，否则
     * 「明月别枝惊鹊清风半夜鸣蝉」+「请选择下列」= 17 字，相似度掉到 0.71 < 0.72，
     * **整句直接认不出**（实测过，属于最坏的一类回归）。
     */
    private fun mergeAdjacent(clusters: List<LineCluster>, maxLines: Int): List<List<LineCluster>> {
        if (clusters.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<LineCluster>>()
        var current = mutableListOf(clusters[0])
        for (i in 1 until clusters.size) {
            val c = clusters[i]
            val last = current.last()
            // 与上一行自己的行高比，而不是与合并后的整体高度比，否则连续多行会被一路吞掉
            val gapTol = maxOf(last.lineHeight, c.lineHeight) * Config.LINE_MERGE_TOLERANCE_RATIO
            val adjacent = c.top - last.bottom <= gapTol &&
                horizontalOverlap(last, c) > 0f &&
                !containsHorizontally(last, c)
            if (current.size < maxLines && adjacent) {
                current += c
            } else {
                groups += current
                current = mutableListOf(c)
            }
        }
        groups += current
        return groups
    }

    /** [outer] 是否在水平方向上**明显**包住 [inner]（宽度差要超过一定比例）。 */
    private fun containsHorizontally(outer: LineCluster, inner: LineCluster): Boolean {
        val outerW = outer.width
        val innerW = inner.width
        val margin = Config.LINE_CONTAIN_WIDTH_MARGIN * minOf(outerW, innerW)
        if (outer.left > inner.left || outer.right < inner.right) return false
        return outerW - innerW > margin
    }

    private fun horizontalOverlap(a: LineCluster, b: LineCluster): Float =
        minOf(a.right, b.right) - maxOf(a.left, b.left)
}
