package kr.co.akpda.shared

object Injection {
    const val SCREEN = "Injection_1"
    const val MAX_PHOTO = 1_400_000
    val labels = linkedMapOf(
        "COUNT_LEFT" to "생산량 · 왼쪽", "COUNT_RIGHT" to "생산량 · 오른쪽", "RPM" to "RPM"
    ).apply {
        listOf("PROCESS" to "공정 시간", "PAUSE" to "휴지 시간", "METERING" to "계량 시간",
            "INJECTION" to "사출 시간", "COOLING" to "냉각 시간").forEach { (key, title) ->
            put(key + "_LEFT", title + " · 왼쪽"); put(key + "_RIGHT", title + " · 오른쪽")
        }
        listOf("NOZZLE", "H4", "H3", "H2", "H1", "MOVE", "FIX", "OIL").forEach { zone ->
            put(zone + "_PV", zone + " PV"); put(zone + "_SV", zone + " SV")
        }
    }
    fun validate(fields: Row) {
        require(fields.keys.all { it in labels || it in setOf("MACHINE", "MODEL", "CAPTURED_AT", "PHOTO", "CONFIRMED", "ROBOT") }) { "지원하지 않는 사출 항목입니다." }
        require(required(fields, "MACHINE").length <= 80) { "설비번호는 80자 이하입니다." }
        require(required(fields, "MODEL").length <= 80)
        require(runCatching { java.time.Instant.parse(required(fields, "CAPTURED_AT")) }.isSuccess) { "촬영/불러오기 시각을 확인하세요." }
        require(fields["CONFIRMED"] == "Y") { "사진과 인식 결과를 확인하세요." }
        require(fields["ROBOT"].orEmpty() in setOf("", "ON", "OFF"))
        val photo = fields["PHOTO"].orEmpty()
        require(photo.length in 8..MAX_PHOTO && photo.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) { "사진을 촬영하거나 선택하세요." }
        require(labels.keys.any { !fields[it].isNullOrBlank() && fields[it] != "---" }) { "측정값을 하나 이상 입력하세요." }
        labels.forEach { (key, label) ->
            val value = fields[key].orEmpty()
            if (value.isNotBlank() && value != "---") {
                require(value.matches(Regex("[0-9]{1,9}(\\.[0-9]{1,4})?"))) { "$label 숫자 형식을 확인하세요." }
            }
        }
    }
}
