package com.bsd.akProd

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException

/** CP menus store confirmed values only. Existing photo records remain readable. */
internal object CaptureSaveRepository {
    enum class Target(val table: String, val machine: String) {
        MONO("CP_mono", Cp14TableReader.MACHINE), COLORED("CP_Colored", Cp3TableReader.MACHINE)
    }

    private data class Existing(
        val hash: String, val user: String, val machine: String,
        val json: String, val sameTime: Boolean, val photoCount: Int
    )

    fun save(
        target: Target, id: String, user: String, capturedAt: String, json: String,
        onStage: (String) -> Unit = {},
        connect: () -> Connection = { DBHelper.openConnection(directCapture = true) },
        trace: (String) -> Unit = {}
    ) {
        require(user.isNotBlank()) { "다시 로그인하세요." }
        require(id.isNotBlank() && capturedAt.isNotBlank() && json.isNotBlank()) { "등록 요청과 입력값을 확인하세요." }
        var lastStage = "저장 준비"
        var session = "미확인"
        fun stage(value: String) {
            lastStage = value; onStage("$value · DB 세션 $session")
            trace("request=$id table=${target.table} session=$session stage=$value")
        }

        val hash = digest(user, capturedAt, json)
        val table = "[dbo].[${target.table}]"
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
                        s.executeQuery("SELECT OBJECT_ID(N'dbo.${target.table}',N'U')").use { r ->
                            check(r.next() && r.getObject(1) != null) {
                                "${target.table} 테이블이 없습니다. 접속 DB와 CP 테이블 설치 스크립트를 확인하세요."
                            }
                        }
                    }
                    stage("동일 요청의 저장 결과를 확인하는 중")
                    val existing = c.prepareStatement(
                        """
                        SELECT PAYLOAD_SHA256,USER_ID,MACHINE_CODE,VALUES_JSON,
                               CASE WHEN CAPTURED_AT=CONVERT(datetime2,?,126) THEN 1 ELSE 0 END,PHOTO_COUNT
                        FROM $table WITH (UPDLOCK,HOLDLOCK) WHERE REQUEST_ID=?
                    """.trimIndent()
                    ).use { s ->
                        s.queryTimeout = 15; s.setString(1, capturedAt); s.setString(2, id)
                        s.executeQuery().use { r ->
                            if (r.next()) Existing(
                                r.getString(1), r.getString(2), r.getString(3),
                                r.getString(4), r.getInt(5) == 1, r.getInt(6)
                            ) else null
                        }
                    }
                    // MACHINE_CODE holds the 사출기 호기 (C01~C16) chosen on the registration screen, the same
                    // value as the JSON "MACHINE". A request drafted before the picker keeps the menu code.
                    val machineCode = machineNo(json) ?: target.machine
                    if (existing != null) {
                        // Old hashes included photographs. An unchanged legacy request can be confirmed without resending them.
                        require(
                            existing.user == user && existing.machine == machineCode && existing.json == json && existing.sameTime &&
                                    (existing.hash == hash || existing.photoCount > 0)
                        ) { "이미 등록된 요청의 내용이 다릅니다. 원래 입력으로 재확인하세요." }
                    } else {
                        stage("입력값을 저장하는 중")
                        val insertSql = """
                            INSERT INTO $table (REQUEST_ID,MACHINE_CODE,USER_ID,CAPTURED_AT,VALUES_JSON,PAYLOAD_SHA256,PHOTO_COUNT,REGISTERED_AT)
                            VALUES (?,?,?,CONVERT(datetime2,?,126),?,?,0,GETDATE())
                        """.trimIndent()
                        trace("request=$id INSERT template=" + insertSql.replace('\n', ' '))
                        trace("request=$id bind_count=6 json_chars=${json.length} json_utf8_bytes=${json.toByteArray(Charsets.UTF_8).size} values=redacted")
                        c.prepareStatement(insertSql).use { s ->
                            s.queryTimeout = 20; s.setString(1, id); s.setString(2, machineCode); s.setString(3, user)
                            s.setString(4, capturedAt); s.setString(5, json); s.setString(6, hash)
                            val started = System.nanoTime()
                            val rows = s.executeUpdate()
                            trace("request=$id INSERT rows=$rows elapsed_ms=${(System.nanoTime()-started)/1_000_000}")
                            check(rows == 1) { "입력값 등록 건수를 확인하세요." }
                        }
                    }
                    stage("저장 결과를 확정하는 중")
                    c.commit()
                    trace("request=$id COMMIT complete")
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
            val hint = if (e is SQLException && e.errorCode == 547)
                "\n사진 미저장 전환 스크립트(cp-values-only.sql) 적용 여부와 DB 제약조건을 확인하세요." else ""
            throw IllegalStateException(
                "$lastStage · DB 세션 $session · $detail: ${e.message}$hint",
                e
            )
        }
    }

    /**
     * Permanently deletes the user's own records in one transaction;
     * nothing is deleted if any statement fails. Returns how many records were removed.
     */
    fun delete(target: Target, user: String, ids: Collection<String>): Int {
        require(user.isNotBlank()) { "다시 로그인하세요." }
        require(ids.isNotEmpty() && ids.size <= 100) { "삭제할 자료를 1~100건 선택하세요." }
        val marks = ids.joinToString(",") { "?" }
        val table = "[dbo].[${target.table}]"
        return DBHelper.getConnection().use { c ->
            c.autoCommit = false
            try {
                fun bind(statement: java.sql.PreparedStatement) {
                    statement.queryTimeout = 30
                    statement.setString(1, user)
                    ids.forEachIndexed { i, id -> statement.setString(2 + i, id) }
                }
                // Records hold values only; the former *_PHOTO tables were dropped from the database.
                val removed = c.prepareStatement(
                    "DELETE FROM $table WHERE USER_ID=? AND REQUEST_ID IN ($marks)"
                ).use { bind(it); it.executeUpdate() }
                c.commit()
                removed
            } catch (e: Exception) {
                runCatching { c.rollback() }
                throw IllegalStateException("삭제 실패(변경 없음): ${e.message}", e)
            }
        }
    }

    /** The 사출기 호기 (C01~C16) written by the registration screens into the stored JSON, if any. */
    internal fun machineNo(json: String): String? =
        Regex("\"MACHINE\"\\s*:\\s*\"(C(0[1-9]|1[0-6]))\"").find(json)?.groupValues?.get(1)

    internal fun digest(user: String, capturedAt: String, json: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("CP_VALUES_ONLY_V1", user, capturedAt, json).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest()
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
