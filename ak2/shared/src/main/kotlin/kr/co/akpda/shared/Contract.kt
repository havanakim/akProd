package kr.co.akpda.shared

import kotlinx.serialization.Serializable
import java.math.BigDecimal

typealias Row = Map<String, String>
@Serializable data class LoginRequest(val plant: String, val userId: String, val password: String)
@Serializable data class LoginResponse(val token: String, val displayName: String, val menus: List<String>, val demo: Boolean)
@Serializable data class WorkRequest(val screen: String, val operation: String, val fields: Map<String,String> = emptyMap(), val requestId: String = "")
@Serializable data class WorkResponse(val message: String = "", val rows: List<Row> = emptyList())
@Serializable data class ApiError(val message: String)
object Screens {
    val titles = linkedMapOf("PDA2001M1" to "특채입고", "PDA2002M1" to "수지 투입", "PDA2003M1" to "분쇄재 실적", "PDA2004M1" to "분쇄재 투입", "PDA2005M1" to "수검 실적", "PDA2006M1" to "외주 증지 체크", "Injection_1" to "Injection_1")
}
fun positive(value: String, label: String): BigDecimal {
    val n = value.toBigDecimalOrNull() ?: throw IllegalArgumentException(label + "을 숫자로 입력하세요.")
    require(n > BigDecimal.ZERO) { label + "은 0보다 커야 합니다." }
    require(n.precision() <= 18 && n.precision() - n.scale() <= 18 && n.scale().coerceAtLeast(0) <= 6) { label + "의 자릿수가 너무 큽니다." }
    return n
}
fun required(fields: Row, key: String): String = fields[key]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 }
    ?: throw IllegalArgumentException(key + " 값이 필요합니다.")
fun validateBoxes(rows: List<Row>) {
    require(rows.isNotEmpty()) { "BOX를 스캔하세요." }
    require(rows.size <= 100) { "한 번에 100개까지 처리할 수 있습니다." }
    require(rows.map { it["BX_ID"] }.distinct().size == rows.size) { "중복 BOX입니다." }
    require(rows.map { it["PO_NO"] }.distinct().size == 1) { "발주서가 서로 다릅니다." }
    require(rows.map { it["INVOICE_NUMBER"] }.distinct().size == 1) { "INVOICE가 서로 다릅니다." }
}
