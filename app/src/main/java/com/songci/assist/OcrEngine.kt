package com.songci.assist

/** 一帧 OCR 的结果（坐标已换算成相对该帧的归一化比例）。 */
data class OcrFrameResult(
    val blocks: List<TextBlock>,
    val frameWidth: Int,
    val frameHeight: Int,
    val ocrMillis: Long = 0L,
)

/**
 * 屏幕文字识别抽象。
 *
 * **刻意不出现任何 Android 类型**（不写 `Bitmap` 参数），这样：
 *  1. 单测可以在纯 JVM 里替换成假实现，[FramePipeline] 不依赖 android.jar；
 *  2. 将来要换 PaddleOCR 移动端（设计文档风险表）时只改这一处。
 *
 * 真机实现见 [MlKitOcrEngine]（它的 `recognize` 接 `android.graphics.Bitmap`）。
 * [FramePipeline] 在自己的单线程池里同步调用 [recognize]，实现方可以放心阻塞。
 */
interface OcrEngine {
    /**
     * @param frameWidth  帧宽（像素）
     * @param frameHeight 帧高（像素）
     */
    fun recognize(frameWidth: Int, frameHeight: Int): OcrFrameResult
}
