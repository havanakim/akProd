package com.bsd.akProd

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Runs the app's mono OCR (MonoTableOcr) on assets/holding1120 photos and compares with the
 * photo truth in assets/holding1120/truth.json. Never writes to DB.
 */
object MonoWordDump {
    fun run(runner: Instrumentation, assets: Context, target: Context, only: String? = null, skip: Set<String> = emptySet()) {
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val report = JSONObject()
        val failures = mutableListOf<String>()
        try {
            val truth = JSONObject(assets.assets.open("holding1120/truth.json").bufferedReader().readText())
            JSONObject(assets.assets.open("metering1120/truth.json").bufferedReader().readText()).let { m -> m.keys().forEach { truth.put(it, m.get(it)) } }
            runBlocking {
                val photos = assets.assets.list("holding1120").orEmpty().filter { it.endsWith(".jpg") }.sorted().map { "holding1120/$it" } +
                    assets.assets.list("positions").orEmpty().sorted().map { "positions/$it" } +
                    assets.assets.list("metering1120").orEmpty().filter { it.endsWith(".jpg") }.sorted().map { "metering1120/$it" }
                for (path in photos.filter { only == null || it.contains(only) }) {
                    val name = path.substringAfter("/")
                    val page = if (path.startsWith("metering1120")) Cp14TableReader.METERING else Cp14TableReader.INJECTION
                    val decoded = assets.assets.open(path).use { BitmapFactory.decodeStream(it)!! }
                    val rotation = assets.assets.open(path).use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees }
                    val image = if (rotation == 0) decoded else android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                        android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true).also { decoded.recycle() }
                    val ocr = MonoTableOcr(image, page, Cp14TableReader.labelsFor(page).keys, skip = skip)
                    ocr.run(latin, korean)
                    val decisions = ocr.decideAll()
                    val values = JSONObject()
                    val expected = truth.optJSONObject(name) ?: truth.getJSONObject(path.substringBefore("/") + "/*")
                    for (key in Cp14TableReader.labelsFor(page).keys) {
                        val decision = decisions[key]
                        val actual = decision?.value
                        values.put(key, JSONObject().put("value", actual ?: JSONObject.NULL)
                            .put("candidates", ocr.candidates[key]?.keys?.joinToString() ?: "")
                            .put("conflict", decision?.conflict?.joinToString() ?: ""))
                        val want = expected.optString(key)
                        if (actual != want) failures.add("$name $key expected=$want actual=$actual candidates=${ocr.candidates[key]?.keys}")
                    }
                    report.put(name, JSONObject().put("headers", ocr.anchors.values.joinToString { "%s@%.4f,%.4f,%.4f,%.4f".format(it.text, it.left, it.top, it.right, it.bottom) })
                        .put("values", values).put("log", ocr.log.toString()))
                    image.recycle()
                    File(target.filesDir, "mono-word-dump.json").writeText(report.toString(1))
                    runner.sendStatus(0, Bundle().apply { putString("stream", "$name done\n") })
                }
            }
            val summary = if (failures.isEmpty()) "All fields match photo truth\n" else failures.joinToString("\n", postfix = "\n")
            runner.finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", summary) })
        } catch (e: Throwable) {
            runner.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", e.stackTraceToString()) })
        } finally { latin.close(); korean.close() }
    }
}
