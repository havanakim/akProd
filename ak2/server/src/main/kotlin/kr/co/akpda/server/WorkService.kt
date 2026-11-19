package kr.co.akpda.server

import kr.co.akpda.shared.*
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

data class Principal(
    val plant: String,
    val userId: String,
    val name: String,
    val menus: Set<String>
)

class WorkService(private val store: Store, private val recycleCancel: String = "") {
    fun login(req: LoginRequest): Principal {
        require(req.plant.matches(Regex("[A-Za-z0-9_.-]{1,30}")) && req.userId.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "공장 코드와 사번을 확인하세요." }
        require(req.password.length in 1..200) { "비밀번호를 입력하세요." }
        if (store.demo) require(req.userId == "demo" && req.password == "demo") { "데모 계정은 demo / demo입니다." }
        val rows = store.call(
            "USP_PDA0001M1_S1",
            mapOf("@PLANT_CD" to req.plant, "@WORKER_ID" to req.userId)
        )
        val user =
            rows.firstOrNull { "PWD" in it } ?: throw IllegalArgumentException("로그인 정보를 확인하세요.")
        require(
            MessageDigest.isEqual(
                user.getValue("PWD").toByteArray(),
                req.password.toByteArray()
            )
        ) { "로그인 정보를 확인하세요." }
        return Principal(
            req.plant,
            req.userId,
            user["WORKER_NM"].orEmpty(),
            rows.mapNotNull { it["MENU_ID"] }.filter { it in Screens.titles }.toSet() + Injection.SCREEN
        )
    }

    fun work(p: Principal, req: WorkRequest): WorkResponse {
        require(req.screen in p.menus && req.screen in Screens.titles) { "업무 권한이 없습니다." }
        val injection = req.screen == Injection.SCREEN
        require(req.fields.size <= (if (injection) 40 else 20) && req.fields.all {
            it.key.length <= 40 && it.value.length <= (if (injection && it.key == "PHOTO") Injection.MAX_PHOTO else 2000)
        }) { "입력 크기 초과" }
        if (injection) {
            require(req.operation == "save") { "사출 화면은 등록만 지원합니다." }
            Injection.validate(req.fields)
        }
        require(
            req.operation in setOf(
                "options",
                "search",
                "secondary",
                "orders",
                "save",
                "cancel"
            )
        ) { "지원하지 않는 작업" }
        val write = req.operation in setOf("save", "cancel")
        val run = { perform(p, req) }
        return if (write) {
            UUID.fromString(req.requestId)
            val canonical = req.copy(fields = req.fields.toSortedMap(), requestId = "")
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(Json.encodeToString(WorkRequest.serializer(), canonical).toByteArray())
                .joinToString("") { "%02x".format(it) }
            store.once(p.plant + ":" + p.userId, req.requestId, hash, run)
        } else run()
    }

