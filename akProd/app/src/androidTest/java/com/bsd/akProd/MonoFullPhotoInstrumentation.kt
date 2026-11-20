package com.bsd.akProd

import android.app.Instrumentation
import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

class MonoFullPhotoInstrumentation: Instrumentation() {
    private var positions=false
    private var dump=false
    private var only:String?=null
    private var holding1119=false
    private var colored1119=false
    private var coloredInjection=false
    override fun onCreate(arguments: Bundle?) { dump=arguments?.getString("dump")=="true"; only=arguments?.getString("only"); positions=arguments?.getString("positions")=="true"; holding1119=arguments?.getString("holding1119")=="true"; colored1119=arguments?.getString("colored1119")=="true"; coloredInjection=arguments?.getString("coloredInjection")=="true"; super.onCreate(arguments); start() }
    override fun onStart() {
        if(dump) { MonoWordDump.run(this,context,targetContext,only); return }
        if(positions) { PositionPhotoCheck.run(this,context,targetContext); return }
        if(colored1119) { ColoredMeteringPhotoCheck.run(this,context,targetContext); return }
        if(coloredInjection) { ColoredMeteringPhotoCheck.run(this,context,targetContext,true); return }
        val engine=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val korean=TextRecognition.getClient(com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build())
        val report=JSONObject()
        val holdingFailures=mutableListOf<String>()
        try {
            runBlocking {
                val names=if(holding1119) context.assets.list("positions").orEmpty().sorted() else listOf("metering","metering-far","holding")
                for(name in names) {
                    val path=if(holding1119) "positions/$name" else "ocr/full-$name.png"
                    val decoded=context.assets.open(path).use { BitmapFactory.decodeStream(it)!! }
                    val rotation=if(holding1119) context.assets.open(path).use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } else 0
                    val image=if(rotation==0) decoded else android.graphics.Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,
                        android.graphics.Matrix().apply { postRotate(rotation.toFloat()) },true).also { decoded.recycle() }
                    val anchors=linkedMapOf<String,Cp14TableReader.Word>()
                    val passes=JSONObject()
                    val localCounts=mutableMapOf<String,MutableMap<String,Int>>()
                    val sourceVerified=mutableMapOf<String,MutableSet<String>>()
                    val zeros=mutableSetOf<String>()
                    val allCandidates=mutableMapOf<String,MutableSet<String>>()
                    fun read(text: com.google.mlkit.vision.text.Text,w:Int,h:Int,roi:android.graphics.RectF,pass:String) {
                        val words=text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e -> e.boundingBox?.let { b ->
                            Cp14TableReader.Word(e.text,roi.left+b.left.toFloat()/w*roi.width(),roi.top+b.top.toFloat()/h*roi.height(),
                                roi.left+b.right.toFloat()/w*roi.width(),roi.top+b.bottom.toFloat()/h*roi.height())
                        } }
                        val table=Cp14TableReader.read(words+anchors.values)
                        table.headers.forEach { anchors.putIfAbsent(it.text,it) }
                        val values=JSONObject()
                        table.cells.forEach { (key,cell) ->
                            val glyphs=cell.words
                            val raw=glyphs.joinToString("") { it.text }
                            val corrected=CaptureNumberCorrection.correct(image,glyphs.minOf { it.left },glyphs.minOf { it.top },glyphs.maxOf { it.right },glyphs.maxOf { it.bottom },raw)
                            Cp14TableReader.number(corrected ?: OcrNumericGlyphs.canonical(raw))?.let { allCandidates.getOrPut(key) { linkedSetOf() }.add(it) }
                            values.put(key,JSONObject().put("raw",raw).put("corrected",corrected ?: raw))
                            if(corrected!=null) Cp14TableReader.number(corrected)?.let { sourceVerified.getOrPut(key) { linkedSetOf() }.add(it) }
                            if(pass=="cell-$key" || pass=="local-$key-CLAHE" || pass=="local-$key-Otsu") {
                                    Cp14TableReader.number(corrected ?: OcrNumericGlyphs.canonical(raw))?.let { value ->
                                    val counts=localCounts.getOrPut(key) { linkedMapOf() }
                                    counts[value]=(counts[value] ?: 0)+1
                                }
                            }
                        }
                        passes.put(pass,JSONObject().put("text",text.text).put("headers",anchors.keys.joinToString()).put("values",values))
                    }
                    val full=android.graphics.RectF(0f,0f,1f,1f)
                    read(LcdOcrPipeline.recognize(korean,image),image.width,image.height,full,"original-korean")
                    val contrast=android.graphics.Bitmap.createBitmap(image.width,image.height,android.graphics.Bitmap.Config.ARGB_8888)
                    val gray=android.graphics.ColorMatrix().apply { setSaturation(0f) }
                    gray.postConcat(android.graphics.ColorMatrix(floatArrayOf(1.35f,0f,0f,0f,-35f,0f,1.35f,0f,0f,-35f,0f,0f,1.35f,0f,-35f,0f,0f,0f,1f,0f)))
                    android.graphics.Canvas(contrast).drawBitmap(image,0f,0f,android.graphics.Paint().apply { colorFilter=android.graphics.ColorMatrixColorFilter(gray) })
                    read(LcdOcrPipeline.recognize(engine,contrast),contrast.width,contrast.height,full,"contrast"); contrast.recycle()
                    val pixels=IntArray(image.width*image.height)
                    image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                    for(index in pixels.indices) {
                        val c=pixels[index]; val r=c shr 16 and 255; val g=c shr 8 and 255; val b=c and 255
                        pixels[index]=if(r>75 && g>55 && r>b*1.3 && g>b*1.2) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                    val mask=android.graphics.Bitmap.createBitmap(pixels,image.width,image.height,android.graphics.Bitmap.Config.ARGB_8888)
                    read(LcdOcrPipeline.recognize(engine,mask),mask.width,mask.height,full,"yellow"); mask.recycle()
                    LcdOcrPipeline.run(image,engine,accept=::read,notice={})
                    for(anchor in anchors.values.filter { !holding1119 || it.text.startsWith("HP") }.toList()) {
                        val peers=anchors.values.filter { it.text.dropLast(1)==anchor.text.dropLast(1) }.sortedBy { it.x }
                        val index=peers.indexOf(anchor)
                        val half=maxOf(anchor.height*3.0f,(anchor.right-anchor.left)*.85f)
                        val l=maxOf(0f,anchor.x-half,if(index>0) (peers[index-1].x+anchor.x)/2 else 0f)
                        val r=minOf(1f,anchor.x+half,if(index+1<peers.size) (peers[index+1].x+anchor.x)/2 else 1f)
                        val t=anchor.bottom+anchor.height*.03f
                        val b=minOf(1f,anchor.bottom+anchor.height*2.2f,Cp14TableReader.nextRowTop(anchor,anchors.values) ?: 1f)
                        if(r<=l || b<=t) continue
                        val x=(l*image.width).toInt(); val y=(t*image.height).toInt()
                        val w=((r-l)*image.width).toInt().coerceAtMost(image.width-x); val h=((b-t)*image.height).toInt().coerceAtMost(image.height-y)
                        if(w<2 || h<2) continue
                        val crop=android.graphics.Bitmap.createBitmap(image,x,y,w,h)
                        val enlarged=android.graphics.Bitmap.createScaledBitmap(crop,w*3,h*3,true)
                        try {
                            read(LcdOcrPipeline.recognize(engine,enlarged),enlarged.width,enlarged.height,
                                android.graphics.RectF(x.toFloat()/image.width,y.toFloat()/image.height,(x+w).toFloat()/image.width,(y+h).toFloat()/image.height),"cell-${anchor.text}")
                            passes.getJSONObject("cell-${anchor.text}").put("verifiedZero",CaptureNumberCorrection.verifiedZero(crop,if(holding1119) 3 else null))
                            val px=IntArray(w*h); crop.getPixels(px,0,w,0,0,w,h)
                            passes.getJSONObject("cell-${anchor.text}").put("zeroProbes",(1..6).joinToString { n -> DigitNumberCorrection.analyze(w,h,px,"8".repeat(n)).toString() })
                            if(CaptureNumberCorrection.verifiedZero(crop,if(holding1119) 3 else null)) zeros.add(anchor.text)
                            val roi=android.graphics.RectF(x.toFloat()/image.width,y.toFloat()/image.height,(x+w).toFloat()/image.width,(y+h).toFloat()/image.height)
                            LcdOcrPipeline.run(crop,engine,selected=true,accept={ text,cw,ch,local,pass ->
                                read(text,cw,ch,android.graphics.RectF(roi.left+local.left*roi.width(),roi.top+local.top*roi.height(),
                                    roi.left+local.right*roi.width(),roi.top+local.bottom*roi.height()),"local-${anchor.text}-$pass")
                            },notice={})
                            File(targetContext.filesDir,"test-${anchor.text}.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                            File(targetContext.filesDir,"$name-${anchor.text}.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        } finally { enlarged.recycle(); crop.recycle() }
                    }
                    val pressure=JSONObject()
                    for(stage in 5 downTo 1) {
                        val key=(if(holding1119) "HP" else "BP")+stage; val counts=localCounts[key].orEmpty()
                        val remaining=allCandidates[key].orEmpty().filterNot { candidate -> sourceVerified[key].orEmpty().any { ZeroCandidateAgreement.supersedes(it,candidate) } }.toSet()
                        val fallback=remaining.singleOrNull()?.takeIf { it in sourceVerified[key].orEmpty() }
                        val value=if(holding1119 && key in zeros) "0" else MonoLocalAgreement.value(counts,sourceVerified[key].orEmpty()) ?: if(key in zeros) "0" else fallback
                        pressure.put(key,JSONObject().put("votes",JSONObject(counts)).put("value",value ?: JSONObject.NULL))
                    }
                    report.put(name,JSONObject().put("passes",passes).put("finalPressure",pressure)); image.recycle()
                    File(targetContext.filesDir,"mono-full-photo.json").writeText(report.toString(2))
                    if(holding1119) for(stage in 5 downTo 1) {
                        val expected=if(stage==1) "95" else "0"
                        val actual=pressure.getJSONObject("HP$stage").optString("value")
                        if(actual!=expected) holdingFailures.add("$name HP$stage expected=$expected actual=$actual")
                    }
                    if(name.startsWith("metering")) for(stage in 5 downTo 1) {
                        check(pressure.getJSONObject("BP$stage").optString("value")=="20") {
                            "$name BP$stage final selection differs from the source-photo truth: ${pressure.getJSONObject("BP$stage")}"
                        }
                    }
                    sendStatus(0,Bundle().apply { putString("stream","Full photo $name completed\n") })
                }
            }
            File(targetContext.filesDir,"mono-full-photo.json").writeText(report.toString(2))
            check(holdingFailures.isEmpty()) { holdingFailures.joinToString("\n") }
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","Saved mono-full-photo.json\n") })
        } catch(e:Throwable) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",e.stackTraceToString()) }) }
        finally { engine.close(); korean.close() }
    }
}
