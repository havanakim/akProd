package com.bsd.akProd

import java.sql.Connection
import java.sql.SQLException

/**
 * CP menus store confirmed values only, in one table [TABLE]. REQUEST_ID comes from the
 * dbo.SEQ_PC_CONDITION sequence. The column is chosen by menu:
 *
 * | menu                                   | column |
 * |----------------------------------------|--------|
 * | 사출/보압(mono) (CP_mono), 사출/보압(color), PB 사출/보압 | JSON1  |
 * | 계량/배압(m), 계량/흘림방지(c), PB 계량/배압               | JSON2  |
 *
 * Each save first looks for a row with the same CAPTURED_AT date, 호기 (DEVICE_CODE) and 차수 (ROUND_NO).
 * Only when there is none does it insert; otherwise it updates that row's JSON column only.
 * Mono and color are told apart by the 호기 itself, not by the JSON.
 */
internal object CaptureSaveRepository {
    const val TABLE = "TB_PC_CONDITION"
    /** JSON "HMI": the first item of every saved JSON, telling the color menus from the mono menus. */
    const val HMI_COLOR = "C"
    const val HMI_MONO = "M"
    /** PB 사출기 menus (PB 사출/보압 JSON1, PB 계량/배압 JSON2); their 호기 are P01~P16. */
    const val HMI_PB = "P"

    enum class Target(val jsonColumn: String, val machine: String) {
        MONO("JSON1", Cp14TableReader.MACHINE),
        MONO_METERING("JSON2", Cp14TableReader.MACHINE),
        PB("JSON1", "PB"),
        PB_METERING("JSON2", "PB"),
        COLORED_HMI("JSON1", Cp3TableReader.MACHINE),
        COLORED("JSON2", Cp3TableReader.MACHINE)
    }

    /** One menu's values inside a row (its JSON column), as the history screens list, read and delete them. */
    enum class Part(val column: String, private val other: String) {
        INJECTION("JSON1", "JSON2"), METERING("JSON2", "JSON1");

        /** Rows that hold this part's values. */
        val rows get() = "$column IS NOT NULL"

        /**
         * Rows that hold this part's values saved by the [hmi] menus ([HMI_COLOR] or [HMI_MONO]). JSON saved before
         * "HMI" existed has none and is listed by both.
         */
        fun rowsFor(hmi: String): String {
            require(hmi == HMI_COLOR || hmi == HMI_MONO || hmi == HMI_PB)
            // PB was added after "HMI" existed: older JSON without it is always CP, never PB.
            if (hmi == HMI_PB) return "($column IS NOT NULL AND JSON_VALUE($column,'$.HMI')='$hmi')"
            return "($column IS NOT NULL AND COALESCE(JSON_VALUE($column,'$.HMI'),'$hmi')='$hmi')"
        }

        /**
         * Statements that remove this part of the [hmi] menu: where the row also holds the other JSON column, only
         * this column is set to NULL; where this column is the row's only JSON, the row is deleted. The UPDATE runs
         * first, so a row never ends with both JSON1 and JSON2 NULL.
         */
        fun deletion(hmi: String) = rowsFor(hmi).let { mine ->
            listOf(
                "UPDATE [dbo].[$TABLE] SET $column=NULL WHERE $mine AND $other IS NOT NULL",
                "DELETE FROM [dbo].[$TABLE] WHERE $mine AND $other IS NULL"
            )
        }
    }

