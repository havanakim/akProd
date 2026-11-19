package com.bsd.akProd

import android.graphics.Bitmap
import android.content.Context

/** Operates on the source glyph pixels; never replace every textual 8/9 with zero. */
object CaptureNumberCorrection {
    private data class Recent(val image:java.lang.ref.WeakReference<Bitmap>,val key:String,val result:DigitNumberCorrection.Result)
    private val recent=ThreadLocal<Recent>()
    /** Remove stray header strokes above a yellow LCD number without changing its pixels. */
    fun trimLcdNumber(crop:Bitmap):Bitmap {
        val w=crop.width; val h=crop.height; val px=IntArray(w*h)
        crop.getPixels(px,0,w,0,0,w,h)
        val ink=BooleanArray(px.size) { i -> val c=px[i]; val r=c shr 16 and 255; val g=c shr 8 and 255; val b=c and 255
            r>110 && g>75 && r>b*1.4 && g>b*1.3 }
        val hits=IntArray(h) { y -> (0 until w).count { ink[y*w+it] } }
        val threshold=maxOf(3,(hits.max()*.18f).toInt())
        val bands=mutableListOf<IntRange>(); var start=-1; var last=-1
        for(y in 0 until h) if(hits[y]>=threshold) {
            if(start>=0 && y-last>maxOf(3,h/18)) { bands.add(start..last); start=-1 }
            if(start<0) start=y; last=y
        }
        if(start>=0) bands.add(start..last)
        val band=bands.maxByOrNull { range -> range.sumOf { hits[it] } } ?: return crop
        if(band.last-band.first<h*.2f) return crop
        val pad=maxOf(3,(band.last-band.first)/10)
        val top=(band.first-pad).coerceAtLeast(0); val bottom=(band.last+pad+1).coerceAtMost(h)
        return if(top==0 && bottom==h) crop else Bitmap.createBitmap(crop,0,top,w,bottom-top)
    }
    /** Return zero only when every visible glyph is positively verified as zero. */
    fun verifiedZero(crop: Bitmap,digits: Int?=null): Boolean {
        val clean=trimLcdNumber(crop)
        try {
            val pixels=IntArray(clean.width*clean.height)
            clean.getPixels(pixels,0,clean.width,0,0,clean.width,clean.height)
            return (digits?.let { it..it } ?: (1..6)).any { count ->
                val result=DigitNumberCorrection.analyzeStable(clean.width,clean.height,pixels,"8".repeat(count))
                result.text=="0".repeat(count) && result.uncertainPositions.isEmpty()
            }
        } finally { if(clean!==crop) clean.recycle() }
    }
    fun correct(image: Bitmap,left: Float,top: Float,right: Float,bottom: Float,raw: String,
                context: Context?=null,fontKey: String=""): String? {
        val numeric=OcrNumericGlyphs.canonical(raw)
        if(numeric.none { it=='0' || it=='8' || it=='9' }) return null
        val margin=maxOf(2,((bottom-top)*image.height*.12f).toInt())
        val x=(left*image.width-margin).toInt().coerceIn(0,image.width-1)
        val y=(top*image.height-margin).toInt().coerceIn(0,image.height-1)
        val endX=(right*image.width+margin).toInt().coerceIn(x+1,image.width)
        val endY=(bottom*image.height+margin).toInt().coerceIn(y+1,image.height)
        val w=endX-x; val h=endY-y
        val pixels=IntArray(w*h)
        image.getPixels(pixels,0,w,x,y,w,h)
        val learned=if(context==null) emptyList() else context.getSharedPreferences("capture_zero_fonts_v1",Context.MODE_PRIVATE)
            .getStringSet(fontKey,emptySet()).orEmpty().map { ZeroFontTemplates.Glyph(it) }
        val result=DigitNumberCorrection.analyzeStable(w,h,pixels,numeric,learned)
        recent.set(Recent(java.lang.ref.WeakReference(image),"$x,$y,$w,$h,$numeric",result))
        return result.text.takeIf { it!=numeric }
    }
    fun needsSegmentReview(image: Bitmap,left: Float,top: Float,right: Float,bottom: Float,raw: String): Boolean {
        val numeric=OcrNumericGlyphs.canonical(raw)
        if(numeric.none { it in "089" }) return false
        val margin=maxOf(2,((bottom-top)*image.height*.12f).toInt())
        val x=(left*image.width-margin).toInt().coerceIn(0,image.width-1)
        val y=(top*image.height-margin).toInt().coerceIn(0,image.height-1)
        val endX=(right*image.width+margin).toInt().coerceIn(x+1,image.width)
        val endY=(bottom*image.height+margin).toInt().coerceIn(y+1,image.height)
        val w=endX-x; val h=endY-y
        val cached=recent.get()
        if(cached?.image?.get()===image && cached.key=="$x,$y,$w,$h,$numeric") return cached.result.uncertainPositions.isNotEmpty()
        val pixels=IntArray(w*h)
        image.getPixels(pixels,0,w,x,y,w,h)
        return DigitNumberCorrection.analyzeStable(w,h,pixels,numeric).uncertainPositions.isNotEmpty()
    }
    fun learn(crop: Bitmap,expected: String,context: Context,fontKey: String): Int {
        val pixels=IntArray(crop.width*crop.height)
        crop.getPixels(pixels,0,crop.width,0,0,crop.width,crop.height)
        val samples=ZeroFontTemplates.learn(crop.width,crop.height,pixels,expected)
        val preferences=context.getSharedPreferences("capture_zero_fonts_v1",Context.MODE_PRIVATE)
        val merged=(preferences.getStringSet(fontKey,emptySet()).orEmpty().toList()+samples.map { it.bits }).distinct().takeLast(18).toSet()
        check(preferences.edit().putStringSet(fontKey,merged).commit()) { "0 글꼴 학습 정보를 저장하지 못했습니다." }
        return merged.size
    }
    fun clear(context: Context,fontKey: String) {
        context.getSharedPreferences("capture_zero_fonts_v1",Context.MODE_PRIVATE).edit().remove(fontKey).apply()
    }
}
