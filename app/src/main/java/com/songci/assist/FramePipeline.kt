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
    ) : FrameOutcome

    /** 命中并在屏上定位到气泡。 */
    data class Hit(
        val pai: String,
        val headText: String,
        val similarity: Double,
        val target: HighlightRect,   // HighlightRect 是 data class，因此 Hit 可比较
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

        // 诊断用：顶部区域实际读到的文本（未命中时也要能看到，否则只能猜）
        val topSample = Matcher.topTextSample(blocks)

        val head = Matcher.scanHead(blocks, verseIndex)
        if (head !is HeadMatch.Hit) {
            cachedOutcome = null
            cachedVerseId = -1
            return Evaluated(FrameOutcome.NoMatch, fromCache = false, headText = topSample)
        }

        // 冷却期：同一局结果不变，直接复用上次结论（省一次第二段 OCR）
        val cached = cachedOutcome
        if (inCooldown && cached != null && cachedVerseId == head.verse.id) {
            return Evaluated(cached, fromCache = true, headText = head.matchedText,
                pai = head.verse.pai, similarity = head.similarity)
        }

        val midBlocks = FramePreprocessor.midArea(blocks)
        val target = Matcher.scanOptions(midBlocks, head.verse.pai, verseIndex)
            ?: Matcher.scanOptions(blocks, head.verse.pai, verseIndex)
        val outcome = if (target == null) {
            FrameOutcome.PaiOnly(head.verse.pai, head.matchedText, head.similarity)
        } else {
            FrameOutcome.Hit(head.verse.pai, head.matchedText, head.similarity, target)
        }
        cachedOutcome = outcome
        cachedVerseId = head.verse.id
        return Evaluated(outcome, fromCache = false, headText = head.matchedText,
            pai = head.verse.pai, similarity = head.similarity)
    }

    /** 日志用：把本帧文本拼成一行，便于对照真机 OCR 结果。 */
    fun summarize(blocks: List<TextBlock>): String =
        blocks.joinToString(" | ") { VerseIndex.normalize(it.text) }

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
