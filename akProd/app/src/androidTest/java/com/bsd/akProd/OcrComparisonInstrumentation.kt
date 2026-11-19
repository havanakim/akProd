package com.bsd.akProd

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Real-photo experiment only; never connects to the DB or edits user drafts. */
class OcrComparisonInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun legacy(w: Int,h: Int,pixels: IntArray,raw: String): String {
        val segment=LegacySevenSegmentVerifier.verify(w,h,pixels,raw)
        if(segment.checked && !segment.uncertain) return segment.text
        return SlashedZeroDetector.correct(w,h,pixels,raw) ?: raw
    }
    override fun onStart() {
        val engine=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val rows=JSONArray()
        var rawCorrect=0; var oldCorrect=0; var newCorrect=0; var oldConsensus=0; var newConsensus=0
        var stressTotal=0; var stressCorrect=0; var controls=0; var damagedControls=0
        fun number(value: String)=Cp3TableReader.number(value)
        try {
            val cases=JSONArray(context.assets.open("ocr/cases.json").bufferedReader().use { it.readText().removePrefix("\uFEFF") })
            for(i in 0 until cases.length()) {
                val item=cases.getJSONObject(i)
                val image=context.assets.open("ocr/${item.getString("id")}.png").use { checkNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    val pixels=IntArray(image.width*image.height)
                    image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                    val expected=item.getString("expected")
                    val expectedNumber=number(expected)
                    val raw=Tasks.await(engine.process(InputImage.fromBitmap(image,0)),15,TimeUnit.SECONDS).text.replace(Regex("\\s"),"")
                    val before=legacy(image.width,image.height,pixels,raw)
                    val after=DigitNumberCorrection.analyze(image.width,image.height,pixels,raw)
                    if(number(raw)==expectedNumber) rawCorrect++
                    if(number(before)==expectedNumber) oldCorrect++
                    if(number(after.text)==expectedNumber) newCorrect++
                    val previous=linkedSetOf<String>(); val current=linkedSetOf<String>()
                    number(before)?.let(previous::add); number(after.text)?.let(current::add)
                    runBlocking {
                        LcdOcrPipeline.run(image,engine,selected=true,accept={ text,_,_,_,_ ->
                            val value=text.text.replace(Regex("\\s"),"")
                            number(legacy(image.width,image.height,pixels,value))?.let(previous::add)
                            number(DigitNumberCorrection.analyze(image.width,image.height,pixels,value).text)?.let(current::add)
                        },notice={})
                    }
                    if(previous.size==1 && previous.single()==expectedNumber) oldConsensus++
                    if(current.size==1 && current.single()==expectedNumber) newConsensus++
                    val stress=JSONArray()
                    if(expected.contains('0')) for(wrong in listOf('8','9')) {
                        val injected=expected.replace('0',wrong)
                        val result=DigitNumberCorrection.analyze(image.width,image.height,pixels,injected)
                        stressTotal++
                        if(number(result.text)==expectedNumber) stressCorrect++
                        stress.put(JSONObject().put("injected",injected).put("before",legacy(image.width,image.height,pixels,injected))
                            .put("after",result.text).put("uncertain",JSONArray(result.uncertainPositions.toList())))
                    }
                    if(expected.any { it in "89" }) {
                        controls++
                        val result=DigitNumberCorrection.analyze(image.width,image.height,pixels,expected)
                        if(result.text!=expected) damagedControls++
                    }
                    rows.put(JSONObject().put("id",item.getString("id")).put("source",item.getString("source"))
                        .put("expected",expected).put("raw",raw).put("legacy",before).put("improved",after.text)
                        .put("legacyCandidates",JSONArray(previous.toList())).put("improvedCandidates",JSONArray(current.toList()))
                        .put("uncertain",JSONArray(after.uncertainPositions.toList())).put("stress",stress))
                } finally { image.recycle() }
                if((i+1)%10==0) sendStatus(0,Bundle().apply { putString("stream","Compared ${i+1}/${cases.length()} real-photo crops\n") })
            }
            val report=JSONObject().put("cases",rows.length()).put("photos",7)
                .put("rawCorrect",rawCorrect).put("legacyCorrect",oldCorrect).put("improvedCorrect",newCorrect)
                .put("legacyConsensusCorrect",oldConsensus).put("improvedConsensusCorrect",newConsensus)
                .put("forcedErrors",stressTotal).put("forcedErrorsRecovered",stressCorrect)
                .put("genuine8or9Cases",controls).put("genuine8or9Changed",damagedControls).put("results",rows)
            val file=File(targetContext.filesDir,"ocr-comparison.json")
            file.writeText(report.toString(2))
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString().substringBefore("\"results\"")+" report=${file.name}\n") })
        } catch(e: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","OCR comparison failed: ${e.stackTraceToString()}\n") })
        } finally { engine.close() }
    }
}
