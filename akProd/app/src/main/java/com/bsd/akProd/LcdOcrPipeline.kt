package com.bsd.akProd

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

/** Extra OCR passes; callers retain their original-image pass and table geometry rules. */
object LcdOcrPipeline {
    fun smoothDisplay(image: Bitmap): Bitmap {
        if(!available) return image
        val source=Mat(); val target=Mat()
        return try {
            Utils.bitmapToMat(image,source)
            Imgproc.GaussianBlur(source,target,Size(5.0,5.0),1.1)
            Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(target,it) }
        } finally { source.release(); target.release() }
    }
    private val available by lazy {
        try {
            OpenCVLoader.initLocal()
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Bounds are normalized in the source image, including integer crop rounding. */
    suspend fun run(
        image: Bitmap, latin: TextRecognizer, korean: TextRecognizer? = null,
        selected: Boolean = false,
        modes: Int = 4,
        targetHeight: Int = 120,
        shouldContinue: () -> Boolean = { true },
        accept: (Text, Int, Int, RectF, String) -> Unit,
        notice: (String) -> Unit
    ) {
        if (!withContext(Dispatchers.Default) { available }) {
            notice("OpenCV를 불러오지 못해 원본 OCR 결과를 사용합니다.")
            return
        }
        val bounds = withContext(Dispatchers.Default) { roi(image, selected) }
        for (mode in 0 until modes.coerceIn(0, 4)) {
            if (!shouldContinue()) break
            coroutineContext.ensureActive()
            val bitmap = try {
                withContext(Dispatchers.Default + NonCancellable) {
                    prepare(
                        image,
                        bounds,
                        selected,
                        mode,
                        targetHeight
                    )
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                notice("LCD 전처리 실패: ${e.javaClass.simpleName}. 원본 결과를 확인하세요.")
                return
            }
            try {
                coroutineContext.ensureActive()
                // Non-cancellable Task wait keeps the bitmap alive until ML Kit releases it.
                val engine = if (mode == 0 && !selected) korean ?: latin else latin
                val text = recognize(engine, bitmap)
                coroutineContext.ensureActive()
                accept(text, bitmap.width, bitmap.height, RectF(bounds), names[mode])
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                notice("LCD 추가 인식 실패: ${e.javaClass.simpleName}. 원본 결과를 확인하세요.")
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun recognize(recognizer: TextRecognizer, image: Bitmap): Text =
        suspendCoroutine { continuation ->
            try {
                recognizer.process(InputImage.fromBitmap(image, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
                    .addOnCanceledListener { continuation.resumeWithException(java.util.concurrent.CancellationException()) }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }

    private val names = listOf("CLAHE", "Otsu", "Otsu 방향별 closing", "적응형 방향별 closing")

    private fun roi(image: Bitmap, selected: Boolean): RectF {
        if (selected) return RectF(0f, 0f, 1f, 1f)
        val scale = minOf(1f, 480f / maxOf(image.width, image.height))
        val thumb = Bitmap.createScaledBitmap(
            image, maxOf(1, (image.width * scale).toInt()),
            maxOf(1, (image.height * scale).toInt()), true
        )
        try {
            val pixels = IntArray(thumb.width * thumb.height)
            thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)
            val box = MonitorDetector.detect(thumb.width, thumb.height, pixels)
                ?: return RectF(0f, 0f, 1f, 1f)
            // Small margin retains edge headers; detection is optional, never a fixed-position crop.
            return RectF(
                (box.left - .015f).coerceAtLeast(0f), (box.top - .015f).coerceAtLeast(0f),
                (box.right + .015f).coerceAtMost(1f), (box.bottom + .015f).coerceAtMost(1f)
            )
        } finally {
            if (thumb !== image) thumb.recycle()
        }
    }

    private fun prepare(
        image: Bitmap,
        bounds: RectF,
        selected: Boolean,
        mode: Int,
        targetHeight: Int
    ): Bitmap {
        val left = (bounds.left * image.width).roundToInt().coerceIn(0, image.width - 1)
        val top = (bounds.top * image.height).roundToInt().coerceIn(0, image.height - 1)
        val right = (bounds.right * image.width).roundToInt().coerceIn(left + 1, image.width)
        val bottom = (bounds.bottom * image.height).roundToInt().coerceIn(top + 1, image.height)
        bounds.set(
            left.toFloat() / image.width, top.toFloat() / image.height,
            right.toFloat() / image.width, bottom.toFloat() / image.height
        )
        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        val scale =
            if (selected) minOf(4f, targetHeight.toFloat() / crop.height, 1800f / crop.width)
            else minOf(1.5f, 1800f / maxOf(crop.width, crop.height))
        val scaled = Bitmap.createScaledBitmap(
            crop, maxOf(1, (crop.width * scale).toInt()),
            maxOf(1, (crop.height * scale).toInt()), true
        )
        val mats = mutableListOf<Mat>()
        fun mat() = Mat().also { mats.add(it) }
        try {
            val rgba = mat();
            val gray = mat();
            val enhanced = mat();
            val mask = mat();
            val output = mat()
            Utils.bitmapToMat(scaled, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            try {
                clahe.apply(gray, enhanced)
            } finally {
                clahe.collectGarbage()
            }
            if (mode == 0) enhanced.copyTo(output)
            else {
                if (mode == 3) {
                    val limit = minOf(enhanced.rows(), enhanced.cols())
                    if (limit < 3) enhanced.copyTo(mask)
                    else {
                        val block = minOf(31, if (limit % 2 == 0) limit - 1 else limit)
                        Imgproc.adaptiveThreshold(
                            enhanced, mask, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                            Imgproc.THRESH_BINARY, block, 7.0
                        )
                    }
                } else Imgproc.threshold(
                    enhanced,
                    mask,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
                )
                // Closing operates on white ink. Determine polarity from the ROI border.
                var bright = 0;
                var count = 0
                for (x in 0 until mask.cols()) {
                    if (mask.get(0, x)[0] > 127) bright++
                    if (mask.get(mask.rows() - 1, x)[0] > 127) bright++
                    count += 2
                }
                for (y in 0 until mask.rows()) {
                    if (mask.get(y, 0)[0] > 127) bright++
                    if (mask.get(y, mask.cols() - 1)[0] > 127) bright++
                    count += 2
                }
                if (bright * 2 >= count) Core.bitwise_not(mask, mask)
                if (mode >= 2) {
                    // Independent closures avoid compounding vertical and horizontal joins.
                    val length = (if (selected) mask.rows() * .025 else mask.rows() * .002).toInt()
                        .coerceIn(2, 5)
                    val kernelLength = if (length % 2 == 0) length + 1 else length
                    val h = mat();
                    val v = mat()
                    val hk = Imgproc.getStructuringElement(
                        Imgproc.MORPH_RECT,
                        Size(kernelLength.toDouble(), 1.0)
                    ).also { mats.add(it) }
                    val vk = Imgproc.getStructuringElement(
                        Imgproc.MORPH_RECT,
                        Size(1.0, kernelLength.toDouble())
                    ).also { mats.add(it) }
                    Imgproc.morphologyEx(mask, h, Imgproc.MORPH_CLOSE, hk)
                    Imgproc.morphologyEx(mask, v, Imgproc.MORPH_CLOSE, vk)
                    Core.bitwise_or(h, v, mask)
                }
                Core.bitwise_not(mask, output) // Black ink on white for ML Kit.
            }
            Imgproc.cvtColor(output, rgba, Imgproc.COLOR_GRAY2RGBA)
            return Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(rgba, it) }
        } finally {
            mats.forEach { it.release() }
            if (scaled !== image && scaled !== crop) scaled.recycle()
            if (crop !== image) crop.recycle()
        }
    }
}
