package kr.co.akpda.server

import java.sql.SQLException
import kotlin.test.*

class SqlDiagnosticsTest {
    @Test fun retainsSqlCodesAndChainedErrors() {
        val error = SQLException("Login failed", "28000", 18456)
        error.setNextException(SQLException("Cannot open database", "42000", 4060))
        val lines = SqlDiagnostics.details(error)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("errorCode=18456"))
        assertTrue(lines[0].contains("SQLState=28000"))
        assertTrue(lines[1].contains("errorCode=4060"))
    }

    @Test fun redactsConfiguredSecretsAndPasswordAssignments() {
        val secret = "sensitive-test-value"
        val error = SQLException("password={p;ss}; pwd='xyz'; credential=" + secret + "\nsecond line", "08001", 0)
        error.initCause(IllegalStateException("pwd=another-secret; " + secret))
        val lines = SqlDiagnostics.details(error, listOf(secret))
        val text = lines.joinToString(" ")
        assertFalse(text.contains(secret))
        assertFalse(text.contains("p;ss"))
        assertFalse(text.contains("xyz"))
        assertFalse(text.contains("another-secret"))
        assertTrue(text.contains("[REDACTED]"))
        assertTrue(lines.all { '\n' !in it && '\r' !in it })
    }

    @Test fun clientResponseContainsCodesButNoDatabaseDetails() {
        val error = SQLException("Private server address and database details", "28000", 18456)
        val message = SqlDiagnostics.clientMessage(error)
        assertTrue(message.contains("18456"))
        assertTrue(message.contains("28000"))
        assertFalse(message.contains("Private server"))
    }

    @Test fun handlesMissingAndInvalidSqlState() {
        assertEquals("n/a", SqlDiagnostics.state(SQLException("none")))
        assertEquals("n/a", SqlDiagnostics.state(SQLException("bad", "bad\nstate", 0)))
    }

    @Test fun stopsOnCircularNextExceptionChain() {
        val error = SQLException("cycle", "08001", 0)
        error.setNextException(error)
        assertEquals(1, SqlDiagnostics.details(error).size)
    }
}
