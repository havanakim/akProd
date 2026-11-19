package com.bsd.akProd

/** Uses the unclosed source mask: a repaired middle bar is never evidence for an 8. */
object SevenSegmentVerifier {
    data class Digit(val position: Int,val left: Int,val top: Int,val right: Int,val bottom: Int,val value: Char?)
    data class Result(val text: String, val uncertain: Boolean, val checked: Boolean,
                      val digits: List<Digit> = emptyList(),val ink: BooleanArray = booleanArrayOf())

    fun verify(width: Int, height: Int, pixels: IntArray, recognized: String): Result {
        val raw=recognized.replace(Regex("\\s"), "")
        val fallback=Result(raw, raw.any { it in "089" }, false)
        if (!raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) || width<8 || height<12 ||
            pixels.size!=width*height || raw.none { it in "089" }) return fallback
        val gray=IntArray(pixels.size) { i -> val c=pixels[i]
            ((c shr 16 and 255)*299+(c shr 8 and 255)*587+(c and 255)*114)/1000 }
        val histogram=IntArray(256); gray.forEach { histogram[it]++ }
        val total=gray.sumOf { it.toDouble() }
        var n=0; var sum=0.0; var score=0.0; var threshold=127
        for (t in 0..254) {
            n+=histogram[t]; sum+=t.toDouble()*histogram[t]
            if (n==0 || n==gray.size) continue
            val delta=sum/n-(total-sum)/(gray.size-n)
            val candidate=n.toDouble()*(gray.size-n)*delta*delta
            if (candidate>score) { score=candidate; threshold=t }
        }
        if (score==0.0) return fallback
        val border=mutableListOf<Int>()
        for(x in 0 until width) { border.add(gray[x]); border.add(gray[(height-1)*width+x]) }
        for(y in 0 until height) { border.add(gray[y*width]); border.add(gray[y*width+width-1]) }
        val light=border.count { it>threshold }*2>=border.size
        val ink=BooleanArray(gray.size) { if(light) gray[it]<=threshold else gray[it]>threshold }
        val positions=raw.indices.filter { raw[it]!='.' }
        val digits=DigitGlyphSegmentation.split(width,height,ink,positions.map { raw[it] }) ?: return fallback
        val text=raw.toCharArray(); var uncertain=false; var checked=false
        val decisions=mutableListOf<Digit>()
        digits.forEachIndexed { index,b ->
            val position=positions[index]
            if(raw[position] !in "089") return@forEachIndexed
            fun record(value: Char?) {
                decisions.add(Digit(position,b.left,b.top,b.right,b.bottom,value))
                if(value==null) uncertain=true else { text[position]=value; checked=true }
            }
            val w=b.right-b.left; val h=b.bottom-b.top
            fun occupancy(l:Double,t:Double,r:Double,bt:Double):Double {
                var hit=0; var count=0
                for(y in (t*h).toInt() until (bt*h).toInt()) for(xx in (l*w).toInt() until (r*w).toInt()) {
                    count++; if(ink[(b.top+y)*width+b.left+xx]) hit++
                }
                return if(count==0) 0.0 else hit.toDouble()/count
            }
            // Five shared outer bars; lower-left presence distinguishes 8 from 9.
            val outer=listOf(occupancy(.25,0.0,.75,.16),occupancy(.25,.84,.75,1.0),
                occupancy(0.0,.18,.25,.42),occupancy(.75,.18,1.0,.42),
                occupancy(0.0,.58,.25,.82),occupancy(.75,.58,1.0,.82))
            if(w.toDouble()/h !in .3.. .95 || h<12 || outer.filterIndexed { i,_ -> i!=4 }.any { it<.32 } ||
                occupancy(.3,.22,.7,.35)>.18 || occupancy(.3,.65,.7,.78)>.18) {
                record(null); return@forEachIndexed
            }
            val middle=occupancy(.25,.44,.75,.56)
            when {
                outer[4]>=.32 && middle<.10 -> record('0')
                outer[4]>=.32 && middle>.65 -> record('8')
                outer[4]<.12 && middle>.65 -> record('9')
                else -> record(null)
            }
        }
        return Result(String(text),uncertain,checked,decisions,ink)
    }
}
