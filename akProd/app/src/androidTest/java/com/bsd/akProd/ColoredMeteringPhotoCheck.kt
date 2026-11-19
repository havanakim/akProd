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

object ColoredMeteringPhotoCheck {
    fun run(test: Instrumentation,assets: Context,target: Context,injection:Boolean=false) {
        val page=if(injection) Cp3TableReader.INJECTION else Cp3TableReader.METERING
        val folder=if(injection) "coloredInjection" else "colored1119"
        val korean=TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val report=JSONObject(); val failures=mutableListOf<String>()
        try { runBlocking {
            for(name in assets.assets.list(folder).orEmpty().sorted()) {
                val path="$folder/$name"
                val decoded=assets.assets.open(path).use { BitmapFactory.decodeStream(it)!! }
                val rotation=assets.assets.open(path).use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees }
                val image=if(rotation==0) decoded else android.graphics.Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,android.graphics.Matrix().apply { postRotate(rotation.toFloat()) },true).also { decoded.recycle() }
                val votes=linkedMapOf<String,MutableMap<String,Int>>()
                val anchors=linkedMapOf<String,Cp3TableReader.Word>()
                val rows=mutableListOf<Cp3TableReader.Word>(); val passes=JSONObject()
                fun read(text:com.google.mlkit.vision.text.Text,w:Int,h:Int,roi:android.graphics.RectF,pass:String) {
                    val words=text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e -> e.boundingBox?.let { b ->
                        Cp3TableReader.Word(e.text,roi.left+b.left.toFloat()/w*roi.width(),roi.top+b.top.toFloat()/h*roi.height(),roi.left+b.right.toFloat()/w*roi.width(),roi.top+b.bottom.toFloat()/h*roi.height())
                    } }
                    rows.addAll(words.filter { it.text in setOf("압력","속도","위치","배압") })
                    val result=Cp3TableReader.read(words+anchors.values+rows.distinct(),Cp3TableReader.METERING)
                    result.headers.forEach { anchors.putIfAbsent(it.text,it) }
                    result.cells.forEach { (key,cell) ->
                        val counts=votes.getOrPut(key) { linkedMapOf() }; counts[cell.value]=(counts[cell.value] ?: 0)+1
                    }
                    passes.put(pass,JSONObject().put("text",text.text).put("values",JSONObject(result.cells.mapValues { it.value.value })))
                }
                val detected=ColoredHmiOcr.read(image,page,korean,latin)
                detected.values.forEach { (key,value) -> votes[key]=linkedMapOf(value to 1) }
                passes.put("green",detected.details)
                val expected=linkedMapOf<String,String>()
                for(stage in 1..5) { expected["METER_${stage}_PRESSURE"]="140"; expected["METER_${stage}_SPEED"]="42"; expected["METER_${stage}_POSITION"]=listOf("10","50","100","150","186")[stage-1]; expected["METER_${stage}_BACK_PRESSURE"]="24" }
                expected["SUCKBACK_1_PRESSURE"]="130"; expected["SUCKBACK_1_SPEED"]="44"; expected["SUCKBACK_1_POSITION"]="1"
                if(injection) {
                    expected.clear()
                    val first=name.startsWith("20261113")
                    val truth=if(first) listOf(listOf("0",null,"0",null),listOf("0",null,"0",null),listOf("125","26","30",null),listOf("138","32","25","25"),listOf("136","30","25","48"),listOf("130","26","25","68"),listOf("88","10","6","82"),listOf("58","8","12","90"))
                        else listOf(listOf("0",null,"0",null),listOf("0",null,"0",null),listOf("78","8","25",null),listOf("78","11","30","23"),listOf("76","10","30","48"),listOf("66","5","30","100"),listOf("40","2","20","138"),listOf("22","2","20","160"))
                    Cp3TableReader.stages.forEachIndexed { i,stage ->
                        if(name.contains("090526") && i>5 || name.contains("090531") && i<2) return@forEachIndexed
                        Cp3TableReader.rowLabels.keys.forEachIndexed { row,key -> truth[i][row]?.let { expected["${stage}_$key"]=it } }
                    }
                }
                val final=votes.mapValues { (_,counts) -> if(counts.size==1) counts.keys.single() else null }
                expected.forEach { (key,value) -> if(final[key]!=value) failures.add("$name $key expected=$value actual=${final[key]} votes=${votes[key]}") }
                check(votes.keys.none { it in Cp3TableReader.disabledFor(page) })
                report.put(name,JSONObject().put("passes",passes).put("votes",JSONObject(votes)).put("final",JSONObject(final)))
                File(target.filesDir,if(injection) "colored-injection-photo.json" else "colored-metering-photo.json").writeText(report.toString(2)); image.recycle()
                test.sendStatus(0,Bundle().apply { putString("stream","Colored $name completed\n") })
            }
        }; check(failures.isEmpty()) { failures.joinToString("\n") }
            test.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Colored metering photos passed\n") })
        } catch(e:Throwable) { test.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",e.stackTraceToString()) }) }
        finally { korean.close(); latin.close() }
    }
}
