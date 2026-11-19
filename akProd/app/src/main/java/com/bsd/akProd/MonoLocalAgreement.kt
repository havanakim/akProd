package com.bsd.akProd

/** Prefer a unique agreement from enlarged source/CLAHE/Otsu reads over noisy whole-photo alternatives. */
object MonoLocalAgreement {
    fun value(counts: Map<String, Int>, sourceVerified: Set<String> = emptySet()): String? {
        val ranked = counts.entries.sortedByDescending { it.value }
        if (ranked.isEmpty() || ranked[0].value < 2) return null
        if (ranked.size > 1 && ranked[0].value <= ranked[1].value) return null
        val winner = ranked[0].key
        val corrections =
            sourceVerified.filter { it == winner || ZeroCandidateAgreement.supersedes(it, winner) }
        if (corrections.size == 1) return corrections.single()
        if (corrections.size > 1) return null
        if (counts.keys.any { ZeroCandidateAgreement.supersedes(it, winner) }) return null
        return winner
    }
}
