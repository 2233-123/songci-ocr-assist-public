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

    /**
     * 子串候选走「前缀对齐」的最大长度。
     *
     * 游戏首句分批渐显，每段几字到十几字；前缀法只对这类**短截断**有意义。
     * 实测（`MatchBenchmark`）放开到 24 字会让匹配整体慢 3 倍，收到 12 字后降到 ~1.3 倍，
     * 而且「前 5~12 字认回自己」的覆盖没有损失。
     */
    private const val MAX_PREFIX_CANDIDATE_LEN = 12

    /**
     * 判定「位置相同」的容差（归一化 y）。
     *
     * OCR 给出的同一行 y 每次会有 1~2px 抖动（归一化后约 0.001），
     * 取 0.004（约 5px）既能把同一行视为相同位置，又不会把相邻两行混为一谈。
     */
    private const val Y_TIE_EPSILON = 0.004f

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

        // 单块内部还能拆行（ML Kit 有时把一个区域合成一个块）。
        //
        // 注意：**排序必须是升序**，因为 mergeAdjacent 只把「相邻」的簇合并成一组，
        // 顺序一变分组结果就变了。要「从下往上判断」的意图放在下面迭代时反转。
        val clusters = clustersOf(lineItems(top)).sortedBy { it.minY }

        var best: HeadMatch.Hit? = null
        var bestLines = Int.MAX_VALUE
        var bestMinY = Float.NEGATIVE_INFINITY
        // 取舍规则：**位置优先**（更靠下 = 当前题），相似度与行数次之。
        //
        // 为什么位置放到第一位 —— 真机日志给了两个必须用位置才能区分的场景：
        //
        // 1. 换题瞬间**上一题的首句残留在上方**，当前题的首句在更下面
        //    （帧155：残留「庭院深深深几许…」在 y=0.06，当前「一曲新词酒一杯」在 y=0.19）。
        // 2. 两个首句同时可读时，**两者的相似度都是满分 1.0**，分数无法区分，
        //    于是会在两者之间来回抖（日志里实测：如梦令↔诉衷情、少年游↔丑奴儿 各抖 3 次）。
        //
        // 用户反馈的后果是「题都换了，框还停在旧答案上」→ 容易误触。
        // 改成位置优先后，当前题（更靠下）稳定胜出。
        //
        // 注意仍在 TOP_CROP 区间内（0.0~0.45）：更下面的提示气泡（y≈0.7）不会进来。
        for (group in mergeAdjacent(clusters, Config.MAX_MERGED_LINES)) {
            val text = group.joinToString("") { it.text }
            val normalized = VerseIndex.normalize(text)
            if (normalized.length < Config.MIN_HEAD_LEN) continue

            val hit = bestVerseIn(normalized, index) ?: continue
            val minY = group.minOf { it.minY }
            val better = best == null ||
                // 1) 更靠下（当前题）
                minY > bestMinY + Y_TIE_EPSILON ||
                // 2) 位置相同：相似度更高
                (kotlin.math.abs(minY - bestMinY) <= Y_TIE_EPSILON &&
                    (hit.similarity > best!!.similarity ||
                        // 3) 位置与分数都相同：行数更少（更可能是"真的那句话"）
                        (hit.similarity == best!!.similarity && group.size < bestLines)))
            if (better) {
                best = hit
                bestLines = group.size
                bestMinY = minY
            }
        }
        // 诊断：**只在调试模式打印**。
        //
        // 这段早先是无条件执行的，代价被严重低估：
        // - 每帧往 EventLog 写两行长文本，把环形缓冲冲爆（真机日志证实：400 条混杂
        //   事件里绝大多数是 scanHead 这两行）；
        // - 更贵的是它**又跑了一遍完整匹配** —— `mergeAdjacent` 后对每个分组调
        //   `bestVerseIn`，而后者内部是子串全搜索。真机实测匹配耗时中位 51ms、
        //   最大 865ms，这一段占了相当比例。
        if (MatcherDebug.enabled) {
            val blockDump = top.joinToString(" ｜ ") {
                "y=%.2f:%s".format(it.centerY, VerseIndex.normalize(it.text).take(18))
            }
            MatcherDebug.log("scanHead块", blockDump)
            val top3 = mergeAdjacent(clusters, Config.MAX_MERGED_LINES)
                .map { g ->
                    val t = VerseIndex.normalize(g.joinToString("") { it.text })
                    val h = bestVerseIn(t, index)
                    "「${t.take(22)}」=${h?.let { "%.3f".format(it.similarity) } ?: "无"}"
                }
                .sortedByDescending { it.substringAfterLast('=') }
                .take(3)
                .joinToString(" ｜ ")
            MatcherDebug.log(
                "scanHead果",
                "${if (best != null) "命中 ${best.verse.pai} %.3f".format(best.similarity) else "未匹配"} ｜ $top3",
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

        /**
         * 前缀对齐候选。
         *
         * 与 [consider] 并列（不是替代）：游戏首句是**分批渐显**的，OCR 常常只读到前几段，
         * 而 [consider] 的相似度分母是较长者，短候选会被严重低估 —— 实测把 99 条的
         * 「前 7 字」喂进旧逻辑只有 8/99 命中。这里改用「候选 vs 首句等长前缀」比较，
         * 前缀一字不差即为 1.0。
         */
        fun considerPrefix(text: String, alignAtZeroOnly: Boolean = false) {
            if (text.length < Config.MIN_HEAD_LEN) return
            val current = best
            val near = index.nearestByPrefix(text, alignAtZeroOnly) ?: return
            if (near.second < Config.SIMILARITY_THRESHOLD) return
            val candidate = HeadMatch.Hit(near.first, near.second, text)
            if (current == null || candidate.similarity > current.similarity) {
                best = candidate
            }
        }

        // 先试整体（精确命中就是 1.0，直接结束）
        consider(normalized)
        if (best?.similarity == 1.0) return best
        // 整体不是完整首句时，试「整体是否等于某条首句的截断」—— 一字不差即 1.0。
        // 这是救「首句分批渐显」的关键一步（真机 bug：一曲新词酒一杯 认不出浣溪沙）。
        considerPrefix(normalized)
        if (best?.similarity == 1.0) return best

        // 再试所有长度 ≥ MIN_HEAD_LEN 的子串：覆盖「OCR 多读了前缀/后缀」的情况。
        for (start in 0..normalized.length - Config.MIN_HEAD_LEN) {
            for (end in normalized.length downTo start + Config.MIN_HEAD_LEN) {
                val sub = normalized.substring(start, end)
                // **先用精确表筛一遍**：完整首句走精确命中即可，不必进前缀法。
                // 这一步是性能关键 —— 不加它，每个子串都要跑「5 窗口 × 99 条」比较，
                // JVM 实测单次匹配从 ~10ms 涨到 27ms（真机更差）。
                val exact = index.lookup(sub)
                if (exact != null) {
                    val hit = HeadMatch.Hit(exact, 1.0, sub)
                    if (best == null || hit.similarity > best!!.similarity) best = hit
                    return best
                }
                // 只有「短截断」的候选才走前缀法，且**先只对齐开头**（便宜）。
                //
                // 为什么限制长度：前缀法是为了救「首句分批渐显、OCR 只读到前几段」，
                // 而游戏每段就是几字到十几字。放开到最长首句会让一个 21 字文本枚举
                // 出上百个候选 × 99 条，A/B 实测整体慢 3 倍。
                // 长文本本来就有 `consider(normalized)` 的整体比较兜着。
                if (sub.length <= MAX_PREFIX_CANDIDATE_LEN) {
                    considerPrefix(sub, alignAtZeroOnly = true)
                    if (best?.similarity == 1.0) return best
                }
                consider(sub)
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

        // 第二优先：同长度、相似度 ≥ 阈值的块，且必须是唯一最优解。
        //
        // 打分前把**形近字**折回代表字（见 [VerseIndex.canonicalizeConfusable]）。
        //
        // 为什么必须做：3 字词牌错 1 字是 0.667，而阈值 0.66 —— **只高 0.007**，
        // 所以只要错 2 个字就跌破阈值（0.333）。而 OCR 对某些字会反复读错：
        //
        //   鹧鸪天 -> 鹤鸽天   0.333 ❌
        //   丑奴儿 -> 卫奴儿   0.667（卡边缘，时好时坏）
        //   丑奴儿 -> 卫奴几   0.333 ❌
        //
        // 折叠后：`鹧鸪天/鹤鸽天` 都变 `鹧鸪天`，`丑奴儿/卫奴儿/卫奴几` 都变 `丑奴儿`
        // —— 直接满分命中。安全性由 [ConfusableClassTest] 断言守护（每个代表字
        // 在 40 个词牌里只出现一次，折回去不可能把 A 词牌变成 B 词牌）。
        val scored = inRegion
            .mapNotNull { b ->
                val t = VerseIndex.normalize(b.text)
                if (t.length != want.length || t.isEmpty()) return@mapNotNull null
                val s = similarity(t, want)
                // 形近字等价打分：把"读成形近字"也算作相同，
                // 覆盖"两个字都读错"的情况（见 VerseIndex.CONFUSABLE_CLASSES）
                val sCanon = VerseIndex.confusableSimilarity(t, want)
                val score = maxOf(s, sCanon)
                if (score < Config.OPTION_SIMILARITY_THRESHOLD) null else score to b
            }
            .sortedByDescending { it.first }
        if (scored.isEmpty()) return null

        val (bestScore, bestBlock) = scored.first()
        val runnerUp = scored.getOrNull(1)?.first ?: 0.0
        // 唯一最优：第二名必须明显更低，否则宁可不出框也不画错地方
        if (bestScore - runnerUp < Config.OPTION_SIMILARITY_MARGIN) return null
        return HighlightRect.of(bestBlock)
    }

    /**
     * 与 [match] 相同，但**排除屏幕顶部一片区域**（App 自己画的状态条所在处）。
     *
     * ### 为什么需要（用户反馈 + 代码确认）
     *
     * 状态条画的是 `应选：蝶恋花（屏上没找到选项气泡）`。OCR 把它读回来后，
     * 如果**只切出词牌名那三个字**（OCR 常在标点/括号处断块），那就是一个
     * **恰好等于词牌名的块** —— 而 [scanOptions] 的第一优先判据正是"整块恰好等于词牌名"，
     * 于是**框被画到 App 自己的状态文字上**，而不是游戏气泡上。
     *
     * 用户的原话：「我认为就是因为你 OCR 识别到了自己的提示框」—— 判断是对的。
     *
     * App 自己画了什么、画在哪是**已知信息**，用它排除比靠坐标阈值猜可靠得多。
     */
    fun matchExcluding(
        blocks: List<TextBlock>,
        index: VerseIndex,
        excludeTopRatio: Float,
    ): MatchResult {
        val kept = blocks.filter { it.normalized().centerY > excludeTopRatio }
        return match(kept, index)
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
