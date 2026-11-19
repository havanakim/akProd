package com.bsd.akProd

/** Missing measurements are null; numeric zero is never treated as missing. */
object CaptureFieldValues {
    fun nullableValues(keys: Set<String>,values: Map<String,String>,absent: Set<String> = emptySet(),
                       number: (String) -> String?): Map<String,String?> {
        require(values.keys.all { it in keys } && absent.all { it in keys }) { "지원하지 않는 항목입니다." }
        return keys.associateWith { key ->
            val raw=values[key].orEmpty().trim()
            require(key !in absent || raw.isBlank()) { "$key: 값과 표시 없음을 동시에 선택할 수 없습니다." }
            if(raw.isBlank()) null else {
                require(number(raw)!=null) { "$key 숫자 형식을 확인하세요." }
                raw // Preserve displayed precision and legacy retry payloads, including 000.0.
            }
        }
    }
}