    /**
     * Saves the values and returns the REQUEST_ID of the row written: the existing row of the same day, 호기 and
     * 차수 whose [Target.jsonColumn] was updated, or a new row. [id] is the device-side request key, for tracing only.
     */
    fun save(
        target: Target, id: String, user: String, capturedAt: String, json: String,
        onStage: (String) -> Unit = {},
        connect: () -> Connection = { DBHelper.openConnection(directCapture = true) },
        trace: (String) -> Unit = {}
    ): Long {
        require(user.isNotBlank()) { "다시 로그인하세요." }
        require(capturedAt.isNotBlank() && json.isNotBlank()) { "등록 요청과 입력값을 확인하세요." }
        var lastStage = "저장 준비"
        var session = "미확인"
        fun stage(value: String) {
            lastStage = value; onStage("$value · DB 세션 $session")
            trace("request=$id table=$TABLE/${target.jsonColumn} session=$session stage=$value")
        }

        stage("DB 서버에 연결하는 중")
        try {
            connect().use { c ->
                c.autoCommit = false
                try {
                    stage("저장 테이블을 확인하는 중")
                    c.createStatement().use { s ->
                        s.queryTimeout = 10
                        s.execute("SET LOCK_TIMEOUT 10000")
                        s.execute("SET XACT_ABORT ON")
                        s.executeQuery("SELECT @@SPID")
                            .use { r -> if (r.next()) session = r.getInt(1).toString() }
                        s.executeQuery("SELECT OBJECT_ID(N'dbo.$TABLE',N'U')").use { r ->
                            check(r.next() && r.getObject(1) != null) {
                                "$TABLE 테이블이 없습니다. 접속 DB와 pc-condition-schema.sql 적용 여부를 확인하세요."
                            }
                        }
                    }
                    // DEVICE_CODE holds the 사출기 호기 (C01~C16) chosen on the registration screen, the same
                    // value as the JSON "MACHINE". A request drafted before the picker keeps the menu code.
                    // ROUND_NO is the 차수 (JSON "ROUND"); a request drafted before the round picker stores NULL.
                    val deviceCode = machineNo(json) ?: target.machine
                    val round = roundNo(json)
                    val existing = if (round != null) {
                        stage("같은 날·호기·차수 자료를 확인하는 중")
                        sameDayRow(c, deviceCode, round, capturedAt)
                    } else null
                    val savedId = if (existing != null) {
                        stage("기존 자료의 입력값을 갱신하는 중")
                        val updateSql = "UPDATE [dbo].[$TABLE] SET ${target.jsonColumn}=? WHERE REQUEST_ID=?"
                        trace("request=$id UPDATE template=$updateSql request_id=$existing json_chars=${json.length} values=redacted")
                        c.prepareStatement(updateSql).use { s ->
                            s.queryTimeout = 20; s.setString(1, json); s.setLong(2, existing)
                            val rows = s.executeUpdate()
                            trace("request=$id UPDATE rows=$rows")
                            check(rows == 1) { "입력값 갱신 건수를 확인하세요." }
                        }
                        existing
                    } else {
                        stage("입력값을 저장하는 중")
                        insert(c, target, id, deviceCode, round, user, capturedAt, json, trace)
                    }
                    stage("저장 결과를 확정하는 중")
                    c.commit()
                    trace("request=$id COMMIT complete request_id=$savedId updated=${existing != null}")
                    return savedId
                } catch (e: Throwable) {
                    // Expose the actual failure while driver rollback/close may still be waiting.
                    val detail =
                        if (e is SQLException) "SQL ${e.errorCode} / ${e.sqlState}" else e.javaClass.simpleName
                    onStage("$lastStage 실패 · DB 세션 $session · $detail: ${e.message} · 트랜잭션 정리 중")
                    trace("request=$id session=$session failure=$detail type=${e.javaClass.simpleName} stage=$lastStage")
                    runCatching { c.rollback() }; throw e
                }
            }
        } catch (e: Exception) {
            val detail =
                if (e is SQLException) "SQL ${e.errorCode} / ${e.sqlState}" else e.javaClass.simpleName
            throw IllegalStateException("$lastStage · DB 세션 $session · $detail: ${e.message}", e)
        }
    }

    /** The latest row with the same CAPTURED_AT date, 호기 and 차수, locked until commit; null if none. */
    private fun sameDayRow(c: Connection, deviceCode: String, round: Int, capturedAt: String): Long? {
        val day = capturedAt.take(10)
        return c.prepareStatement(
            """
            SELECT TOP 1 REQUEST_ID FROM [dbo].[$TABLE] WITH (UPDLOCK,HOLDLOCK)
            WHERE DEVICE_CODE=? AND ROUND_NO=?
              AND CAPTURED_AT>=CONVERT(datetime2,?,126) AND CAPTURED_AT<DATEADD(day,1,CONVERT(datetime2,?,126))
            ORDER BY REQUEST_ID DESC
            """.trimIndent()
        ).use { s ->
            s.queryTimeout = 15
            s.setString(1, deviceCode); s.setInt(2, round); s.setString(3, day); s.setString(4, day)
            s.executeQuery().use { r -> if (r.next()) r.getLong(1) else null }
        }
    }

