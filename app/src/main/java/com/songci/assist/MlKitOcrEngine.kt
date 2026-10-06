package com.songci.assist

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * ML Kit 中文识别（**bundled** 模型：随 APK 打包，不需要 Play 服务/网络）。
 *
 * 产出粒度 = ML Kit 的**行**（每行一个 [TextBlock]），原因是 [Matcher] 要按行拼接
 * （首句可能被拆成两行）并按行定位词牌气泡；块级坐标会把三行合成一个大矩形，
 * 反而画不准框。
 *
 * 注意：`recognize(bitmap)` 是本类自己的方法（参数是 Android 类型），
 * [OcrEngine] 接口故意只接收尺寸，以便纯 JVM 单测替换实现。
 */
class MlKitOcrEngine : OcrEngine {

    private var recognizer: TextRecognizer? =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /** 真机入口：对一帧做识别（坐标换算成相对该帧的比例）。 */
    fun recognize(bitmap: Bitmap): OcrFrameResult = recognize(bitmap.width, bitmap.height, bitmap)

    override fun recognize(frameWidth: Int, frameHeight: Int): OcrFrameResult =
        throw UnsupportedOperationException("真机实现需要 Bitmap，请调用 recognize(bitmap)")

    private fun recognize(frameWidth: Int, frameHeight: Int, bitmap: Bitmap): OcrFrameResult {
        val client = recognizer ?: return OcrFrameResult(emptyList(), frameWidth, frameHeight)
        val input = InputImage.fromBitmap(bitmap, 0)
        return try {
            val started = System.currentTimeMillis()
            val visionText = Tasks.await(client.process(input))
            OcrFrameResult(
                blocks = toBlocks(visionText, frameWidth, frameHeight),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                ocrMillis = System.currentTimeMillis() - started,
            )
        } catch (t: Throwable) {
            // 识别失败（超时/输入异常）按「本帧没有文本」处理，不要打断整局
            android.util.Log.w(TAG, "OCR 失败", t)
            OcrFrameResult(emptyList(), frameWidth, frameHeight)
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }

    private fun toBlocks(result: Text, frameWidth: Int, frameHeight: Int): List<TextBlock> {
        val out = ArrayList<TextBlock>(result.textBlocks.size * 2)
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box: Rect = line.boundingBox ?: continue
                if (line.text.isBlank()) continue
                out += TextBlock.pixels(
                    text = line.text,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    confidence = null,
                    lines = listOf(line.text),
                )
            }
        }
        return out
    }

    companion object {
        private const val TAG = "MlKitOcrEngine"
    }
}
