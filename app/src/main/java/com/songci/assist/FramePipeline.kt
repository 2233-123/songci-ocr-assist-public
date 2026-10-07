package com.songci.assist

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一帧的诊断信息（调试面板 / 引导页诊断区显示）。
 *
 * 没有它就只能猜「为什么没出框」：看不到 OCR 到底读到了什么、首句候选是什么、
 * 匹配到了哪个词牌、第二段有没有找到气泡。
 */
data class FrameDiag(
    val frameCount: Int,
    /** 当帧尺寸（像素） */
    val frameWidth: Int,
    val frameHeight: Int,
    /** 屏幕真实尺寸（用于对比捕获尺寸是否一致） */
    val screenWidth: Int,
    val screenHeight: Int,
    /** 屏幕旋转（0/90/180/270） */
    val rotation: Int,
    /** 本帧全部文本块（归一化坐标），用于核对裁切区与坐标换算 */
    val blocks: List<TextBlock>,
    /** 顶部区域拼出来的首句候选 */
    val headText: String,
    /** 匹配到的词牌，空 = 没匹配上 */
    val pai: String,
    val similarity: Double,
    /** 命中的气泡矩形（归一化），null = 没找到气泡 */
    val target: HighlightRect?,
    val idle: Boolean,
) {
    /** 供界面显示：每个文本块的归一化坐标 + 文字 */
    fun blockLines(max: Int = 12): List<String> = blocks
        .map { it.normalized() }
        .sortedBy { it.centerY }
        .take(max)
        .map { b ->
            "y=%.2f x=%.2f-%.2f  %s".format(b.centerY, b.left, b.right, VerseIndex.normalize(b.text))
        }
}

/** 一帧流水线的结论（含"没跑第二段"这类中间态，便于状态条显示）。 */
sealed interface FrameOutcome {
    /** 本帧 OCR 全空 / 无有效文本。 */
    data object Empty : FrameOutcome

    /** 首句未命中，不绘制任何东西。 */
    data object NoMatch : FrameOutcome

    /** 认出了词牌，但屏上没找到气泡 → 状态条「应选：X」。 */
    data class PaiOnly(
        val pai: String,
        val headText: String,
        val similarity: Double,
        /** 该词句的效果（不含「词元」）；空 = 没有数据 */
        val effects: List<PaiEffect> = emptyList(),
    ) : FrameOutcome

    /** 命中并在屏上定位到气泡。 */
    data class Hit(
        val pai: String,
        val headText: String,
        val similarity: Double,
        val target: HighlightRect,   // HighlightRect 是 data class，因此 Hit 可比较
        /** 该词句的效果（不含「词元」）；空 = 没有数据 */
        val effects: List<PaiEffect> = emptyList(),
    ) : FrameOutcome
}

/**
 * 帧流水线：节流 + 调度「第一段（顶部）→ 第二段（中部）」两段式 OCR。
 *
 * 核心逻辑不使用 Android 框架类（只用 `android.os.SystemClock` 取单调时间），位图处理
 * 放在 [FramePreprocessorImages] 由 CaptureService 调用；因此可以在 JVM 单测里用假数据
 * 覆盖全部调度逻辑，见 `FramePipelineTest`。
 *
 * 节流三道（设计文档 §7）：
 *  1. 取帧 2 fps，长期未命中降到 0.5 fps（[Config.FRAMES_BEFORE_IDLE] 帧未命中即省电）；
 *  2. 同一时刻只处理一帧（[busy]）；
 *  3. 命中后 [Config.HIT_COOLDOWN_MS] 内不再跑第二段（复用上次结论）。
 */
