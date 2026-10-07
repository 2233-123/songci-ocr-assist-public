package com.songci.assist

/**
 * 当前活跃的 [FramePipeline] 的持有者。
 *
 * ### 为什么需要它
 *
 * `OverlayService` 需要把自己**画在屏幕上的区域**告诉流水线（见
 * [FramePipeline.selfDrawnBounds]），否则状态条文案里的词牌名会被当成游戏气泡选中，
 * 并自锁导致高亮框永不消失（用户反馈的现象）。
 *
 * 但管道是 `CaptureService` 创建的、`OverlayService` 拿不到引用。两者同进程，
 * 用一个极薄的持有者传一下即可，不必引入绑定/消息通道。
 *
 * ### 生命周期
 *
 * - `CaptureService` 建好管道后 `set(...)`；销毁时 `set(null)`。
 * - `OverlayService` 读 [get]；为 null 时什么都不做（未开始取帧就是这种状态）。
 */
object PipelineHolder {

    @Volatile
    private var pipeline: FramePipeline? = null

    fun set(p: FramePipeline?) {
        pipeline = p
    }

    fun get(): FramePipeline? = pipeline
}
