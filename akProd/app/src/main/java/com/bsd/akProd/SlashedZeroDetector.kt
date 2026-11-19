package com.bsd.akProd

import kotlin.math.abs

/** Conservative bitmap-glyph check: two diagonal counter-spaces, not the stacked holes of an 8. */
object SlashedZeroDetector {
    fun correct(width: Int,height: Int,pixels: IntArray,recognized: String): String? {
        val results=listOf(0.0,.10,.18,.24,.30,.36,.42,.48,.55,.62,.68,.74,.80,.86).mapNotNull { correctAt(width,height,pixels,recognized,it) }
        return results.groupingBy { it }.eachCount().entries.firstOrNull { it.value>=2 }?.key
    }
    private fun correctAt(width: Int,height: Int,pixels: IntArray,recognized: String,shrink: Double): String? {
        val raw=recognized.replace(Regex("\\s"),"")
        if(!raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) || raw.none { it=='8' || it=='9' }) return null
        if(width<5 || height<8 || pixels.size!=width*height) return null
        val gray=IntArray(pixels.size) { i -> val c=pixels[i]; ((c shr 16 and 255)*299+(c shr 8 and 255)*587+(c and 255)*114)/1000 }
        val histogram=IntArray(256)
        gray.forEach { histogram[it]++ }
        val total=gray.sum().toDouble()
        var darkCount=0; var darkSum=0.0; var threshold=127; var best=-1.0
        for(value in 0..254) {
            darkCount+=histogram[value]; darkSum+=value.toDouble()*histogram[value]
            val brightCount=gray.size-darkCount
            if(darkCount==0 || brightCount==0) continue
            val delta=darkSum/darkCount-(total-darkSum)/brightCount
            val score=darkCount.toDouble()*brightCount*delta*delta
            if(score>best) { best=score; threshold=value }
        }
        if(best<=0) return null
        val edge=mutableListOf<Int>()
        for(x in 0 until width) { edge.add(gray[x]); edge.add(gray[(height-1)*width+x]) }
        for(y in 0 until height) { edge.add(gray[y*width]); edge.add(gray[y*width+width-1]) }
        val lightBackground=edge.count { it>threshold }>=edge.size/2
        // Bright LCD halos close the diagonal counters at Otsu's threshold.
        // Requiring agreement at multiple source thresholds avoids treating a single noisy hole as evidence.
        threshold=if(lightBackground) (threshold-(threshold-gray.min())*shrink).toInt() else (threshold+(gray.max()-threshold)*shrink).toInt()
        val ink=BooleanArray(pixels.size) { if(lightBackground) gray[it]<=threshold else gray[it]>threshold }
        data class Glyph(val left: Int,val top: Int,val right: Int,val bottom: Int)
        val seen=BooleanArray(pixels.size); val queue=IntArray(pixels.size)
        val glyphs=mutableListOf<Glyph>()
        for(seed in ink.indices) {
            if(!ink[seed] || seen[seed]) continue
            var head=0; var tail=1; queue[0]=seed; seen[seed]=true
            var left=width; var top=height; var right=0; var bottom=0
            while(head<tail) {
                val index=queue[head++]; val x=index%width; val y=index/width
                left=minOf(left,x); right=maxOf(right,x); top=minOf(top,y); bottom=maxOf(bottom,y)
                for(dy in -1..1) for(dx in -1..1) {
                    val nx=x+dx; val ny=y+dy
                    if(nx !in 0 until width || ny !in 0 until height) continue
                    val next=ny*width+nx
                    if(ink[next] && !seen[next]) { seen[next]=true; queue[tail++]=next }
                }
            }
            if(bottom-top>=7 && right-left>=4) glyphs.add(Glyph(left,top,right,bottom))
        }
        val maxHeight=glyphs.maxOfOrNull { it.bottom-it.top+1 } ?: return null
        val positions=raw.indices.filter { raw[it]!='.' }
        val componentsDigits=glyphs.filter { it.bottom-it.top+1>=maxHeight*.65 }.sortedBy { it.left }
        val digits=if(componentsDigits.size==positions.size) componentsDigits else
            DigitGlyphSegmentation.split(width,height,ink,positions.map { raw[it] })
                ?.map { Glyph(it.left,it.top,it.right-1,it.bottom-1) } ?: return null
        if(digits.size!=positions.size) return null // Do not guess character segmentation or insert digits.
        val corrected=raw.toCharArray(); var changed=false
        digits.forEachIndexed { i,glyph ->
            val position=positions[i]
            if(raw[position]!='8' && raw[position]!='9') return@forEachIndexed
            val w=glyph.right-glyph.left+1; val h=glyph.bottom-glyph.top+1
            if(w.toFloat()/h !in .25f..1.15f) return@forEachIndexed
            data class Hole(val size: Int,val x: Double,val y: Double,val xy: Double,val yy: Double)
            val holes=mutableListOf<Hole>(); val visited=BooleanArray(w*h); val flood=IntArray(w*h)
            for(seed in visited.indices) {
                val sx=seed%w; val sy=seed/w
                if(visited[seed] || ink[(sy+glyph.top)*width+sx+glyph.left]) continue
                var head=0; var tail=1; flood[0]=seed; visited[seed]=true
                var edgeTouch=false; var sumX=0.0; var sumY=0.0; var sumXY=0.0; var sumYY=0.0
                while(head<tail) {
                    val index=flood[head++]; val x=index%w; val y=index/w
                    sumX+=x; sumY+=y; sumXY+=x*y; sumYY+=y*y
                    if(x==0 || y==0 || x==w-1 || y==h-1) edgeTouch=true
                    fun visit(nx: Int,ny: Int) {
                        if(nx !in 0 until w || ny !in 0 until h) return
                        val next=ny*w+nx
                        if(!visited[next] && !ink[(ny+glyph.top)*width+nx+glyph.left]) {
                            visited[next]=true; flood[tail++]=next
                        }
                    }
                    visit(x-1,y); visit(x+1,y); visit(x,y-1); visit(x,y+1)
                }
                if(!edgeTouch && tail>=maxOf(3,(w*h*.006).toInt())) holes.add(Hole(tail,sumX/tail,sumY/tail,sumXY/tail,sumYY/tail))
            }
            if(holes.size!=2) return@forEachIndexed
            val a=holes[0]; val b=holes[1]
            val ratio=a.size.toDouble()/b.size
            // Subtract the glyph's slant so a tilted genuine 8 is not mistaken for a slashed zero.
            val centers=mutableListOf<Pair<Double,Double>>()
            for(row in (h*.2).toInt()..(h*.8).toInt()) {
                val columns=(0 until w).filter { ink[(glyph.top+row)*width+glyph.left+it] }
                if(columns.isNotEmpty()) centers.add(row.toDouble() to (columns.first()+columns.last())/2.0)
            }
            val meanY=centers.map { it.first }.average(); val meanX=centers.map { it.second }.average()
            val variance=centers.sumOf { (it.first-meanY)*(it.first-meanY) }
            val slope=if(variance>0) centers.sumOf { (it.first-meanY)*(it.second-meanX) }/variance else 0.0
            val diagonalOffset=abs((a.x-slope*a.y)-(b.x-slope*b.y))
            val ca=(a.xy-a.x*a.y-slope*(a.yy-a.y*a.y))/(w*h)
            val cb=(b.xy-b.x*b.y-slope*(b.yy-b.y*b.y))/(w*h)
            // Thick bitmap zeros have small triangular counters with matching negative tilt.
            // Symmetric stacked counters of an 8 have near-zero residual covariance.
            if((diagonalOffset>=w*.18 || (ca<-.0015 && cb<-.0015 && diagonalOffset>w*.04)) && abs(a.y-b.y)>=h*.08 && ratio in .25..4.0) {
                corrected[position]='0'; changed=true
            }
        }
        return if(changed) String(corrected) else null
    }
}
