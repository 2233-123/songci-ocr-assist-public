package com.songci.assist

import org.junit.Assume

/**
 * 单测共用的假数据。
 *
 * 坐标全部是**归一化比例**（相对当帧宽高），坐标来源是按实机截图场景手写的：
 * 顶部一行首句 + 中部三个词牌气泡。改这里就等于改「屏幕布局」。
 */
object Fixtures {

    /** 实机截图里那条首句（id 75 → 西江月） */
    const val HEAD_TEXT = "明月别枝惊鹊清风半夜鸣蝉"

    /** 三个气泡（归一化矩形） */
    val BUBBLE_1 = HighlightRect(0.24f, 0.50f, 0.76f, 0.545f)
    val BUBBLE_2 = HighlightRect(0.24f, 0.575f, 0.76f, 0.62f)
    val BUBBLE_3 = HighlightRect(0.24f, 0.65f, 0.76f, 0.695f)

    /** 首句所在行（y 0.11..0.16） */
    val HEAD_RECT = HighlightRect(0.08f, 0.11f, 0.92f, 0.16f)

    private val cachedIndex: VerseIndex by lazy { VerseIndex.fromJson(loadJson()) }

    fun index(): VerseIndex = cachedIndex

    /** 从 classpath 读随包发布的 verses.json（构建脚本把 src/main/assets 挂进了 test resources）。 */
    fun loadJson(): String {
        val stream = Fixtures::class.java.classLoader!!.getResourceAsStream(VerseIndex.ASSET_NAME)
            ?: error("classpath 里没有 ${VerseIndex.ASSET_NAME}（检查 test resources 配置）")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun block(text: String, rect: HighlightRect): TextBlock =
        TextBlock.normalized(text, rect.left, rect.top, rect.right, rect.bottom)

    /** 完整一屏：首句 + 三个气泡。 */
    fun screenshotBlocks(): List<TextBlock> = headOnly() + optionBlocks("念奴娇", "西江月", "水调歌头")

    /** 只要首句那一行。 */
    fun headOnly(): List<TextBlock> = listOf(
        block(HEAD_TEXT, HEAD_RECT),
        // 顶部状态条（时间/回合数），用于确认噪声不干扰
        TextBlock.normalized("12:34", 0.04f, 0.01f, 0.15f, 0.04f),
        TextBlock.normalized("第 3 回合", 0.80f, 0.01f, 0.96f, 0.04f),
    )

    fun optionBlocks(vararg pais: String): List<TextBlock> = pais.mapIndexed { i, pai ->
        block(pai, bubbleRect(i))
    }

    private fun bubbleRect(index: Int): HighlightRect = when (index) {
        0 -> BUBBLE_1
        1 -> BUBBLE_2
        else -> BUBBLE_3
    }

    /** 同一句话被 OCR 拆成上下两行（两个字距很近，Y 轴相邻）。 */
    fun headSplitIntoTwoLines(): List<TextBlock> = listOf(
        TextBlock.normalized("明月别枝惊鹊", 0.08f, 0.11f, 0.92f, 0.16f),
        TextBlock.normalized("清风半夜鸣蝉", 0.08f, 0.17f, 0.92f, 0.22f),
    )

    /** 被拆成三行（超过 MAX_MERGED_LINES，只允许拼相邻两行的兜底行为）。 */
    fun headSplitIntoThreeLines(): List<TextBlock> = listOf(
        TextBlock.normalized("明月别枝惊鹊", 0.08f, 0.11f, 0.92f, 0.16f),
        TextBlock.normalized("清风半夜鸣蝉", 0.08f, 0.17f, 0.92f, 0.22f),
        TextBlock.normalized("七八个星天外", 0.08f, 0.23f, 0.92f, 0.28f),
    )

    // ------------------------------------------------------------------ 效果表

    /**
     * 词句效果表（`effects.json`，随包发布）。
     *
     * **可能为 null** —— 它是**生成物**，公开仓库**不分发**（避免把游戏数据带出去）。
     * 依赖它的测试用 [effectsOrSkip] 在缺失时自动跳过，不会让 CI 变红。
     */
    fun effectsOrNull(): EffectIndex? = runCatching {
        EffectIndex.fromJson(loadEffectsJson())
    }.getOrNull()

    /**
     * 取效果表；**缺失时让当前测试跳过**（JUnit `Assume`）。
     *
     * 与 [MatchBenchmark] 同一套做法：需要额外素材的诊断/契约测试，
     * 没有素材就跳过而不是失败 —— 这样公开仓库（纯代码副本）能正常跑 CI。
     */
    fun effectsOrSkip(): EffectIndex {
        val e = effectsOrNull()
        Assume.assumeTrue(
            "本仓库不含 effects.json（生成物/游戏数据），跳过依赖它的测试",
            e != null,
        )
        return e!!
    }

    /** 从 classpath 读 effects.json（构建脚本把 src/main/assets 挂进了 test resources）。 */
    fun loadEffectsJson(): String {
        val stream = Fixtures::class.java.classLoader!!.getResourceAsStream(EffectIndex.ASSET_NAME)
            ?: error("classpath 里没有 ${EffectIndex.ASSET_NAME}（检查 test resources 配置）")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
