package com.bsd.akProd

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.text.TextRecognizer
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/** Green stage rectangles bound the upper grid; lower controls never supply measurements. */
object ColoredHmiOcr {
    data class Result(val values:Map<String,String>,val regions:Map<String,RectF>,val details:String,val review:Set<String> = emptySet())
    fun headers(image:Bitmap):List<RectF> {
        if(!OpenCVLoader.initLocal()) return emptyList()
        val scale=minOf(1f,1400f/image.width,1800f/image.height)
        val small=Bitmap.createScaledBitmap(image,(image.width*scale).toInt(),(image.height*scale).toInt(),true)
        val w=small.width; val h=small.height; val px=IntArray(w*h); small.getPixels(px,0,w,0,0,w,h)
        val bytes=ByteArray(px.size) { i -> val c=px[i]; val r=c shr 16 and 255; val g=c shr 8 and 255; val b=c and 255
            if(g>60 && g>r*1.10 && g>b*1.08) 255.toByte() else 0 }
        val mask=Mat(h,w,CvType.CV_8UC1); mask.put(0,0,bytes)
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(5.0,3.0))
        val contours=mutableListOf<MatOfPoint>(); val hierarchy=Mat()
        try {
            Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel)
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            val boxes=contours.mapNotNull { c -> val r=Imgproc.boundingRect(c)
                if(r.width<w*.035 || r.width>w*.25 || r.height<h*.008 || r.height>h*.10 ||
                    r.width.toFloat()/r.height !in 1.2f..7f || Imgproc.contourArea(c)<r.area()*.38) null
                else RectF(r.x.toFloat()/w,r.y.toFloat()/h,(r.x+r.width).toFloat()/w,(r.y+r.height).toFloat()/h)
            }
            val bands=boxes.map { seed -> boxes.filter { abs(it.centerY()-seed.centerY())<maxOf(it.height(),seed.height()) } }
            val band=bands.filter { it.size>=3 }.sortedWith(compareByDescending<List<RectF>> { it.size }.thenBy { it.map { b -> b.top }.average() }).firstOrNull().orEmpty()
            val height=band.map { it.height() }.sorted().let { if(it.isEmpty()) 0f else it[it.size/2] }
            val filtered=band.filter { it.height()>height*.65f }
            val width=filtered.map { it.width() }.sorted().let { if(it.isEmpty()) 1f else it[it.size/2] }
            return filtered.flatMap { box ->
                val count=if(box.width()>width*1.65f) (box.width()/width+.25f).toInt().coerceIn(2,3) else 1
                (0 until count).map { i -> RectF(box.left+box.width()*i/count,box.top,box.left+box.width()*(i+1)/count,box.bottom) }
            }.sortedBy { it.centerX() }
        } finally { contours.forEach { it.release() }; hierarchy.release(); mask.release(); kernel.release(); if(small!==image) small.recycle() }
    }
    suspend fun read(image:Bitmap,page:String,korean:TextRecognizer,latin:TextRecognizer):Result {
        val started=android.os.SystemClock.elapsedRealtime()
        val boxes=headers(image); val log=StringBuilder("green headers=${boxes.size} $boxes\n")
        val labels=Cp3TableReader.labelsFor(page); val rows=Cp3TableReader.rowsFor(page).keys.toList()
        val values=linkedMapOf<String,String>(); val regions=linkedMapOf<String,RectF>()
        val review=linkedSetOf<String>()
        fun crop(r:RectF):Bitmap {
            val x=(r.left*image.width).toInt().coerceIn(0,image.width-1); val y=(r.top*image.height).toInt().coerceIn(0,image.height-1)
            val endX=(r.right*image.width).toInt().coerceIn(x+1,image.width); val endY=(r.bottom*image.height).toInt().coerceIn(y+1,image.height)
            return Bitmap.createBitmap(image,x,y,endX-x,endY-y)
        }
        val descriptors=boxes.map { box ->
            val header=crop(RectF((box.left-.005f).coerceAtLeast(0f),(box.top-.003f).coerceAtLeast(0f),(box.right+.005f).coerceAtMost(1f),(box.bottom+.003f).coerceAtMost(1f)))
            val title=try { LcdOcrPipeline.recognize(korean,header).text.replace(Regex("\\s"),"") } finally { header.recycle() }
            val normalizedTitle=title.replace("사품","사출").replace("차출","사출").replace(Regex("(계량|계랑|계령|게량)A"),"계량4").let { if(it.endsWith("림방지")) it+"1" else it }
            val match=Regex(if(page==Cp3TableReader.METERING) "(계량|계랑|계령|게량|흘림방지|홀림방지|을림방지|를림방지)([1-5])" else "(보압|사출)([1-5])").find(normalizedTitle)
            log.append("header=$title\n")
            box to match?.let { (when(it.groupValues[1]) { "보압" -> "HOLD_"; "사출" -> "INJECT_"; "계량","계랑","계령","게량" -> "METER_"; else -> "SUCKBACK_" })+it.groupValues[2] }
        }
        val expected=Cp3TableReader.stagesFor(page)
        val fullGrid=descriptors.size==expected.size && descriptors.withIndex().count { it.value.second==expected[it.index] }>=expected.size/2
        val offsets=descriptors.withIndex().mapNotNull { item -> item.value.second?.let { expected.indexOf(it).takeIf { i -> i>=0 }?.minus(item.index) } }.groupingBy { it }.eachCount()
        val offset=offsets.maxByOrNull { it.value }?.takeIf { it.value>=2 }?.key
        val rowModels=mutableMapOf<String,Float>()
        for(indexed in descriptors.withIndex().reversed()) {
            val (box,recognized)=indexed.value
            val stage=recognized ?: if(fullGrid) expected[indexed.index] else offset?.let { expected.getOrNull(indexed.index+it) } ?: continue
            if(stage !in Cp3TableReader.stagesFor(page)) continue
            val stageRows=rows.filter { "${stage}_$it" !in Cp3TableReader.disabledFor(page) }
            val count=stageRows.size
            val roi=RectF(box.left,box.bottom+.08f*box.height(),box.right,(box.bottom+box.height()*5.7f).coerceAtMost(1f))
            val rawSource=crop(roi); val source=LcdOcrPipeline.smoothDisplay(rawSource); val enlarged=Bitmap.createScaledBitmap(source,source.width*2,source.height*2,true)
            val votes=linkedMapOf<String,MutableMap<String,Int>>()
            val centers=mutableMapOf<Int,Float>()
            val verified=mutableMapOf<String,MutableSet<String>>()
            val uncertain=mutableMapOf<String,MutableSet<String>>()
            fun addVote(index:Int,raw:String,region:RectF) {
                val key="${stage}_${stageRows[index]}"
                if(key !in labels || key in Cp3TableReader.disabledFor(page)) return
                if(stageRows[index]=="POSITION" && !raw.contains('.')) return
                // This font's zero is dotted (two walls, centre dot); its own shape check may turn an OCR 8/9 into 0.
                val dotted=ColorZeroGlyph.correct(image,region.left,region.top,region.right,region.bottom,raw)
                val corrected=dotted ?: CaptureNumberCorrection.correct(image,region.left,region.top,region.right,region.bottom,raw)
                // Do not turn an OCR zero into an eight or nine because of panel moire.
                val safe=dotted ?: corrected?.let { OcrNumericGlyphs.preserveColorDigits(raw,it) }
                if(dotted!=null) log.append("$key dotted-zero $raw->$dotted\n")
                val numeric=Cp3TableReader.number(safe ?: raw) ?: return
                if(safe!=null && safe!=raw) verified.getOrPut(key) { linkedSetOf() }.add(numeric)
                if(raw.any { it in "89" } && (corrected!=null || CaptureNumberCorrection.needsSegmentReview(image,region.left,region.top,region.right,region.bottom,raw))) uncertain.getOrPut(key) { linkedSetOf() }.add(numeric)
                val tally=votes.getOrPut(key) { linkedMapOf() }; tally[numeric]=(tally[numeric] ?: 0)+1
                regions[key]=region
            }
            fun choice(key:String):String? {
                val tally=votes[key].orEmpty().filterKeys { candidate -> verified[key].orEmpty().none { ZeroCandidateAgreement.supersedes(it,candidate) } }
                val sorted=tally.entries.sortedByDescending { it.value }
                if(sorted.isEmpty()) return null
                // All readings are one number seen with 0 or 8/9 at some places (50.0 x4 vs 58.0 x4 under a crack).
                // Reading a real 8 as 0 is rare, so the all-zero variant wins when it was read at least twice and
                // no less often than any rival.
                val zeroVariant=sorted.firstOrNull { z -> sorted.all { it.key==z.key || ZeroCandidateAgreement.supersedes(z.key,it.key) } }
                if(zeroVariant!=null && sorted.size>1 && zeroVariant.value>=2 && sorted.all { it.value<=zeroVariant.value }) return zeroVariant.key
                // Readings that differ only where they say 0/8/9 (140 vs 148 vs 149: a crack or glare over the
                // 0) mean that glyph was not read reliably. A zero misread as 8/9 is the common error, so votes
                // may still pick the 0 there, but never an 8/9 against such a rival.
                if(sorted.drop(1).any { ZeroCandidateAgreement.eightOrNineAgainstZeroLike(sorted[0].key,it.key) }) return null
                return sorted[0].key.takeIf { (sorted.size==1 || sorted[0].value>sorted[1].value) && sorted[0].value>=2 }
            }
            fun accept(text:com.google.mlkit.vision.text.Text,w:Int,h:Int,local:RectF,pass:String) {
                val pieces=text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
                    val b=e.boundingBox ?: return@mapNotNull null
                    val raw=OcrNumericGlyphs.canonical(e.text.replace(',','.'))
                    if(!raw.matches(Regex("[0-9.]+"))) return@mapNotNull null
                    val region=RectF(roi.left+(local.left+b.left.toFloat()/w*local.width())*roi.width(),roi.top+(local.top+b.top.toFloat()/h*local.height())*roi.height(),roi.left+(local.left+b.right.toFloat()/w*local.width())*roi.width(),roi.top+(local.top+b.bottom.toFloat()/h*local.height())*roi.height())
                    raw to region
                }
                val lines=mutableListOf<MutableList<Pair<String,RectF>>>()
                pieces.sortedBy { it.second.centerY() }.forEach { piece ->
                    val line=lines.lastOrNull()
                    if(line!=null && abs(line.map { it.second.centerY() }.average()-piece.second.centerY())<piece.second.height()*.6f) line.add(piece)
                    else lines.add(mutableListOf(piece))
                }
                val tokens=lines.mapNotNull { line ->
                    val ordered=line.sortedBy { it.second.left }; val raw=ordered.joinToString("") { it.first }
                    val value=Cp3TableReader.number(raw) ?: return@mapNotNull null
                    Triple(raw,value,RectF(ordered.minOf { it.second.left },ordered.minOf { it.second.top },ordered.maxOf { it.second.right },ordered.maxOf { it.second.bottom }))
                }
                log.append("$stage $pass ${tokens.map { it.first }}\n")
                if(tokens.size==count) tokens.forEachIndexed { index,token ->
                    centers.putIfAbsent(index,token.third.centerY())
                    rowModels.putIfAbsent(stageRows[index],(token.third.centerY()-box.bottom)/box.height())
                }
                if(centers.size!=count) return
                val gap=centers.values.sorted().zipWithNext { a,b -> b-a }.sorted().let { it[it.size/2] }
                tokens.forEachIndexed { index,(raw,value,region) ->
                    val row=centers.minBy { abs(it.value-region.centerY()) }
                    if(abs(row.value-region.centerY())<gap*.4f) addVote(row.key,raw,region)
                }
            }
            try {
                stageRows.forEachIndexed { index,row -> rowModels[row]?.let { centers[index]=box.bottom+it*box.height() } }
                accept(LcdOcrPipeline.recognize(latin,enlarged),enlarged.width,enlarged.height,RectF(0f,0f,1f,1f),"original")
                LcdOcrPipeline.run(source,latin,selected=true,modes=2,targetHeight=700,
                    shouldContinue={ votes.size<count || votes.values.any { tally -> tally.values.maxOrNull().let { it==null || it<2 } } },accept=::accept,notice={ log.append(it) })
                if(centers.size==count) for(index in stageRows.indices) {
                    val key="${stage}_${stageRows[index]}"
                    val selected=choice(key)
                    if(selected!=null && selected.none { it in "89" }) continue
                    val gap=centers.values.sorted().zipWithNext { a,b -> b-a }.sorted().let { it[it.size/2] }
                    val cellRoi=RectF(box.left,(centers.getValue(index)-gap*.4f).coerceAtLeast(roi.top),box.right,(centers.getValue(index)+gap*.4f).coerceAtMost(roi.bottom))
                    val rawCell=crop(cellRoi); val cell=LcdOcrPipeline.smoothDisplay(rawCell); val large=Bitmap.createScaledBitmap(cell,cell.width*3,cell.height*3,true)
                    fun cellRead(text:com.google.mlkit.vision.text.Text,w:Int,h:Int,local:RectF,pass:String) {
                        val numericElements=text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.map { OcrNumericGlyphs.canonical(it.text.replace(',','.')) }.filter { Cp3TableReader.number(it)!=null }
                        val raw=OcrNumericGlyphs.canonical(text.text.replace(',','.')).let { if(Cp3TableReader.number(it)!=null) it else numericElements.singleOrNull() ?: return }
                        if(Cp3TableReader.number(raw)==null) return
                        // The glyph check needs the digits' own box, not the bordered cell.
                        val boxes=text.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                            .filter { Cp3TableReader.number(OcrNumericGlyphs.canonical(it.text.replace(',','.')))!=null }.mapNotNull { it.boundingBox }
                        val glyphs=if(boxes.isEmpty()) cellRoi else RectF(
                            cellRoi.left+(local.left+boxes.minOf { it.left }.toFloat()/w*local.width())*cellRoi.width(),
                            cellRoi.top+(local.top+boxes.minOf { it.top }.toFloat()/h*local.height())*cellRoi.height(),
                            cellRoi.left+(local.left+boxes.maxOf { it.right }.toFloat()/w*local.width())*cellRoi.width(),
                            cellRoi.top+(local.top+boxes.maxOf { it.bottom }.toFloat()/h*local.height())*cellRoi.height())
                        addVote(index,raw,glyphs); log.append("$key cell-$pass=$raw\n")
                    }
                    try {
                        cellRead(LcdOcrPipeline.recognize(latin,large),large.width,large.height,RectF(0f,0f,1f,1f),"original")
                        if(votes[key].orEmpty().keys.any { it.any { c -> c in "89" } }) {
                            val rawLarge=Bitmap.createScaledBitmap(rawCell,rawCell.width*3,rawCell.height*3,true)
                            try {
                                cellRead(LcdOcrPipeline.recognize(korean,rawLarge),rawLarge.width,rawLarge.height,RectF(0f,0f,1f,1f),"source-korean")
                                cellRead(LcdOcrPipeline.recognize(korean,large),large.width,large.height,RectF(0f,0f,1f,1f),"clean-korean")
                            } finally { rawLarge.recycle() }
                        }
                        LcdOcrPipeline.run(cell,latin,selected=true,modes=2,shouldContinue={ choice(key)==null },accept=::cellRead,notice={})
                        // No reading at all: a lone "0" that ML Kit skipped is accepted only from its verified shape.
                        val lone=votes[key].isNullOrEmpty() && stageRows[index] in setOf("PRESSURE","SPEED") && ColorZeroGlyph.loneZero(image,cellRoi)
                        if(votes[key].isNullOrEmpty()) log.append("$key no-reading lone=$lone\n")
                        if(lone) {
                            votes.getOrPut(key) { linkedMapOf() }["0"]=2
                            verified.getOrPut(key) { linkedSetOf() }.add("0"); regions[key]=cellRoi
                            log.append("$key lone-dotted-zero\n")
                        }
                    } finally { large.recycle(); cell.recycle(); if(rawCell!==cell) rawCell.recycle() }
                }
                stageRows.forEach { row -> val key="${stage}_$row"; choice(key)?.let { values[key]=it; if(it in uncertain[key].orEmpty() && it !in verified[key].orEmpty()) review.add(key) } }
            } finally { enlarged.recycle(); source.recycle(); if(rawSource!==source) rawSource.recycle() }
        }
        log.append("elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}\n")
        return Result(values,regions,log.toString(),review)
    }
}
