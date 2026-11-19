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
}
