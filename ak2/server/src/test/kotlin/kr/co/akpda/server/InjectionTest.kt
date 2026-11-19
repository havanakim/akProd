package kr.co.akpda.server

import kr.co.akpda.shared.*
import java.util.UUID
import kotlin.test.*

class InjectionTest {
    private val store = DemoStore()
    private val service = WorkService(store)
    private val p = service.login(LoginRequest("01", "demo", "demo"))
    private val fields = mapOf("MACHINE" to "INJ01", "MODEL" to "SPE-250",
        "CAPTURED_AT" to "2026-11-13T01:00:00Z", "CONFIRMED" to "Y",
        "PHOTO" to "/9j/2Q==", "COUNT_RIGHT" to "528", "RPM" to "0", "MOVE_PV" to "---")
    private fun request(f: Row = fields, id: String = UUID.randomUUID().toString()) =
        WorkRequest(Injection.SCREEN, "save", f, id)
    @Test fun menuAndIdempotentRegistration() {
        assertTrue(Injection.SCREEN in p.menus)
        val r = request()
        assertEquals(service.work(p, r), service.work(p, r))
        assertEquals(1, store.writeCount)
        assertFailsWith<IllegalArgumentException> { service.work(p, r.copy(fields = fields + ("RPM" to "1"))) }
    }
    @Test fun invalidOrUnconfirmedDataCannotWrite() {
        for (f in listOf(fields + ("CONFIRMED" to "N"), fields + ("RPM" to "-1"),
            fields + ("RPM" to "1e3"), fields + ("MACHINE" to ""), fields + ("PHOTO" to "YWJjZA=="),
            fields + ("CAPTURED_AT" to "bad"), fields + ("UNEXPECTED" to "1"))) {
            assertFails { service.work(p, request(f)) }
        }
        assertEquals(0, store.writeCount)
    }
    @Test fun requiresPermissionAndMeasurement() {
        assertFailsWith<IllegalArgumentException> { service.work(p.copy(menus = emptySet()), request()) }
        assertFailsWith<IllegalArgumentException> { service.work(p, request(fields - setOf("RPM", "COUNT_RIGHT"))) }
        assertFailsWith<IllegalArgumentException> { service.work(p, request().copy(operation = "cancel")) }
    }
}
