package kr.co.akpda.server

import kr.co.akpda.shared.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.*
import java.util.concurrent.ConcurrentHashMap

interface Store {
    val demo: Boolean
    fun call(proc: String, args: Row, write: Boolean = false): List<Row>
    fun once(owner: String, id: String, hash: String, block: () -> WorkResponse): WorkResponse
    fun saveInjection(p: Principal, request: WorkRequest, photo: ByteArray) {
        error("사출 데이터 저장이 구성되지 않았습니다.")
    }
}

@Serializable
data class WriteContract(
    val returnCode: Int,
    val messageParameter: String,
    val successMessages: List<String>,
    val successPrefix: String = ""
)

class JdbcStore(
    private val url: String,
    private val user: String,
    private val password: String,
    private val contracts: Map<String, WriteContract>
) : Store {
    override val demo = false
    private val active = ThreadLocal<Connection>()
    private fun connect() = DriverManager.getConnection(url, user, password)
    override fun saveInjection(p: Principal, request: WorkRequest, photo: ByteArray) {
        val c = active.get() ?: error("사출 등록 트랜잭션이 필요합니다.")
        val f = request.fields
        c.prepareStatement("INSERT dbo.AKPDA_INJECTION_CAPTURE(REQUEST_ID,PLANT_CD,MACHINE_CODE,MODEL_NAME,CAPTURED_AT,USER_ID,VALUES_JSON,PHOTO_JPEG) VALUES(?,?,?,?,?,?,?,?)").use { q ->
            q.setString(1, request.requestId); q.setString(2, p.plant); q.setNString(3, f.getValue("MACHINE"))
            q.setNString(4, f.getValue("MODEL")); q.setTimestamp(5, Timestamp.from(java.time.Instant.parse(f.getValue("CAPTURED_AT"))), java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")))
            q.setString(6, p.userId)
            q.setNString(7, Json.encodeToString(WorkRequest.serializer(), request.copy(fields = f - "PHOTO")))
            q.setBytes(8, photo); check(q.executeUpdate() == 1)
        }
    }
    override fun call(proc: String, args: Row, write: Boolean): List<Row> {
        require(proc.matches(Regex("USP_[A-Z0-9_]+")))
        val current = active.get()
        return if (current != null) execute(
            current,
            proc,
            args,
            write
        ) else connect().use { execute(it, proc, args, write) }
    }

    private fun execute(c: Connection, proc: String, args: Row, write: Boolean): List<Row> {
        val contract = if (write) contracts[proc] ?: error("쓰기 결과 계약 미설정: " + proc) else null

        data class Param(val name: String, val type: Int, val out: Boolean)

        val params =
            c.prepareStatement("SELECT p.name, t.name, p.is_output FROM sys.parameters p JOIN sys.types t ON p.user_type_id=t.user_type_id WHERE p.object_id=OBJECT_ID(?) AND p.parameter_id>0 ORDER BY p.parameter_id")
                .use { q ->
                    q.setString(1, "dbo." + proc)
                    q.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(
                                Param(
                                    rs.getString(1).uppercase(), when (rs.getString(2)) {
                                        "int" -> Types.INTEGER; "bigint" -> Types.BIGINT; "decimal", "numeric" -> Types.DECIMAL; "bit" -> Types.BIT; "nvarchar", "nchar" -> Types.NVARCHAR; else -> Types.VARCHAR
                                    }, rs.getBoolean(3)
                                )
                            )
                        }
                    }
                }
        require(params.isNotEmpty()) { "프로시저 파라미터를 확인할 수 없습니다: " + proc }
        require(args.keys.all { key -> params.any { it.name == key } }) { "프로시저 입력 계약이 다릅니다: " + proc }
        require(params.filterNot { it.out }
            .all { it.name in args }) { "필수 프로시저 입력이 누락되었습니다: " + proc }
        if (contract != null) require(params.any {
            it.out && it.name.equals(
                contract.messageParameter,
                true
            )
        }) { "출력 메시지 계약이 다릅니다: " + proc }
        val sql = "{? = call dbo." + proc + "(" + params.joinToString(",") { "?" } + ")}"
        return c.prepareCall(sql).use { st ->
            st.queryTimeout = 30
            st.registerOutParameter(1, Types.INTEGER)
            params.forEachIndexed { index, p ->
                val i = index + 2
                if (p.out) st.registerOutParameter(i, p.type)
                if (p.name in args) {
                    val v = args.getValue(p.name)
                    if (p.type == Types.DECIMAL) st.setBigDecimal(
                        i,
                        v.toBigDecimal()
                    ) else st.setObject(i, v, p.type)
                }
            }
            val tables = mutableListOf<Row>()
            var result = st.execute()
            while (true) {
                if (result) st.resultSet.use { rs ->
                    val md = rs.metaData
                    while (rs.next()) tables += (1..md.columnCount).associate {
                        md.getColumnLabel(it).uppercase() to (rs.getString(it) ?: "")
                    }
                } else if (st.updateCount == -1) break
                result = st.moreResults
            }
            val outputs = params.mapIndexedNotNull { i, p ->
                if (p.out) p.name to (st.getString(i + 2) ?: "") else null
            }.toMap()
            if (contract != null) {
                val msg = outputs[contract.messageParameter.uppercase()] ?: error("출力契約不一致")
                require(
                    st.getInt(1) == contract.returnCode && (msg in contract.successMessages || (contract.successPrefix.isNotEmpty() && msg.startsWith(
                        contract.successPrefix
                    )))
                ) { "DB 업무 처리 실패: " + msg }
            } else {
                val message = outputs.entries.firstOrNull {
                    it.key in setOf(
                        "@RTMSG",
                        "@RTN_MSG",
                        "@RETURN_MSG",
                        "@ERROR_MESSAGE"
                    )
                }?.value.orEmpty()
                check(message.isEmpty() || message.startsWith("성공")) { "DB 조회 실패: " + message }
            }
            tables
        }
    }

    override fun once(
        owner: String,
        id: String,
        hash: String,
        block: () -> WorkResponse
    ): WorkResponse = connect().use { c ->
        c.autoCommit = false
        c.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
        active.set(c)
        try {
            val saved =
                c.prepareStatement("SELECT PAYLOAD_HASH, RESPONSE_JSON FROM dbo.AKPDA_API_REQUEST WITH (UPDLOCK,HOLDLOCK) WHERE OWNER_KEY=? AND REQUEST_ID=?")
                    .use { q ->
                        q.setString(1, owner); q.setString(2, id)
                        q.executeQuery()
                            .use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
                    }
            val response = if (saved != null) {
                require(saved.first == hash) { "같은 요청 ID의 내용이 다릅니다." }
                Json.decodeFromString<WorkResponse>(saved.second)
            } else {
                val answer = block()
                c.prepareStatement("INSERT dbo.AKPDA_API_REQUEST(OWNER_KEY,REQUEST_ID,PAYLOAD_HASH,RESPONSE_JSON) VALUES(?,?,?,?)")
                    .use { q ->
                        q.setString(1, owner); q.setString(2, id); q.setString(
                        3,
                        hash
                    ); q.setNString(
                        4,
                        Json.encodeToString(WorkResponse.serializer(), answer)
                    ); q.executeUpdate()
                    }
                answer
            }
            c.commit(); response
        } catch (e: Exception) {
            c.rollback(); throw e
        } finally {
            active.remove()
        }
    }
}

