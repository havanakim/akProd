package kr.co.akpda.server

import java.sql.DriverManager
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap

internal object SqlDiagnostics {
    private val assignments = Regex("(?i)\\b(password|pwd)\\s*[:=]\\s*(?:\\{[^}]*\\}|\"[^\"]*\"|'[^']*'|[^;\\s,]+)")

    fun state(error: SQLException): String = error.sqlState
        ?.takeIf { it.matches(Regex("[A-Za-z0-9]{5}")) } ?: "n/a"

    fun clientMessage(error: SQLException): String =
        "DB 처리 오류 (errorCode=" + error.errorCode + ", SQLState=" + state(error) +
            "). 서버의 SQL 상세 로그를 확인하세요. 저장 요청은 동일 요청 번호로 재확인하세요."

    fun details(error: SQLException, secrets: List<String> = emptyList()): List<String> {
        val lines = mutableListOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: SQLException? = error
        var count = 0
        while (current != null && count < 8 && seen.add(current)) {
            val sql = current
            lines += "SQL[" + count + "] type=" + sql.javaClass.simpleName +
                " errorCode=" + sql.errorCode + " SQLState=" + state(sql) +
                " message=" + clean(sql.message, secrets)
            var cause = sql.cause
            var depth = 0
            while (cause != null && depth < 4 && seen.add(cause)) {
                lines += "  cause=" + cause.javaClass.simpleName + " message=" + clean(cause.message, secrets)
                cause = cause.cause
                depth++
            }
            current = sql.nextException
            count++
        }
        return lines
    }

    private fun clean(message: String?, secrets: List<String>): String {
        var safe = message.orEmpty()
        for (secret in secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            safe = safe.replace(secret, "[REDACTED]")
        }
        safe = assignments.replace(safe) { it.groupValues[1] + "=[REDACTED]" }
        return safe.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').take(1600)
    }
}

internal fun checkSqlConnection(url: String, user: String, password: String) {
    DriverManager.getConnection(url, user, password).use { connection ->
        connection.createStatement().use { statement ->
            statement.queryTimeout = 10
            statement.executeQuery("SELECT 1").use { result ->
                check(result.next() && result.getInt(1) == 1) { "DB 연결 확인 결과가 올바르지 않습니다." }
            }
        }
    }
}