    private fun insert(
        c: Connection, target: Target, id: String, deviceCode: String, round: Int?,
        user: String, capturedAt: String, json: String, trace: (String) -> Unit
    ): Long {
        val insertSql = """
            INSERT INTO [dbo].[$TABLE] (DEVICE_CODE,ROUND_NO,USER_ID,CAPTURED_AT,${target.jsonColumn},PHOTO_COUNT,INSERT_AT)
            OUTPUT INSERTED.REQUEST_ID
            VALUES (?,?,?,CONVERT(datetime2,?,126),?,0,GETDATE())
        """.trimIndent()
        trace("request=$id INSERT template=" + insertSql.replace('\n', ' '))
        trace("request=$id bind_count=5 json_chars=${json.length} json_utf8_bytes=${json.toByteArray(Charsets.UTF_8).size} values=redacted")
        return c.prepareStatement(insertSql).use { s ->
            s.queryTimeout = 20; s.setString(1, deviceCode)
            if (round == null) s.setNull(2, java.sql.Types.INTEGER) else s.setInt(2, round)
            s.setString(3, user); s.setString(4, capturedAt); s.setString(5, json)
            val started = System.nanoTime()
            s.executeQuery().use { r ->
                check(r.next()) { "입력값 등록 건수를 확인하세요." }
                r.getLong(1).also {
                    check(!r.next()) { "입력값 등록 건수를 확인하세요." }
                    trace("request=$id INSERT request_id=$it elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
                }
            }
        }
    }

    /**
     * Removes the [hmi] menu's [part] values from the user's own selected records in one transaction: a row that also holds
     * the other screen keeps it, otherwise the row is deleted. Nothing changes if any statement fails.
     * Returns how many records were changed.
     */
    fun delete(part: Part, hmi: String, user: String, ids: Collection<String>): Int {
        require(user.isNotBlank()) { "다시 로그인하세요." }
        require(ids.isNotEmpty() && ids.size <= 100) { "삭제할 자료를 1~100건 선택하세요." }
        val numbers = ids.map { requestId(it) }
        val marks = numbers.joinToString(",") { "?" }
        return DBHelper.getConnection().use { c ->
            c.autoCommit = false
            try {
                val removed = part.deletion(hmi).sumOf { sql ->
                    c.prepareStatement("$sql AND USER_ID=? AND REQUEST_ID IN ($marks)").use { s ->
                        s.queryTimeout = 30
                        s.setString(1, user)
                        numbers.forEachIndexed { i, n -> s.setLong(2 + i, n) }
                        s.executeUpdate()
                    }
                }
                c.commit()
                removed
            } catch (e: Exception) {
                runCatching { c.rollback() }
                throw IllegalStateException("삭제 실패(변경 없음): ${e.message}", e)
            }
        }
    }

    /** REQUEST_ID is a sequence number; an id from an older app version (UUID) is not a record. */
    internal fun requestId(id: String): Long =
        id.toLongOrNull() ?: throw IllegalArgumentException("내 저장자료에서 해당 건을 찾을 수 없습니다.")

    /** The 사출기 호기 (CP C01~C16, PB P01~P16) written by the registration screens into the stored JSON, if any. */
    internal fun machineNo(json: String): String? =
        Regex("\"MACHINE\"\\s*:\\s*\"([CP](0[1-9]|1[0-6]))\"").find(json)?.groupValues?.get(1)

    /** History list label such as "C05 · 3차"; null when the row predates the 호기 picker. */
    internal fun deviceLabel(deviceCode: String?, round: Number?): String? =
        deviceCode?.takeIf { it.matches(Regex("[CP][0-9]{2}")) }
            ?.let { code -> round?.let { "$code · ${it.toInt()}차" } ?: code }

    /** The 차수 (1, 2, 3 …) written by the registration screens into the stored JSON, if any. */
    internal fun roundNo(json: String): Int? =
        Regex("\"ROUND\"\\s*:\\s*([1-9][0-9]{0,2})(?![0-9])").find(json)?.groupValues?.get(1)?.toInt()
}
