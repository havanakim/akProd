package com.bsd.akProd

import kotlin.math.abs

object ZeroFontTemplatesCheck {
    private data class Image(val width: Int,val height: Int,val pixels: IntArray)
    private fun render(text: String,broken: Boolean=false): Image {
        val width=text.fold(8) { total,ch -> total+(if(ch=='.') 10 else 40) }; val height=60
        val image=IntArray(width*height) { -1 }; var offset=4
        text.forEach { digit ->
            for(y in 0 until 56) for(x in 0 until 36) {
                val dx=x-18.0; val dy=y-28.0
                fun ellipse(cx: Double,cy: Double,rx: Double,ry: Double)=((dx-cx)/rx)*((dx-cx)/rx)+((dy-cy)/ry)*((dy-cy)/ry)<=1
                val ring=ellipse(0.0,0.0,13.0,24.0) && !ellipse(0.0,0.0,9.0,19.0)
                val ink=when(digit) {
                    '0' -> (ring || (ellipse(0.0,0.0,13.0,24.0) && abs(dx+dy*.45)<1.8)) &&
                        !(broken && x<=9 && y in 26..28)
                    '1' -> x in 16..20 && y in 4..51
                    '5' -> (x in 6..29 && (y in 4..8 || y in 25..29 || y in 47..51)) ||
                        (x in 6..10 && y in 4..29) || (x in 25..29 && y in 25..51)
                    '8' -> (ellipse(0.0,-11.0,13.0,13.0) && !ellipse(0.0,-11.0,8.0,8.0)) ||
                        (ellipse(0.0,11.0,13.0,13.0) && !ellipse(0.0,11.0,8.0,8.0))
                    '9' -> (ellipse(0.0,-10.0,13.0,14.0) && !ellipse(0.0,-10.0,8.0,9.0)) || (dx in 8.0..12.0 && dy in -10.0..23.0)
                    '.' -> x in 2..5 && y in 48..51
                    else -> false
                }
                if(ink) image[(y+2)*width+offset+x]=0xff000000.toInt()
            }
            offset+=if(digit=='.') 10 else 40
        }
        return Image(width,height,image)
    }
    @JvmStatic fun main(args: Array<String>) {
        val teaching=render("010.0")
        val templates=ZeroFontTemplates.learn(teaching.width,teaching.height,teaching.pixels,"010.0")
        check(templates.isNotEmpty())
        check(ZeroFontTemplates.correct(teaching.width,teaching.height,teaching.pixels,"810.0",templates)=="010.0")
        val fifty=render("050.0")
        check(ZeroFontTemplates.correct(fifty.width,fifty.height,fifty.pixels,"850.8",templates)=="050.0")
        val allZero=render("000.0")
        check(ZeroFontTemplates.correct(allZero.width,allZero.height,allZero.pixels,"888.8",templates)=="000.0")
        val real=render("080.9")
        check(ZeroFontTemplates.correct(real.width,real.height,real.pixels,"080.9",templates)==null) { "Real 8 and 9 must not change" }
        check(ZeroFontTemplates.correct(fifty.width,fifty.height,fifty.pixels,"850.8",emptyList())==null)
        check(runCatching { ZeroFontTemplates.learn(teaching.width,teaching.height,teaching.pixels,"10.0") }.isFailure) { "Leading-zero count must match the selected image" }
        val broken=render("000.0",broken=true)
        val brokenTemplates=ZeroFontTemplates.learn(broken.width,broken.height,broken.pixels,"000.0")
        check(ZeroFontTemplates.correct(broken.width,broken.height,broken.pixels,"888.8",brokenTemplates)=="000.0")
        val inverted=fifty.pixels.map { if(it==-1) 0xff000000.toInt() else -1 }.toIntArray()
        check(ZeroFontTemplates.correct(fifty.width,fifty.height,inverted,"850.8",templates)=="050.0")
        check(runCatching { ZeroFontTemplates.learn(real.width,real.height,real.pixels,"899.9") }.isFailure)
        println("Zero-font templates: 10 synthetic checks passed (810.0 -> 010.0, 850.8 -> 050.0, 888.8 -> 000.0; real 8/9 preserved)")
    }
}
