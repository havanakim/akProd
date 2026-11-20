package com.bsd.akProd

/** A source-verified zero can supersede only an 8/9 at the same decimal place. */
object ZeroCandidateAgreement {
    fun supersedes(verified: String,candidate: String): Boolean {
        val a=verified.toBigDecimalOrNull() ?: return false
        val b=candidate.toBigDecimalOrNull() ?: return false
        val scale=maxOf(a.scale(),b.scale())
        val first=a.setScale(scale).toPlainString(); val second=b.setScale(scale).toPlainString()
        val length=maxOf(first.length,second.length)
        val x=first.padStart(length,'0'); val y=second.padStart(length,'0')
        if(x==y) return false
        return x.indices.all { x[it]==y[it] || x[it]=='0' && y[it] in "89" }
    }

    /**
     * True when [top] and [rival] differ only where both show 0, 8 or 9 (148 vs 140 or 149), and [top]
     * has an 8/9 at one of those places, i.e. choosing [top] would trust a doubtful 8/9.
     */
    fun eightOrNineAgainstZeroLike(top: String,rival: String): Boolean {
        val a=top.toBigDecimalOrNull() ?: return false
        val b=rival.toBigDecimalOrNull() ?: return false
        val scale=maxOf(a.scale(),b.scale())
        val p=a.setScale(scale).toPlainString(); val q=b.setScale(scale).toPlainString()
        val length=maxOf(p.length,q.length)
        val x=p.padStart(length,'0'); val y=q.padStart(length,'0')
        val differing=x.indices.filter { x[it]!=y[it] }
        return differing.isNotEmpty() && differing.all { x[it] in "089" && y[it] in "089" } && differing.any { x[it] in "89" }
    }
}
