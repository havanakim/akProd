package com.bsd.akProd

/** Standalone JVM checks for automatic monitor detection, independent of Android. */
object MonitorDetectorCheck {
    @JvmStatic fun main(args: Array<String>) {
        val w = 320; val h = 400
        fun photo(left: Int, top: Int, right: Int, bottom: Int): IntArray {
            val pixels = IntArray(w*h) { 0xffdddddd.toInt() }
            for (y in top until bottom) for (x in left until right) pixels[y*w+x] = 0xff181818.toInt()
            for (row in 0..9) for (y in top+8+row*5 until top+10+row*5) {
                for (x in left+20 until left+65) pixels[y*w+x] = 0xffd09932.toInt()
            }
            return pixels
        }
        val found = checkNotNull(MonitorDetector.detect(w,h,photo(55,180,265,310)))
        check(kotlin.math.abs(found.left - 55f/w) < .01)
        check(kotlin.math.abs(found.top - 180f/h) < .01)
        val moved = checkNotNull(MonitorDetector.detect(w,h,photo(15,40,225,170)))
        check(kotlin.math.abs(moved.left - 15f/w) < .01)
        check(MonitorDetector.detect(w,h,IntArray(w*h) { 0xffeeeeee.toInt() }) == null)
        check(MonitorDetector.detect(w,h,IntArray(w*h) { 0xff111111.toInt() }) == null)
        println("Monitor detection: 4 checks passed")
    }
}
