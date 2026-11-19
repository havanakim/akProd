package com.bsd.akProd

/** OCR-only aliases. B is an ambiguous 8 candidate; source pixels must still verify any zero correction. */
object OcrNumericGlyphs {
    fun preserveColorDigits(raw:String,proposed:String):String {
        if(raw.length!=proposed.length) return raw
        return proposed.mapIndexed { i,c ->
            if(raw[i]=='0' && c in "89" || raw[i]=='8' && c=='0') raw[i] else c
        }.joinToString("")
    }
    fun canonical(text:String):String {
        val raw=text.replace(Regex("\\s"),"")
        return if(raw.matches(Regex("[0-9BaoO]+(?:\\.[0-9BaoO]+)?"))) raw.replace('B','8').replace('a','8').replace('O','0').replace('o','0') else raw
    }
}
