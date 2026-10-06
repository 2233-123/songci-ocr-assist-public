package com.songci.assist

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 投屏取帧器：**一步到位**的完整实现。
 *
 * ### 三条来自社区实战的结论（每条我都踩过坑）
 *
 * 1. **VirtualDisplay 尺寸必须等于真实显示尺寸**，用 `Display.getRealMetrics()` 取。
 *    - 用 `resources.displayMetrics` → 应用窗口尺寸（实测 1200x1800，屏幕是 1200x2608）；
 *    - 用 `maximumWindowMetrics` → 2608x1200，实际显示 1200x2608；
 *    - 用 `currentWindowMetrics` → 2608x1200，实际显示 2464x1152。
 *    只要两者不一致，系统就会把镜像内容**缩放并偏移**塞进来 —— 我 pull 下来的帧就是
 *    "一大片黑 + 角落一小块灰"（约 45% 缩放），OCR 一个字都读不出。
 *    参考：[auto-mobile #4785](https://github.com/kaeawc/auto-mobile/issues/4785)
 *    （"VirtualDisplay stays at the original orientation's dimensions, so the mirrored
 *    content is squished/letterboxed"）。
 *
 * 2. **方向变化后必须换 surface，而且换之前要先 detach 旧 surface**。
 *    [droidVNC-NG #336](https://github.com/bk138/droidVNC-NG/issues/336) 的验证清单：
 *    - `onCapturedContentResize` 单独用 ❌ 没用；
 *    - 换 surface 前**先 detach 旧 surface** ✅ 解决（作者标了 🎉）。
 *    我之前的错误：直接 `setSurface(新)` 然后 close 旧 reader，导致旧 BufferQueue
 *    被 abandoned（`dequeueBuffer: BufferQueue has been abandoned`），每次只续 2 帧。
 *
 * 3. **绝不能 release VirtualDisplay**：那会让系统判定投影失效并立即回收
 *    （实测投屏只活 21ms）。只能 `resize()` + 换 surface。
 */
class ScreenFrameReader(
    private val projection: MediaProjection,
    private val handler: Handler,
    private val onFrame: (frame: FrameData) -> Unit,
    private val onLog: (tag: String, message: String) -> Unit,
) {

    /** 一帧的原始像素 + 尺寸 */
    class FrameData(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val capturedAt: Long,
    )

    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    /** 当前消费者尺寸 */
    @Volatile
    private var readerWidth = 0

    @Volatile
    private var readerHeight = 0

    /** 内容当前真实尺寸（onCapturedContentResize 通知） */
    @Volatile
    private var contentWidth = 0

    @Volatile
    private var contentHeight = 0

    @Volatile
    private var released = false

    private val swapping = AtomicBoolean(false)

    /** 帧计数（诊断） */
    @Volatile
    var frameCount = 0
        private set

    /** 用真实显示尺寸创建 VirtualDisplay 与 ImageReader（1:1，不缩放）。 */
    fun start(context: Context): Boolean {
        val size = realDisplaySize(context)
        val width = size[0]
        val height = size[1]
        if (width <= 0 || height <= 0) {
            onLog("frame.setup", "无法获取真实显示尺寸")
            return false
        }
        val density = context.resources.displayMetrics.densityDpi
        onLog("frame.setup", "真实显示 ${width}x$height dpi=$density（1:1 不缩放）")

        contentWidth = width
        contentHeight = height

        val imageReader = newReader(width, height) ?: return false
        reader = imageReader
        readerWidth = width
        readerHeight = height

        val vd = try {
            projection.createVirtualDisplay(
                "songci-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
        } catch (t: Throwable) {
            onLog("frame.setup", "createVirtualDisplay 失败: ${t.javaClass.simpleName}")
            return false
        }
        virtualDisplay = vd
        onLog("frame.setup", "VirtualDisplay 已建立（=真实显示尺寸，不会缩放）")
        return true
    }

    private fun newReader(width: Int, height: Int): ImageReader? = try {
        ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3).also { r ->
            r.setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        }
    } catch (t: Throwable) {
        onLog("frame.setup", "ImageReader 创建失败: ${t.javaClass.simpleName}")
        null
    }

    /**
     * 内容尺寸变化：`resize` + **先 detach 旧 surface 再 attach 新的**。
     *
     * 顺序很关键（社区验证）：
     * 1. 先 `setSurface(null)` 把旧 surface 摘下来 —— 否则旧 BufferQueue 被 close 后
     *    会 abandoned，生产者直接失败；
     * 2. 建新尺寸的 ImageReader；
     * 3. attach 新 surface；
     * 4. 延后回收旧 reader（等系统真正切过去）。
     */
    fun onContentResized(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || released) return
        contentWidth = width
        contentHeight = height
        if (width == readerWidth && height == readerHeight) return
        if (!swapping.compareAndSet(false, true)) return
        handler.post {
            try {
                val vd = virtualDisplay
                if (vd == null || released) return@post
                onLog("frame.swap", "内容尺寸变化 → ${width}x$height，换消费者（先 detach 旧 surface）")

                val old = reader
                val new = newReader(width, height)
                if (new == null) {
                    onLog("frame.swap", "新 ImageReader 创建失败，保持原样")
                    return@post
                }

                // (1) 先摘下旧 surface，避免旧 BufferQueue 被弃用后生产者写失败
                runCatching { vd.surface = null }
                    .onFailure { Log.w(TAG, "detach 旧 surface 失败", it) }

                // (2) 调整虚拟显示器尺寸到新的内容尺寸（不是 release！）
                runCatching { vd.resize(width, height, context_density) }
                    .onFailure { Log.w(TAG, "resize 失败", it) }

                // (3) attach 新 surface
                runCatching { vd.surface = new.surface }
                    .onFailure {
                        onLog("frame.swap", "attach 新 surface 失败: ${it.javaClass.simpleName}")
                        runCatching { new.close() }
                        return@post
                    }

                reader = new
                readerWidth = width
                readerHeight = height
                frameCount = 0
                onLog("frame.swap", "换消费者完成，继续取帧")

                // (4) 延后回收旧 reader：等系统真正切到新 surface
                if (old != null) {
                    handler.postDelayed({
                        runCatching { old.close() }
                    }, OLD_READER_CLOSE_DELAY_MS)
                }
            } finally {
                swapping.set(false)
            }
        }
    }

    private var context_density = 480

    fun setDensity(d: Int) {
        context_density = d
    }

    private fun onImageAvailable(r: ImageReader) {
        if (released) return
        // 换消费者期间旧 reader 可能还会回调：直接丢弃，不污染数据
        if (r !== reader) {
            runCatching { r.acquireLatestImage()?.close() }
            return
        }
        frameCount++
        val now = SystemClock.elapsedRealtime()
        val image = try {
            r.acquireLatestImage()
        } catch (t: Throwable) {
            Log.w(TAG, "acquireLatestImage 失败", t)
            null
        } ?: return
        try {
            val pixels = toPixels(image) ?: return
            onFrame(FrameData(pixels, image.width, image.height, now))
        } finally {
            runCatching { image.close() }
        }
    }

    /** RGBA_8888 → IntArray（带 rowStride padding，必须逐行读） */
    private fun toPixels(image: Image): IntArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val pixels = IntArray(width * height)
        if (pixelStride == 4 && rowStride == width * 4) {
            buffer.rewind()
            buffer.asIntBuffer().get(pixels)
            return pixels
        }
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val len = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, len)
            var src = 0
            var dst = y * width
            for (x in 0 until width) {
                val r = row[src].toInt() and 0xFF
                val g = row[src + 1].toInt() and 0xFF
                val b = row[src + 2].toInt() and 0xFF
                val a = row[src + 3].toInt() and 0xFF
                pixels[dst + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                src += pixelStride
            }
        }
        return pixels
    }

    /** 供 Service 记录/释放用（不参与取帧逻辑） */
    fun virtualDisplayOrNull(): VirtualDisplay? = virtualDisplay

    fun release() {
        released = true
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
    }

    companion object {
        private const val TAG = "ScreenFrameReader"

        /** 换消费者后旧 reader 的延迟回收时间（等系统真正切过去） */
        private const val OLD_READER_CLOSE_DELAY_MS = 1500L

        /**
         * 真实显示尺寸。
         *
         * `getRealMetrics` 返回**真实物理显示**，不受窗口/分屏/letterbox 影响，
         * 也不会像 `maximumWindowMetrics` 那样给出与实际不一致的值。
         */
        fun realDisplaySize(context: Context): IntArray {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(dm)
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return intArrayOf(dm.widthPixels, dm.heightPixels)
            }
            val fallback = context.resources.displayMetrics
            return intArrayOf(fallback.widthPixels, fallback.heightPixels)
        }
    }
}
