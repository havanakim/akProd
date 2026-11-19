package com.bsd.akProd

import kotlin.math.abs

/** User-labelled zeros for one monitor font. Keeps character positions, including padded zeros and decimals. */
object ZeroFontTemplates {
    const val WIDTH=24
    const val HEIGHT=36
    data class Glyph(val bits: String)
    private data class Mask(val width: Int,val height: Int,val ink: BooleanArray)
    private fun mask(w: Int,h: Int,pixels: IntArray): Mask? {
        if(w<2 || h<8 || pixels.size!=w*h) return null
        val gray=IntArray(pixels.size) { i -> val c=pixels[i]; ((c shr 16 and 255)*299+(c shr 8 and 255)*587+(c and 255)*114)/1000 }
        val hist=IntArray(256); gray.forEach { hist[it]++ }
        val sum=gray.sum().toDouble(); var count=0; var part=0.0; var best=-1.0; var threshold=127
        for(t in 0..254) {
            count+=hist[t]; part+=t.toDouble()*hist[t]
            if(count==0 || count==gray.size) continue
            val difference=part/count-(sum-part)/(gray.size-count)
            val score=count.toDouble()*(gray.size-count)*difference*difference
            if(score>best) { best=score; threshold=t }
        }
        if(best<=0) return null
        val edge=(0 until w).flatMap { listOf(gray[it],gray[(h-1)*w+it]) }+
            (0 until h).flatMap { listOf(gray[it*w],gray[it*w+w-1]) }
        val light=edge.count { it>threshold }>=edge.size/2
        return Mask(w,h,BooleanArray(pixels.size) { if(light) gray[it]<=threshold else gray[it]>threshold })
    }
    private fun glyphs(w: Int,h: Int,pixels: IntArray,expectedDigits: Int): List<Glyph> {
        val mask=mask(w,h,pixels) ?: return emptyList()
        data class Box(val left: Int,val right: Int,val top: Int,val bottom: Int)
        val boxes=mutableListOf<Box>()
        var start=-1
        // Column projection tolerates disconnected strokes in low-resolution bitmap zeros.
        for(x in 0..w) {
            val hasInk=x<w && (0 until h).any { mask.ink[it*w+x] }
            if(hasInk && start<0) start=x
            if(!hasInk && start>=0) {
                val ys=(0 until h).filter { y -> (start until x).any { mask.ink[y*w+it] } }
                if(ys.isNotEmpty()) boxes.add(Box(start,x-1,ys.first(),ys.last()))
                start=-1
            }
        }
        val maxHeight=boxes.maxOfOrNull { it.bottom-it.top+1 } ?: return emptyList()
        val digits=boxes.filter { it.bottom-it.top+1>=maxHeight*.60 && it.bottom-it.top>=7 }
        if(digits.size!=expectedDigits) return emptyList()
        return digits.map { box ->
            val bw=box.right-box.left+1; val bh=box.bottom-box.top+1
            val bits=StringBuilder(WIDTH*HEIGHT)
            for(y in 0 until HEIGHT) for(x in 0 until WIDTH) {
                val sx=box.left+((x+.5f)*bw/WIDTH).toInt().coerceAtMost(bw-1)
                val sy=box.top+((y+.5f)*bh/HEIGHT).toInt().coerceAtMost(bh-1)
                bits.append(if(mask.ink[sy*w+sx]) '1' else '0')
            }
            Glyph(bits.toString())
        }
    }
    fun learn(w: Int,h: Int,pixels: IntArray,expected: String): List<Glyph> {
        require(expected.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) && expected.contains('0')) { "앞자리 0까지 사진에 보이는 정답을 입력하세요. 예: 010.0" }
        val chars=expected.filter { it!='.' }
        val parts=glyphs(w,h,pixels,chars.length)
        require(parts.size==chars.length) { "숫자 테두리나 이웃 값이 들어가지 않도록 영역을 다시 선택하세요." }
        return parts.filterIndexed { index,_ -> chars[index]=='0' }.distinct()
    }
    private fun stackedHoles(bits: String): Boolean {
        data class Hole(val count: Int,val x: Double,val y: Double)
        val seen=BooleanArray(bits.length); val queue=IntArray(bits.length); val holes=mutableListOf<Hole>()
        for(seed in bits.indices) {
            if(seen[seed] || bits[seed]=='1') continue
            var head=0; var tail=1; queue[0]=seed; seen[seed]=true; var edge=false; var xSum=0.0; var ySum=0.0
            while(head<tail) {
                val index=queue[head++]; val x=index%WIDTH; val y=index/WIDTH
                xSum+=x; ySum+=y
                if(x==0 || y==0 || x==WIDTH-1 || y==HEIGHT-1) edge=true
                fun visit(nx: Int,ny: Int) {
                    if(nx !in 0 until WIDTH || ny !in 0 until HEIGHT) return
                    val next=ny*WIDTH+nx
                    if(!seen[next] && bits[next]=='0') { seen[next]=true; queue[tail++]=next }
                }
                visit(x-1,y); visit(x+1,y); visit(x,y-1); visit(x,y+1)
            }
            if(!edge && tail>=12) holes.add(Hole(tail,xSum/tail,ySum/tail))
        }
        if(holes.size!=2) return false
        return abs(holes[0].x-holes[1].x)<WIDTH*.12 && abs(holes[0].y-holes[1].y)>HEIGHT*.2
    }
    private fun distance(a: String,b: String): Double {
        var difference=0.0; var weight=0.0
        for(y in 0 until HEIGHT) for(x in 0 until WIDTH) {
            val importance=if(x in 4 until WIDTH-4 && y in 6 until HEIGHT-6) 3.0 else 1.0
            weight+=importance
            if(a[y*WIDTH+x]!=b[y*WIDTH+x]) difference+=importance
        }
        return difference/weight
    }
    fun correct(w: Int,h: Int,pixels: IntArray,rawText: String,templates: List<Glyph>): String? {
        val raw=rawText.replace(Regex("\\s"),"")
        if(templates.isEmpty() || !raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) || raw.none { it=='8' || it=='9' }) return null
        val positions=raw.indices.filter { raw[it]!='.' }
        val parts=glyphs(w,h,pixels,positions.size)
        if(parts.size!=positions.size) return null
        val output=raw.toCharArray(); var changed=false
        parts.forEachIndexed { index,glyph ->
            val position=positions[index]
            if(raw[position]!='8' && raw[position]!='9') return@forEachIndexed
            if(stackedHoles(glyph.bits)) return@forEachIndexed // Genuine 8 safeguard.
            val lowerLeft=(HEIGHT*6/10 until HEIGHT*85/100).count { y -> (0 until WIDTH*35/100).any { x -> glyph.bits[y*WIDTH+x]=='1' } }
            if(lowerLeft<(HEIGHT*85/100-HEIGHT*6/10)*.65) return@forEachIndexed // Genuine 9's open lower-left side.
            val match=templates.filter { it.bits.length==WIDTH*HEIGHT }.minOfOrNull { distance(glyph.bits,it.bits) } ?: return@forEachIndexed
            if(match<=.075) { output[position]='0'; changed=true }
        }
        return if(changed) String(output) else null
    }
}
