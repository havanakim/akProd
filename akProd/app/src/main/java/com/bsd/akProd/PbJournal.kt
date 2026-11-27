package com.bsd.akProd

/**
 * PB 공정관리일지 (ACN-A-0602-2-5) items for the PB menus. The header-coded HMI values (IF/IP/IV/HP/HT,
 * S/RV/BP) are read by OCR like the CP_mono screens; the rest of the journal (C/T, 사출부 온도, 중량, 비고 …)
 * has no printed header code to anchor on, so it is typed in. All of them are stored in the same "FIELDS".
 */
object PbJournal {
    data class Field(val key: String, val label: String, val numeric: Boolean = true, val ocr: Boolean = false)

    const val TEXT_MAX = 100

    /** Typed-in journal items of the PB 사출/보압 menu, by section. */
    private val manual = linkedMapOf(
        "기본 정보" to listOf(
            Field("MOLD_NO", "금형번호", numeric = false), Field("ITEM_NAME", "품명", numeric = false),
            Field("SPEC", "규격", numeric = false), Field("CAVITY", "캐비티수")
        ),
        "C/T (sec)" to listOf(Field("CT_INJECT", "사출"), Field("CT_COOL", "냉각"), Field("CYCLE_TIME", "Cycle Time")),
        "사출부 온도 (℃)" to listOf(
            Field("NH", "노즐(NH)"), Field("H4", "H4"), Field("H3", "H3"), Field("H2", "H2"), Field("H1", "H1")
        ),
        "기타 조건" to listOf(
            Field("HOLD_GAUGE", "실보압(게이지)"), Field("SCREW_RPM", "스크류 RPM"), Field("WATER_TEMP", "온수기(℃)"),
            Field("METER_TIME", "계량시간"), Field("HOPPER_DRYER", "호퍼드라이어"),
            Field("SHOT_WEIGHT", "제품+런너 중량(g)"), Field("PRODUCT_WEIGHT", "총제품 중량(g)")
        ),
        "비고" to listOf(
            Field("ROBOT_DWELL", "직교로봇 체류시간(sec)"), Field("OIL_TEMP", "유온온도(℃)"),
            Field("MATERIAL", "원료", numeric = false), Field("REMAIN_DIST", "잔량거리(mm)"),
            Field("HUMIDITY", "습도"), Field("ROOM_TEMP", "온도"), Field("NOTE", "특이사항", numeric = false)
        )
    )

    /** Typed-in items of [page]; the PB 계량/배압 screen has none. */
    fun manualFields(page: String): List<Field> =
        if (page == Cp14TableReader.PB_INJECTION) manual.values.flatten() else emptyList()

    /** Every section of [page] in journal order: the OCR rows first, then the typed-in journal items. */
    fun sections(page: String): LinkedHashMap<String, List<Field>> {
        val result = linkedMapOf<String, List<Field>>()
        if (page == Cp14TableReader.PB_INJECTION) result["기본 정보"] = manual.getValue("기본 정보")
        Cp14TableReader.labelsFor(page).entries.groupBy { Cp14TableReader.prefix(it.key) }
            .forEach { (prefix, entries) ->
                result[Cp14TableReader.stageName(prefix)] =
                    entries.map { Field(it.key, it.value.substringBefore(" · "), ocr = true) }
            }
        if (page == Cp14TableReader.PB_INJECTION) manual.forEach { (title, fields) -> result.putIfAbsent(title, fields) }
        return result
    }

    /** Checks the typed-in items and returns them as stored: blank is null, numbers keep their shown precision. */
    fun values(page: String, values: Map<String, String>): Map<String, String?> {
        val fields = manualFields(page)
        require(values.keys.all { key -> fields.any { it.key == key } }) { "다른 화면의 항목이 포함되어 있습니다." }
        return fields.associate { field ->
            val raw = values[field.key].orEmpty().trim()
            field.key to when {
                raw.isBlank() -> null
                field.numeric -> raw.also {
                    require(Cp14TableReader.number(it) != null) { "${field.label} 숫자 형식을 확인하세요." }
                }
                else -> raw.also { require(it.length <= TEXT_MAX) { "${field.label}은 ${TEXT_MAX}자 이하로 입력하세요." } }
            }
        }
    }
}