    private fun perform(p: Principal, r: WorkRequest): WorkResponse {
        if (r.screen == Injection.SCREEN) {
            val photo = java.util.Base64.getDecoder().decode(r.fields.getValue("PHOTO"))
            require(photo.size <= 1_000_000 && photo.size >= 4 && photo[0] == 0xff.toByte() && photo[1] == 0xd8.toByte()) { "JPEG 사진을 확인하세요." }
            store.saveInjection(p, r, photo)
            return WorkResponse(if (store.demo) "데모 처리 완료 · 실제 DB 미변경" else "Injection_1 등록 완료")
        }
        val f = r.fields
        fun get(key: String) = required(f, key)
        val cancel = r.operation == "cancel" || f["MODE"] == "C"
        val mode = if (cancel) "C" else "R"
        val base = mapOf("@PLANT_CD" to p.plant)
        fun read(proc: String, vararg args: Pair<String, String>) = store.call(proc, base + args)
        fun first(proc: String, vararg args: Pair<String, String>): Row =
            read(proc, *args).firstOrNull() ?: throw IllegalArgumentException("조회 결과가 없습니다.")

        fun save(proc: String, vararg args: Pair<String, String>): WorkResponse {
            store.call(proc, base + args, true)
            return WorkResponse(if (store.demo) "데모 처리 완료 · 실제 DB 미변경" else "정상 처리되었습니다.")
        }

        fun material() = first(
            "USP_PDA2002M1_S1",
            "@BX_ID" to get("SCAN"),
            "@RC_GUBUN" to mode,
            "@RESOURCE_CD" to get("RESOURCE_CD"),
            "@USER_ID" to p.userId,
            "@PGM_ID" to r.screen
        )

        fun child() = first(
            "USP_" + r.screen + if (cancel) "_S2" else "_S1",
            "@CHILD_ITEM_CD" to get("CHILD_ITEM_CD"),
            "@USER_ID" to p.userId,
            "@PGM_ID" to r.screen
        )

        fun inputLot() =
            first("USP_PDA2006M1_S1", "@INPUT_LOT" to get("SCAN"), "@WORK_GUBUN" to mode)

        fun prodtLot() = first("USP_PDA2006M1_S2", "@PRODT_LOT" to get("SCAN2"))
        if (r.operation == "options") {
            val rows = when (r.screen) {
                "PDA2002M1" -> {
                    require(get("RESOURCE_GB") in setOf("T", "R")); read(
                        "USP_PDA2002M1_C1",
                        "@RESOURCE_GB" to get("RESOURCE_GB")
                    )
                }

                "PDA2003M1", "PDA2004M1" -> read("USP_" + r.screen + "_C1", "@RC_GUBUN" to mode)
                "PDA2005M1" -> read("USP_PDA2005M1_C1")
                else -> emptyList()
            }
            return WorkResponse(rows = rows)
        }
        if (r.operation == "orders") {
            require(r.screen == "PDA2005M1")
            require(runCatching { LocalDate.parse(get("ORDER_DT")) }.isSuccess) { "작업지시 날짜 형식을 확인하세요." }
            return WorkResponse(
                rows = read(
                    "USP_PDA2005M1_S1",
                    "@WC_CD" to get("WC_CD"),
                    "@ORDER_DT" to get("ORDER_DT")
                )
            )
        }
        if (r.operation in setOf("search", "secondary")) {
            val row = when (r.screen) {
                "PDA2001M1" -> first(
                    "USP_PDA2001M1_S1",
                    "@BX_ID" to get("SCAN"),
                    "@CD_KIND" to if (cancel) "D" else "C"
                )

                "PDA2002M1" -> material()
                "PDA2003M1", "PDA2004M1" -> child()
                "PDA2005M1" -> first("USP_PDA2005M1_S2", "@SCAN_LOT" to get("SCAN"))
                "PDA2006M1" -> if (r.operation == "secondary") prodtLot() else inputLot()
                else -> error("미지원 화면")
            }
            return WorkResponse(rows = listOf(row.filterKeys { it !in setOf("PWD", "PASSWORD") }))
        }
        val userArgs = arrayOf("@USER_ID" to p.userId, "@PGM_ID" to r.screen)
        return when (r.screen) {
            "PDA2001M1" -> {
                val ids = get("BOXES").split(',').map { it.trim() }
                require(ids.size in 1..100 && ids.all { it.isNotEmpty() } && ids.distinct().size == ids.size) { "BOX 목록을 확인하세요." }
                val rows = ids.map {
                    first(
                        "USP_PDA2001M1_S1",
                        "@BX_ID" to it,
                        "@CD_KIND" to if (cancel) "D" else "C"
                    )
                }
                validateBoxes(rows)
                save(
                    "USP_PDA2001M1_I1",
                    "@BX_ID" to (ids.joinToString(",") + ","),
                    "@CD_KIND" to if (cancel) "D" else "C",
                    "@USER_ID" to p.userId
                )
            }

            "PDA2002M1" -> {
                val row = material()
                val args = arrayOf(
                    "@SL_CD" to "M1110",
                    "@RESOURCE_CD" to get("RESOURCE_CD"),
                    "@ITEM_CD" to required(row, "ITEM_CD"),
                    "@LOT_NO" to required(row, "BX_ID"),
                    "@USERID" to p.userId,
                    "@INPUT_UNIT" to required(row, "BASIC_UNIT")
                )
                if (cancel) save("USP_MATERIAL_INPUT_CANCEL_1", *args)
                else {
                    val qty = positive(get("QTY"), "투입량")
                    require(qty <= required(row, "INV_QTY").toBigDecimal()) { "투입량이 재고를 초과합니다." }
                    save("USP_MATERIAL_INPUT_1", *args, "@MOV_QTY" to qty.toPlainString())
                }
            }

            "PDA2003M1", "PDA2004M1" -> {
                if (cancel && r.screen == "PDA2004M1") require(recycleCancel.matches(Regex("USP_[A-Z0-9_]+"))) { "분쇄재 투입 취소 프로시저 계약 확인이 필요합니다." }
                val row = child();
                val qty = positive(get("QTY"), "처리량")
                val max = positive(required(row, "MAX_WEIGHT"), "최대량")
                require(qty <= max) { "처리량이 최대량을 초과합니다." }
                if (r.screen == "PDA2003M1") save(
                    if (cancel) "USP_PDA2003M1_D1" else "USP_PDA2003M1_I1",
                    "@CHILD_ITEM_CD" to get("CHILD_ITEM_CD"),
                    "@USE_WEIGHT" to qty.toPlainString(),
                    "@MAX_WEIGHT" to max.toPlainString(),
                    *userArgs
                )
                else save(
                    if (cancel) recycleCancel else "USP_PDA2004M1_I1",
                    "@SL_CD" to "M1110",
                    "@RESOURCE_CD" to required(row, "RESOURCE_CD"),
                    "@ITEM_CD" to required(row, "ITEM_CD"),
                    "@USE_WEIGHT" to qty.toPlainString(),
                    "@MAX_WEIGHT" to max.toPlainString(),
                    *userArgs
                )
            }

            "PDA2005M1" -> {
                require(!cancel) { "수검 실적 취소는 원본에 정의되어 있지 않습니다." }
                require(runCatching { LocalDate.parse(get("ORDER_DT")) }.isSuccess) { "작업지시 날짜 형식을 확인하세요." }
                val orders = read(
                    "USP_PDA2005M1_S1",
                    "@WC_CD" to get("WC_CD"),
                    "@ORDER_DT" to get("ORDER_DT")
                )
                require(orders.any { it["PRODT_ORDER_NO"] == get("PRODT_ORDER_NO") }) { "작업지시를 다시 선택하세요." }
                val lot = first("USP_PDA2005M1_S2", "@SCAN_LOT" to get("SCAN"))
                positive(required(lot, "LOT_QTY"), "LOT 수량")
                save(
                    "USP_PDA2005M1_I1",
                    "@PRODT_ORDER_NO" to get("PRODT_ORDER_NO"),
                    "@LOT_NO" to required(lot, "LOT_NO"),
                    *userArgs
                )
            }

            "PDA2006M1" -> {
                val input = inputLot();
                val prodt = prodtLot()
                require(
                    required(input, "INPUT_ITEM_CD") == required(
                        prodt,
                        "PRODT_ITEM_CD"
                    )
                ) { "투입 LOT와 생산 LOT의 품목이 다릅니다." }
                positive(required(input, "INPUT_QTY"), "투입 수량")
                if (!cancel) positive(required(prodt, "PRODT_QTY"), "생산 수량")
                save(
                    if (cancel) "USP_PDA2006M1_I2" else "USP_PDA2006M1_I1",
                    "@INPUT_LOT" to required(input, "INPUT_LOT_NO"),
                    "@INPUT_ITEM_CD" to required(input, "INPUT_ITEM_CD"),
                    "@INPUT_QTY" to required(input, "INPUT_QTY"),
                    "@PRODT_LOT" to required(prodt, "PRODT_LOT_NO"),
                    "@PRODT_ITEM_CD" to required(prodt, "PRODT_ITEM_CD"),
                    "@PRODT_QTY" to required(prodt, "PRODT_QTY"),
                    *userArgs
                )
            }

            else -> error("미지원 화면")
        }
    }
}