class DemoStore : Store {
    override val demo = true
    private val saved = ConcurrentHashMap<String, Pair<String, WorkResponse>>()
    private val stock = mutableMapOf<String, java.math.BigDecimal>()
    var writeCount = 0; private set
    @Synchronized
    override fun saveInjection(p: Principal, request: WorkRequest, photo: ByteArray) { writeCount++ }
    @Synchronized
    override fun once(
        owner: String,
        id: String,
        hash: String,
        block: () -> WorkResponse
    ): WorkResponse {
        val key = owner + ":" + id
        saved[key]?.let { require(it.first == hash) { "요청 ID 충돌" }; return it.second }
        val copy = stock.toMap();
        val count = writeCount
        try {
            return block().also { saved[key] = hash to it }
        } catch (e: Exception) {
            stock.clear(); stock.putAll(copy); writeCount = count; throw e
        }
    }

    @Synchronized
    override fun call(proc: String, args: Row, write: Boolean): List<Row> {
        if (write) {
            writeCount++
            if (proc == "USP_MATERIAL_INPUT_1") {
                val lot = args.getValue("@LOT_NO");
                val left = stock[lot] ?: "100.5".toBigDecimal()
                val qty = args.getValue("@MOV_QTY")
                    .toBigDecimal(); require(qty <= left) { "재고 초과" }; stock[lot] = left - qty
            }
            return emptyList()
        }
        val box = args["@BX_ID"] ?: "BOX001"
        return when (proc) {
            "USP_PFW_NOTIFY_SEQ_S1" -> listOf(mapOf("NOTIFY_SEQ" to "0"))
            "USP_PFW_NOTIFY_PDA_S1" -> emptyList()
            "USP_PDA0001M1_S1" -> listOf(
                mapOf(
                    "PWD" to "demo",
                    "WORKER_NM" to "데모 작업자"
                )
            ) + Screens.titles.keys.map { mapOf("MENU_ID" to it) }

            "USP_PDA2001M1_S1" -> listOf(
                mapOf(
                    "BX_ID" to box,
                    "PO_NO" to "PO001",
                    "PO_SEQ_NO" to "1",
                    "INVOICE_NUMBER" to "INV001",
                    "INVOICE_SEQ" to "1",
                    "ITEM_CD" to "RESIN01",
                    "ITEM_NM" to "수지",
                    "PACKING_QTY" to "25.5",
                    "INV_QTY" to "25.5"
                )
            )

            "USP_PDA2002M1_C1" -> listOf(
                mapOf("CODE" to "T01", "NAME" to "1호 탱크"),
                mapOf("CODE" to "R01", "NAME" to "1호 설비")
            )

            "USP_PDA2003M1_C1", "USP_PDA2004M1_C1" -> listOf(
                mapOf(
                    "CODE" to "RECYCLE01",
                    "NAME" to "분쇄재 01"
                )
            )

            "USP_PDA2005M1_C1" -> listOf(mapOf("CODE" to "WC01", "NAME" to "수검 작업장"))
            "USP_PDA2002M1_S1" -> listOf(
                mapOf(
                    "BX_ID" to box,
                    "ITEM_CD" to "RESIN01",
                    "ITEM_NM" to "수지",
                    "BASIC_UNIT" to "KG",
                    "RCPT_QTY" to "100.5",
                    "INV_QTY" to (stock[box] ?: "100.5".toBigDecimal()).toPlainString(),
                    "USE_QTY" to "0",
                    "QTY" to "25.5"
                )
            )

            "USP_PDA2003M1_S1", "USP_PDA2003M1_S2", "USP_PDA2004M1_S1", "USP_PDA2004M1_S2" -> listOf(
                mapOf(
                    "ITEM_CD" to "RECYCLE01",
                    "ITEM_NM" to "분쇄재",
                    "MAX_WEIGHT" to "50.5",
                    "UNIT" to "KG",
                    "RESOURCE_CD" to "R01",
                    "RESOURCE_NM" to "1호 설비"
                )
            )

            "USP_PDA2005M1_S1" -> listOf(
                mapOf(
                    "PRODT_ORDER_NO" to "ORDER001",
                    "ITEM_NM" to "완제품",
                    "PRODT_ORDER_QTY" to "200"
                )
            )

            "USP_PDA2005M1_S2" -> listOf(
                mapOf(
                    "LOT_NO" to args.getValue("@SCAN_LOT"),
                    "LOT_QTY" to "20.5"
                )
            )

            "USP_PDA2006M1_S1" -> listOf(
                mapOf(
                    "INPUT_LOT_NO" to args.getValue("@INPUT_LOT"),
                    "INPUT_ITEM_CD" to "ITEM01",
                    "INPUT_ITEM_NM" to "외주품",
                    "INPUT_QTY" to "20.5"
                )
            )

            "USP_PDA2006M1_S2" -> listOf(
                mapOf(
                    "PRODT_LOT_NO" to args.getValue("@PRODT_LOT"),
                    "PRODT_ITEM_CD" to "ITEM01",
                    "PRODT_ITEM_NM" to "외주품",
                    "PRODT_QTY" to "20.5"
                )
            )

            else -> error("미지원 프로시저")
        }
    }
}
