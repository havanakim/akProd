package kr.co.akpda.server
import kr.co.akpda.shared.*
import kotlin.test.*
import java.util.UUID
class WorkServiceTest {
    private val store=DemoStore()
    private val service=WorkService(store)
    private val p=service.login(LoginRequest("01","demo","demo"))
    private fun request(screen: String,op: String,fields: Row,id: String=UUID.randomUUID().toString()) = service.work(p,WorkRequest(screen,op,fields,id))
    @Test fun allSixWorkflowsReadAndSave() {
        request("PDA2001M1","search",mapOf("SCAN" to "B1"))
        request("PDA2001M1","save",mapOf("BOXES" to "B1,B2"))
        request("PDA2002M1","save",mapOf("SCAN" to "B3","RESOURCE_CD" to "T01","QTY" to "12.345"))
        request("PDA2003M1","save",mapOf("CHILD_ITEM_CD" to "RECYCLE01","QTY" to "1.25"))
        request("PDA2004M1","save",mapOf("CHILD_ITEM_CD" to "RECYCLE01","QTY" to "1.25"))
        request("PDA2005M1","save",mapOf("WC_CD" to "WC01","ORDER_DT" to "2026-11-10","PRODT_ORDER_NO" to "ORDER001","SCAN" to "L1"))
        request("PDA2006M1","save",mapOf("SCAN" to "L1","SCAN2" to "L2"))
        assertEquals(6,store.writeCount)
    }
    @Test fun duplicateWriteDoesNotRunAgainAndChangedPayloadFails() {
        val id=UUID.randomUUID().toString(); val fields=mapOf("SCAN" to "B3","RESOURCE_CD" to "T01","QTY" to "2.5")
        val first=request("PDA2002M1","save",fields,id)
        assertEquals(first,request("PDA2002M1","save",fields,id));assertEquals(1,store.writeCount)
        assertFailsWith<IllegalArgumentException> { request("PDA2002M1","save",fields+("QTY" to "3"),id) }
    }
    @Test fun insufficientInventoryAndNegativeQuantityDoNotWrite() {
        for(q in listOf("-1","0","101")) assertFailsWith<IllegalArgumentException> { request("PDA2002M1","save",mapOf("SCAN" to "B1","RESOURCE_CD" to "T01","QTY" to q)) }
        assertEquals(0,store.writeCount)
    }
    @Test fun cancellationUsesDefinedContracts() {
        request("PDA2001M1","cancel",mapOf("BOXES" to "B1","MODE" to "C"))
        request("PDA2002M1","cancel",mapOf("SCAN" to "B1","RESOURCE_CD" to "T01","MODE" to "C"))
        request("PDA2003M1","cancel",mapOf("CHILD_ITEM_CD" to "RECYCLE01","QTY" to "1.5","MODE" to "C"))
        request("PDA2006M1","cancel",mapOf("SCAN" to "L1","SCAN2" to "L2","MODE" to "C"))
        assertEquals(4,store.writeCount)
        assertFailsWith<IllegalArgumentException> { request("PDA2004M1","cancel",mapOf("CHILD_ITEM_CD" to "RECYCLE01","QTY" to "1","MODE" to "C")) }
        assertFailsWith<IllegalArgumentException> { request("PDA2005M1","cancel",emptyMap()) }
    }
    @Test fun permissionIsEnforcedByServer() {
        assertFailsWith<IllegalArgumentException> { service.work(p.copy(menus=emptySet()),WorkRequest("PDA2001M1","search",mapOf("SCAN" to "B"))) }
        assertFailsWith<IllegalArgumentException> { service.login(LoginRequest("01","demo","wrong")) }
    }
    @Test fun forgedResultColumnsCannotOverrideFreshLookup() {
        request("PDA2002M1","save",mapOf("SCAN" to "B3","RESOURCE_CD" to "T01","QTY" to "2.5","INV_QTY" to "999999","ITEM_CD" to "FORGED"))
        val row=request("PDA2002M1","search",mapOf("SCAN" to "B3","RESOURCE_CD" to "T01")).rows.single()
        assertEquals("RESIN01",row["ITEM_CD"]);assertEquals("98.0",row["INV_QTY"])
    }
    @Test fun invalidOrderAndDuplicateBoxDoNotWrite() {
        assertFailsWith<IllegalArgumentException> { request("PDA2005M1","save",mapOf("WC_CD" to "WC01","ORDER_DT" to "2026-11-10","PRODT_ORDER_NO" to "WRONG","SCAN" to "L")) }
        assertFailsWith<IllegalArgumentException> { request("PDA2001M1","save",mapOf("BOXES" to "B1,B1")) }
        assertEquals(0,store.writeCount)
    }
}
