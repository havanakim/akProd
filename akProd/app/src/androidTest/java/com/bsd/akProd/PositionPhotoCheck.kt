package com.bsd.akProd

import android.app.Instrumentation
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

object PositionPhotoCheck {
    fun run(runner:Instrumentation,assets:Context,target:Context) {
        val latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val korean=TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val report=JSONObject()
        try {
            runBlocking {
                for(name in assets.assets.list("positions").orEmpty().sorted()) {
                    val raw=assets.assets.open("positions/$name").use { BitmapFactory.decodeStream(it)!! }
                    val rotation=assets.assets.open("positions/$name").use { ExifInterface(it).rotationDegrees }
                    val image=if(rotation==0) raw else Bitmap.createBitmap(raw,0,0,raw.width,raw.height,Matrix().apply { postRotate(rotation.toFloat()) },true).also { raw.recycle() }
                    val anchors=linkedMapOf<String,Cp14TableReader.Word>()
                    val votes=linkedMapOf<String,MutableMap<String,Int>>()
                    val passes=JSONObject()
                    fun accept(text:Text,w:Int,h:Int,roi:RectF,pass:String) {
                        val words=text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e -> e.boundingBox?.let { b ->
                            Cp14TableReader.Word(e.text,roi.left+b.left.toFloat()/w*roi.width(),roi.top+b.top.toFloat()/h*roi.height(),roi.left+b.right.toFloat()/w*roi.width(),roi.top+b.bottom.toFloat()/h*roi.height())
                        } }
                        val table=Cp14TableReader.read(words+anchors.values)
                        table.headers.forEach { anchors.putIfAbsent(it.text,it) }
                        val values=JSONObject()
                        table.cells.filterKeys { it.startsWith("IF") || it=="S0" }.forEach { (key,cell) ->
                            val g=cell.words
                            val corrected=CaptureNumberCorrection.correct(image,g.minOf { it.left },g.minOf { it.top },g.maxOf { it.right },g.maxOf { it.bottom },g.joinToString("") { it.text })
                            val value=Cp14TableReader.number(corrected ?: cell.value) ?: return@forEach
                            val counts=votes.getOrPut(key) { linkedMapOf() }; counts[value]=(counts[value] ?: 0)+1
                            values.put(key,value)
                        }
                        passes.put(pass,JSONObject().put("text",text.text).put("values",values))
                    }
                    val full=RectF(0f,0f,1f,1f)
                    accept(LcdOcrPipeline.recognize(korean,image),image.width,image.height,full,"original")
                    accept(LcdOcrPipeline.recognize(latin,image),image.width,image.height,full,"latin")
                    LcdOcrPipeline.run(image,latin,korean,accept=::accept,notice={})
                    for(anchor in anchors.values.filter { it.text.startsWith("IF") || it.text=="S0" }.toList()) {
                        val peers=anchors.values.filter { it.text.startsWith("IF") || it.text=="S0" }.sortedBy { it.x }
                        val i=peers.indexOf(anchor); val half=anchor.height*3f
                        val l=maxOf(0f,anchor.x-half,if(i>0) (peers[i-1].x+anchor.x)/2 else 0f)
                        val r=minOf(1f,anchor.x+half,if(i+1<peers.size) (peers[i+1].x+anchor.x)/2 else 1f)
                        val t=anchor.bottom+anchor.height*.03f
                        val b=minOf(1f,anchor.bottom+anchor.height*2.2f,anchors.values.filter { it.top>t && !it.text.startsWith("IF") && it.text!="S0" }.minOfOrNull { it.top } ?: 1f)
                        if(r<=l || b<=t) continue
                        val x=(l*image.width).toInt(); val y=(t*image.height).toInt()
                        val w=((r-l)*image.width).toInt(); val h=((b-t)*image.height).toInt()
                        if(w<2 || h<2) continue
                        val crop=Bitmap.createBitmap(image,x,y,w,h)
                        val roi=RectF(x.toFloat()/image.width,y.toFloat()/image.height,(x+w).toFloat()/image.width,(y+h).toFloat()/image.height)
                        try { LcdOcrPipeline.run(crop,latin,selected=true,accept={ text,cw,ch,_,pass -> accept(text,cw,ch,roi,"${anchor.text}-$pass") },notice={}) }
                        finally { crop.recycle() }
                    }
                    report.put(name,JSONObject().put("votes",JSONObject(votes.mapValues { JSONObject(it.value) })).put("passes",passes))
                    File(target.filesDir,"position-photos.json").writeText(report.toString(2)); image.recycle()
                    runner.sendStatus(0,Bundle().apply { putString("stream","Position photo $name completed\n") })
                }
            }
            runner.finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Saved position-photos.json\n") })
        } catch(e:Throwable) { runner.finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",e.stackTraceToString()) }) }
        finally { latin.close(); korean.close() }
    }
}