class FramePipeline(
    private val ocr: OcrEngine,
    /** 词句索引；索引是异步加载的，所以用取值函数而不是构造期固定值 */
    private val index: () -> VerseIndex?,
    /**
     * 词句效果表；同样是异步加载、可选。
     *
     * 拿不到（未加载完 / 文件缺失）时效果列表为空 —— **只是不显示效果，不影响出框**。
     */
    private val effects: (() -> EffectIndex?)? = null,
    /** 允许注入的时钟（单测里手动推进）。默认单调时钟，与 Android 侧同一时间基。 */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "songci-pipeline").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private val noHitFrames = AtomicInteger(0)

    /** 累计处理过的帧数（调试面板显示，用来确认取帧到底有没有在跑） */
    private val frameCounter = AtomicInteger(0)

    @Volatile
    private var nextAllowedAt = 0L

    /** 最近一次命中的时刻；用极小值起手，避免首次判断发生减法下溢 */
    @Volatile
    private var lastHitAt = Long.MIN_VALUE / 4

    /** 是否处于省电模式（连续 [Config.FRAMES_BEFORE_IDLE] 帧未命中后为 true）。 */
    @Volatile
    var idleMode: Boolean = false
        private set

    /** 冷却期内复用的上一次结论（同一局结果不变，没必要反复跑第二段 OCR）。 */
    @Volatile
    private var cachedOutcome: FrameOutcome? = null

    /** 上面那份结论对应的词句 id；换局后必须失效（否则会把上一局的框画到新一局） */
    @Volatile
    private var cachedVerseId: Int = -1

    /** 上一帧是否命中过（用于命中结束后补发一次 NoMatch，让高亮及时清掉）。 */
    @Volatile
    private var lastFrameHit: Boolean = false

    /**
     * 最近一次 [matchNow] 的**结论对象**（单测用）。
     *
     * `matchNow` 的签名是返回一行诊断文本，测试拿不到带 `effects` 的 `FrameOutcome`，
     * 所以这里留一个只读出口。生产代码不读它。
     */
    @Volatile
    var lastOutcomeForTest: FrameOutcome? = null
        private set

    /**
     * **App 自己绘制在屏幕上的区域**（归一化坐标 0..1，与 [TextBlock] 同基准）。
     *
     * 由 `OverlayService` 在布局后写入。这些区域里的文字是**我们自己画的**
     * （状态条 `应选：X…`、调试面板），绝不能参与"找游戏选项气泡"的判定。
     *
     * ### 为什么必须排除（用户反馈 + 离线验证）
     *
     * 用户：「择律后回到主界面，高亮框却没即时消失…旧的高亮框就会误导玩家」，
     * 并判断「就是因为你 OCR 识别到了自己的提示框」。离线验证这条路径**成立**：
     *
     * ```
     * 状态条被切成「蝶恋花」独立块  → Hit(蝶恋花, target=top=0.44)
     * 排除自绘区域后               → PaiOnly
     * ```
     *
     * 机制：状态条画的是 `应选：蝶恋花（屏上没找到选项气泡）`。OCR 一旦在括号处断块，
     * 就产出一个**恰好等于词牌名的块**，而 `scanOptions` 的第一优先判据正是
     * "整块恰好等于词牌名" → 框被画到 App 自己的文案上。
     *
     * 更糟的是会**自锁**：框画到状态条上 → 状态条文字被 OCR 读回 → 判定继续命中 →
     * `HIGHLIGHT_TTL_MS` 不断被重置 → **框永远不消失**（正是用户看到的现象）。
     *
     * 用「自己画在哪」来排除，比靠坐标阈值猜可靠得多 —— 这个信息本来就是已知的。
     *
     * 类型用 `List<FloatArray>`（`[left, top, right, bottom]`，归一化）而不是
     * `RectF`：后者是 Android 类，会让 JVM 单测无法构造这个值来验证排除逻辑。
     */
    @Volatile
    var selfDrawnBounds: List<FloatArray> = emptyList()

    /**
     * 最近一帧的匹配结论（诊断用，始终是最新一次的评估结果，**不走冷却缓存**）。
     *
     * 出框失败时，事件日志里靠它区分"OCR 没读到首句"和"读到了但匹配不上"。
     */
    @Volatile
    var lastResult: String? = null
        private set

    /**
     * 命中/未命中的回调；第二个参数是**该结论对应的那一帧**的时刻
     * （[SystemClock.elapsedRealtime]），供悬浮层判断结果是否已经过期。
     * 第三个参数是诊断信息（调试面板用，可能为 null）。
     * 调用方负责线程切换（OverlayService 自己做）。
     */
    @Volatile
    var listener: ((FrameOutcome, Long, FrameDiag?) -> Unit)? = null

    // ------------------------------------------------------------------ 调度
    /** 是否允许处理该时间点的一帧（节流 + 省电模式）。 */
    fun shouldAcceptFrame(now: Long): Boolean = !busy.get() && now >= nextAllowedAt

    /** 记一帧已受理，推进下一次允许的时间。 */
    fun markAccepted(now: Long) {
        nextAllowedAt = now + if (idleMode) Config.IDLE_FRAME_INTERVAL_MS else Config.MIN_FRAME_INTERVAL_MS
    }

    /**
     * 本帧是否还要跑「第二段（找气泡）」。
     *
     * 刚命中过且还在冷却期里 → 跳过：同一局结果不会变，没必要每秒重算两次
     * （省电大头就在这里）。
     */
    fun shouldRunOptionsStage(now: Long): Boolean = now - lastHitAt >= Config.HIT_COOLDOWN_MS

    /** 连续未命中 → 进入省电模式（只影响取帧频率）。 */
    fun noteNoHit() {
        if (noHitFrames.incrementAndGet() >= Config.FRAMES_BEFORE_IDLE) idleMode = true
    }

    fun noteHit(now: Long) {
        noHitFrames.set(0)
        idleMode = false
        lastHitAt = now
    }

    // ------------------------------------------------------------------ 主流程
    /**
     * 处理一帧（异步）。同一时刻只处理一帧，返回 false 表示本帧被丢弃。
     *
     * @param blocks 本帧全部文本块（由调用方对当帧做整屏一次 OCR 得到）
     * @param frameAt 取到这一帧的时刻（[SystemClock.elapsedRealtime]），**必须由取帧方
     *                在取到帧的那一刻记录**并一路带下来，否则「过期结果不许画框」会失效
     */
    fun onFrame(
        blocks: List<TextBlock>,
        frameWidth: Int,
        frameHeight: Int,
        frameAt: Long = clock(),
        debug: Boolean = false,
        screenWidth: Int = frameWidth,
        screenHeight: Int = frameHeight,
        rotation: Int = 0,
    ): Boolean {
        if (!shouldAcceptFrame(frameAt)) return false
        markAccepted(frameAt)
        busy.set(true)
        frameCounter.incrementAndGet()
        executor.execute {
            try {
                // 调试模式：不看冷却缓存，每帧都真算一遍，才能看到实时状态
                val inCooldown = !debug && !shouldRunOptionsStage(frameAt)
                val evaluated = evaluateInternal(blocks, frameAt, inCooldown)
                val outcome = evaluated.outcome
                // 诊断：记录最新一次的真实评估结果（不受冷却缓存影响）
                lastResult = when (outcome) {
                    is FrameOutcome.Hit ->
                        "命中 ${outcome.pai} sim=%.3f".format(outcome.similarity)
                    is FrameOutcome.PaiOnly -> "只认出词牌 ${outcome.pai}（屏上没找到气泡）"
                    is FrameOutcome.NoMatch ->
                        "首句未匹配（顶部候选: ${evaluated.headText.ifEmpty { "空" }}）"
                    is FrameOutcome.Empty -> "OCR 无文本"
                }
                val diag = if (debug) {
                    FrameDiag(
                        frameCount = frameCounter.get(),
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        rotation = rotation,
                        blocks = blocks,
                        headText = evaluated.headText.ifEmpty { Matcher.topTextSample(blocks) },
                        pai = evaluated.pai,
                        similarity = evaluated.similarity,
                        target = (outcome as? FrameOutcome.Hit)?.target,
                        idle = idleMode,
                    )
                } else {
                    null
                }
                when (outcome) {
                    is FrameOutcome.Empty, is FrameOutcome.NoMatch -> {
                        noteNoHit()
                        if (lastFrameHit) {
                            // 命中刚结束（例如已经选完）：补发一次，让悬浮层立刻清掉高亮
                            lastFrameHit = false
                            cachedOutcome = null
                            cachedVerseId = -1
                            listener?.invoke(FrameOutcome.NoMatch, frameAt, diag)
                            return@execute
                        }
                    }

                    is FrameOutcome.PaiOnly, is FrameOutcome.Hit -> {
                        lastFrameHit = true
                        // 只有「真的算过」的那次才续期冷却；复用缓存不能续期，
                        // 否则 2fps 下每帧都续期 → 冷却永不结束 → 第二段再也不跑
                        if (!evaluated.fromCache) noteHit(frameAt)
                    }
                }
                listener?.invoke(outcome, frameAt, diag)
            } catch (t: Throwable) {
                // 任何一帧异常都不能把服务带走
                android.util.Log.w(TAG, "帧处理失败", t)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /**
     * 一帧的处理结果。
     *
     * [fromCache] 区分「复用了上次结论」和「这次真的算过」——冷却期的续期只能由
     * **真的算过**的那次触发，否则 2fps 下每帧都续期，冷却永远不结束（第二段就永远不跑了）。
     */
    private data class Evaluated(
        val outcome: FrameOutcome,
        val fromCache: Boolean,
        val headText: String = "",
        val pai: String = "",
        val similarity: Double = 0.0,
    )

    /** 同步版：单测直接调用，逻辑与 [onFrame] 内部一致（不含线程切换）。 */
    fun evaluate(
        blocks: List<TextBlock>,
        frameWidth: Int,
        frameHeight: Int,
        now: Long = clock(),
        inCooldown: Boolean = !shouldRunOptionsStage(now),
    ): FrameOutcome = evaluateInternal(blocks, now, inCooldown).outcome

    /**
     * **同步**跑一帧并返回结论文案（走冷却逻辑），供调用方把
     * 「本帧结论」和「本帧 OCR 原文」打进同一条日志。
     *
     * 之所以需要它：[onFrame] 是异步的，调用方读 [lastResult] 永远读到的是
     * 上一帧（或首帧的 null），诊断时会被误导。
     */
    fun matchNow(
        blocks: List<TextBlock>,
        frameWidth: Int,
        frameHeight: Int,
        frameAt: Long,
        debug: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        rotation: Int,
    ): String {
        if (blocks.isEmpty()) return "OCR 无文本"
        val verseIndex = index() ?: return "索引未加载"
        val inCooldown = !debug && !shouldRunOptionsStage(frameAt)
        val evaluated = evaluateInternal(blocks, frameAt, inCooldown)
        // 单测用出口：本方法只返回一行诊断文本，拿不到带 `effects` 的 FrameOutcome
        lastOutcomeForTest = evaluated.outcome
        // 同步路径也要推进冷却/省电状态，否则行为与异步路径不一致
        when (val outcome = evaluated.outcome) {
            is FrameOutcome.Empty, is FrameOutcome.NoMatch -> noteNoHit()
            is FrameOutcome.PaiOnly, is FrameOutcome.Hit -> {
                lastFrameHit = true
                if (!evaluated.fromCache) noteHit(frameAt)
            }
        }
        val text = when (val outcome = evaluated.outcome) {
            is FrameOutcome.Hit -> "命中 ${outcome.pai} %.3f".format(outcome.similarity)
            is FrameOutcome.PaiOnly -> "仅词牌 ${outcome.pai}（未找到气泡）"
            is FrameOutcome.NoMatch -> "首句未匹配"
            is FrameOutcome.Empty -> "OCR 无文本"
        }
        lastResult = text
        // 同步通知悬浮层（OverlayService 自己切线程）
        val diag = FrameDiag(
            frameCount = frameCounter.get(),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rotation = rotation,
            blocks = blocks,
            headText = evaluated.headText.ifEmpty { Matcher.topTextSample(blocks) },
            pai = evaluated.pai,
            similarity = evaluated.similarity,
            target = (evaluated.outcome as? FrameOutcome.Hit)?.target,
            idle = idleMode,
        )
        listener?.invoke(evaluated.outcome, frameAt, diag)
        return text
    }

    /** 本帧的诊断信息（调试面板用）。 */
    fun diagnose(blocks: List<TextBlock>, now: Long = clock()): FrameDiag {
        val e = evaluateInternal(blocks, now, inCooldown = false)
        return FrameDiag(
            frameCount = frameCounter.get(),
            frameWidth = 0,
            frameHeight = 0,
            screenWidth = 0,
            screenHeight = 0,
            rotation = 0,
            blocks = blocks,
            headText = e.headText,
            pai = e.pai,
            similarity = e.similarity,
            target = (e.outcome as? FrameOutcome.Hit)?.target,
            idle = idleMode,
        )
    }

    /**
     * 核心：两段式匹配 + 冷却期缓存。
     *
     * 缓存**必须带词句 id 校验**：冷却期内如果换了一局（屏幕上换成另一句词），
     * 直接返回旧结论会把上一局的词牌和高亮框画到新一局上（违反"宁可不提示，也不误报"）。
     */
    private fun evaluateInternal(blocks: List<TextBlock>, now: Long, inCooldown: Boolean): Evaluated {
        if (blocks.isEmpty()) return Evaluated(FrameOutcome.Empty, fromCache = false)
        // 索引还没加载完（启动瞬间）→ 本帧无结论，不报错
        val verseIndex = index() ?: return Evaluated(FrameOutcome.Empty, fromCache = false)

        // **先剔掉 App 自己画的那片区域里的文字**（状态条 / 调试面板）。
        // 详见 [selfDrawnBounds] 的说明：不排除的话，状态条里的词牌名会被当成气泡，
        // 而且会自锁导致高亮框永不消失 —— 这正是用户反馈的现象。
        val selfDrawn = selfDrawnBounds
        val usable = if (selfDrawn.isEmpty()) blocks else blocks.filterNot { b ->
            val p = b.normalized()
            selfDrawn.any { r ->
                p.centerX >= r[0] && p.centerX <= r[2] &&
                    p.centerY >= r[1] && p.centerY <= r[3]
            }
        }
        if (usable.isEmpty()) {
            cachedOutcome = null
            cachedVerseId = -1
            return Evaluated(FrameOutcome.NoMatch, fromCache = false)
        }

        // 诊断用：顶部区域实际读到的文本（未命中时也要能看到，否则只能猜）
        val topSample = Matcher.topTextSample(usable)

        val head = Matcher.scanHead(usable, verseIndex)
        if (head !is HeadMatch.Hit) {
            cachedOutcome = null
            cachedVerseId = -1
            return Evaluated(FrameOutcome.NoMatch, fromCache = false, headText = topSample)
        }

        // 冷却期复用：**只复用"命中"结论，绝不复用"没找到气泡"**。
        //
        // ### 这里曾经有个影响很大的 bug（用户反馈「开面板很快、关掉很慢」）
        //
        // 原实现的条件是「冷却期内 + 词牌相同」就返回缓存，而缓存里存的可能是
        // `PaiOnly`（"认出了词牌，但屏上没找到气泡"）。**那本来是一个要去重试的
        // 失败状态，却被当成结论复用了 `HIT_COOLDOWN_MS`（3000ms）。**
        //
        // 更糟的是 `matchNow` 里 `inCooldown = !debug && ...`：调试面板开着时
        // debug=true → `inCooldown` 永远 false → 每帧都重新扫气泡 → 立刻出框。
        // 所以**开面板反而更快**，且真机日志里每题出现约 2.5~3.2 秒的固定等待
        // （正好等于冷却期长度）。
        //
        // 修法：只有缓存是"完整命中"（气泡也找到了）时才复用。
        // 冷却的本意（同一局结果不变、省一次第二段扫描）本来就只对成功结论成立。
        val cached = cachedOutcome
        val cachedComplete = cached is FrameOutcome.Hit
        if (inCooldown && cachedComplete && cachedVerseId == head.verse.id) {
            return Evaluated(cached!!, fromCache = true, headText = head.matchedText,
                pai = head.verse.pai, similarity = head.similarity)
        }

        // 用 **usable**（已剔除 App 自绘区域）而不是原始 blocks —— 否则状态条里
        // 的词牌名仍会被 scanOptions 选中，框又画回自己的文案上（见 selfDrawnBounds）。
        val midBlocks = FramePreprocessor.midArea(usable)
        val target = Matcher.scanOptions(midBlocks, head.verse.pai, verseIndex)
            ?: Matcher.scanOptions(usable, head.verse.pai, verseIndex)
        val outcome = if (target == null) {
            // 诊断：真机日志里出现过「气泡明明在同一帧的 frame.ocr 明细中、坐标也在
            // 0.30~0.80 区间内，却报未找到」。离线用真机原文块复现不出来（Matcher.match、
            // FramePipeline.evaluate、像素空间映射三条路径都判 Hit），说明"日志里看到的块"
            // 与"匹配时真正拿到的块"存在差异。把匹配那一刻的实况打出来才能定死。
            if (MatcherDebug.enabled) {
                MatcherDebug.log(
                    "optMiss",
                    "应选=${head.verse.pai} 收到${blocks.size}块 中区${midBlocks.size}块 " +
                        "中区块=[${midBlocks.joinToString(" ") {
                            "%.2f:%s".format(it.normalized().centerY, it.text.take(6))
                        }}] " +
                        "全区含该词牌=${blocks.any {
                            VerseIndex.normalize(it.text) == VerseIndex.normalize(head.verse.pai)
                        }}",
                )
            }
            FrameOutcome.PaiOnly(
                head.verse.pai, head.matchedText, head.similarity, paiEffectsOf(head.verse),
            )
        } else {
            FrameOutcome.Hit(
                head.verse.pai, head.matchedText, head.similarity, target, paiEffectsOf(head.verse),
            )
        }
        cachedOutcome = outcome
        cachedVerseId = head.verse.id
        return Evaluated(outcome, fromCache = false, headText = head.matchedText,
            pai = head.verse.pai, similarity = head.similarity)
    }

    /** 日志用：把本帧文本拼成一行，便于对照真机 OCR 结果。 */
    fun summarize(blocks: List<TextBlock>): String =
        blocks.joinToString(" | ") { VerseIndex.normalize(it.text) }

    /**
     * 取该词句的效果（不含「词元」）。
     *
     * 用 `verse.head` 作 key —— 效果表就是按索引首句字面量写的，
     * 而索引 `head` 本身已是规范化形式，所以是直接等值查表（由 `EffectIndexTest` 守护）。
     */
    private fun paiEffectsOf(verse: Verse): List<PaiEffect> =
        effects?.invoke()?.forHead(verse.head).orEmpty()

    fun shutdown() {
        listener = null
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "FramePipeline"
    }
}

/** 裁剪/过滤：只按归一化坐标判断归属，不碰像素。 */
object FramePreprocessor {

    /** 顶部区域：找首句用（[Config.TOP_CROP_TOP]..[Config.TOP_CROP_BOTTOM]）。 */
    fun topArea(blocks: List<TextBlock>): List<TextBlock> = blocks.filter {
        val cy = it.normalized().centerY
        cy >= Config.TOP_CROP_TOP && cy <= Config.TOP_CROP_BOTTOM
    }

    /** 中部区域：找词牌气泡用（[Config.MID_CROP_TOP]..[Config.MID_CROP_BOTTOM]）。 */
    fun midArea(blocks: List<TextBlock>): List<TextBlock> = blocks.filter {
        val cy = it.normalized().centerY
        cy >= Config.MID_CROP_TOP && cy <= Config.MID_CROP_BOTTOM
    }

    /** 判断一个块是否落在给定纵向比例区间内。 */
    fun inVerticalBand(block: TextBlock, topRatio: Float, bottomRatio: Float): Boolean {
        val cy = block.normalized().centerY
        return cy >= topRatio && cy <= bottomRatio
    }
}
