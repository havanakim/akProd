package com.bsd.akProd

import kotlin.math.abs

object SlashedZeroDetectorCheck {
    private const val W=36
    private const val H=56
    private fun glyph(kind: Char): IntArray = IntArray(W*H) { index ->
        val x=index%W-18.0; val y=index/W-28.0
        fun ellipse(cx: Double,cy: Double,rx: Double,ry: Double)=((x-cx)/rx)*((x-cx)/rx)+((y-cy)/ry)*((y-cy)/ry)<=1
        val ring=ellipse(0.0,0.0,13.0,24.0) && !ellipse(0.0,0.0,9.0,19.0)
        val ink=when(kind) {
            'Z' -> ring || (ellipse(0.0,0.0,13.0,24.0) && abs(x+y*.45)<1.8)
            '0' -> ring
            '8' -> (ellipse(0.0,-11.0,13.0,13.0) && !ellipse(0.0,-11.0,8.0,8.0)) ||
                (ellipse(0.0,11.0,13.0,13.0) && !ellipse(0.0,11.0,8.0,8.0))
            '9' -> (ellipse(0.0,-10.0,13.0,14.0) && !ellipse(0.0,-10.0,8.0,9.0)) || (x in 8.0..12.0 && y in -10.0..23.0)
            else -> false
        }
        if(ink) 0xff000000.toInt() else 0xffffffff.toInt()
    }
    @JvmStatic fun main(args: Array<String>) {
        val zero=glyph('Z')
        check(SlashedZeroDetector.correct(W,H,zero,"8")=="0") { "Slashed bitmap zero misread as 8" }
        check(SlashedZeroDetector.correct(W,H,zero,"9")=="0")
        check(SlashedZeroDetector.correct(W,H,glyph('8'),"8")==null) { "Genuine 8 must be preserved" }
        check(SlashedZeroDetector.correct(W,H,glyph('9'),"9")==null)
        check(SlashedZeroDetector.correct(W,H,glyph('0'),"8")==null) { "Unproven ordinary zero must not force a replacement" }
        val inverted=zero.map { if(it==0xff000000.toInt()) 0xffffffff.toInt() else 0xff000000.toInt() }.toIntArray()
        check(SlashedZeroDetector.correct(W,H,inverted,"9")=="0")
        check(SlashedZeroDetector.correct(W,H,zero,"89")==null) { "Segmentation mismatch must not be guessed" }
        check(SlashedZeroDetector.correct(W,H,zero,"0")==null)
        check(SlashedZeroDetector.correct(W,H,IntArray(W*H) { -1 },"8")==null)
        val decimal=IntArray((W*2+8)*H) { -1 }
        for(y in 0 until H) for(x in 0 until W) {
            decimal[y*(W*2+8)+x]=zero[y*W+x]
            decimal[y*(W*2+8)+W+8+x]=zero[y*W+x]
        }
        for(y in 48..51) for(x in W+2..W+5) decimal[y*(W*2+8)+x]=0xff000000.toInt()
        check(SlashedZeroDetector.correct(W*2+8,H,decimal,"8.9")=="0.0")
        val sheared=IntArray(60*H) { -1 }; val eight=glyph('8')
        for(y in 0 until H) for(x in 0 until W) {
            val shifted=x+12+((y-28)*.3).toInt()
            if(shifted in 0 until 60) sheared[y*60+shifted]=eight[y*W+x]
        }
        check(SlashedZeroDetector.correct(60,H,sheared,"8")==null) { "Tilted genuine 8 must be preserved" }
        println("Slashed-zero detector: 11 synthetic bitmap checks passed; genuine 8/9 retained")
    }
}
