package com.bsd.akProd

import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import org.junit.Assert.*
import org.junit.Test

class CaptureSaveRepositoryTest {
    private val time="2026-11-17T09:00:00.000"
    private val payload="{\"MACHINE\":\"C07\",\"ROUND\":3,\"FIELDS\":{\"pressure\":\"10\"}}"

    private class Database {
        var failInsert=false
        var nextId=41L
        var existing: Long?=null
        var commits=0; var rollbacks=0; var inserts=0; var updates=0
        val queries=mutableListOf<String>()
        val binds=mutableListOf<Any?>()
        private fun result(values: List<Any>?): ResultSet {
            var visited=false
            return proxy(ResultSet::class.java) { method,args -> when(method) {
                "next" -> if(visited || values==null) false else { visited=true; true }
                "getString" -> values!![(args!![0] as Int)-1].toString()
                "getInt" -> values!![(args!![0] as Int)-1] as Int
                "getLong" -> values!![(args!![0] as Int)-1] as Long
                "getObject" -> values!![(args!![0] as Int)-1]
                else -> null
            } }
        }
        private fun statement(sql: String?=null): Statement {
            if(sql!=null) queries.add(sql)
            val type=if(sql==null) Statement::class.java else PreparedStatement::class.java
            return proxy(type) { method,args -> when(method) {
                "setBytes", "setBinaryStream", "setBlob" -> error("사진 전송 API를 호출하면 안 됩니다.")
                "setString", "setLong", "setInt" -> { binds.add(args!![1]); null }
                "setNull" -> { binds.add(null); null }
                "execute" -> false
                "executeUpdate" -> {
                    check(sql!!.startsWith("UPDATE ")) { "INSERT는 OUTPUT으로 새 REQUEST_ID를 받아야 합니다." }
                    updates++; 1
                }
                "executeQuery" -> {
                    val query=sql ?: args!![0] as String
                    if(sql==null) queries.add(query)
                    when {
                        query.contains("@@SPID") -> result(listOf(73))
                        query.contains("OBJECT_ID") -> result(listOf(123))
                        query.contains("UPDLOCK") -> result(existing?.let { listOf(it) })
                        query.contains("INSERT INTO") -> {
                            if(!query.contains("INSERT_AT")) throw SQLException("INSERT_AT cannot be NULL","23000",515)
                            if(failInsert) throw SQLException("The query has timed out.","HYT00",-2)
                            inserts++; result(listOf(++nextId))
                        }
                        else -> result(null)
                    }
                }
                else -> null
            } }
        }
        fun connection(): Connection = proxy(Connection::class.java) { method,args -> when(method) {
            "createStatement" -> statement()
            "prepareStatement" -> statement(args!![0] as String)
            "commit" -> { commits++; null }
            "rollback" -> { rollbacks++; null }
            else -> null
        } }
        companion object {
            @Suppress("UNCHECKED_CAST")
            fun <T> proxy(type: Class<T>,call: (String,Array<out Any?>?) -> Any?): T =
                Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { _,method,args -> call(method.name,args) } as T
        }
    }

    private val targets=mapOf(
        CaptureSaveRepository.Target.MONO to "JSON1",CaptureSaveRepository.Target.MONO_METERING to "JSON2",
        CaptureSaveRepository.Target.COLORED_HMI to "JSON1",CaptureSaveRepository.Target.COLORED to "JSON2")

    @Test fun firstSaveOfDayDeviceRoundInsertsIntoItsJsonColumn() {
        targets.forEach { (target,column) ->
            val db=Database()
            val id=CaptureSaveRepository.save(target,"device-uuid","user",time,payload,connect=db::connection)
            assertEquals(42L,id)
            assertEquals(1,db.inserts); assertEquals(0,db.updates); assertEquals(1,db.commits); assertEquals(0,db.rollbacks)
            val insert=db.queries.single { it.contains("INSERT INTO") }
            assertTrue(insert.contains("INSERT INTO [dbo].[TB_PC_CONDITION] (DEVICE_CODE,ROUND_NO,USER_ID,CAPTURED_AT,$column,PHOTO_COUNT,INSERT_AT)"))
            assertTrue(insert.contains("OUTPUT INSERTED.REQUEST_ID"))
            // REQUEST_ID comes from the sequence; the device UUID and payload hash are not stored.
            assertFalse(insert.contains("PAYLOAD_SHA256") || insert.contains("(REQUEST_ID"))
            // The same day/호기/차수 lookup found nothing, then the insert.
            assertEquals(listOf("C07",3,"2026-11-17","2026-11-17","C07",3,"user",time,payload),db.binds)
            assertFalse(db.queries.any { it.contains("_PHOTO") || it.contains(".WRITE") })
        }
    }

    @Test fun laterSaveUpdatesOnlyItsJsonColumnOfSameDayDeviceRoundRow() {
        targets.forEach { (target,column) ->
            val db=Database().apply { existing=7L }
            val id=CaptureSaveRepository.save(target,"id","other-user",time,payload,connect=db::connection)
            assertEquals(7L,id)
            assertEquals(0,db.inserts); assertEquals(1,db.updates); assertEquals(1,db.commits)
            val lookup=db.queries.single { it.contains("UPDLOCK") }
            assertTrue(lookup.contains("WHERE DEVICE_CODE=? AND ROUND_NO=?"))
            assertTrue(lookup.contains("CAPTURED_AT>=CONVERT(datetime2,?,126) AND CAPTURED_AT<DATEADD(day,1,CONVERT(datetime2,?,126))"))
            assertTrue(lookup.contains("ORDER BY REQUEST_ID DESC"))
            // Only the JSON column changes: not the user, capture time or insert time.
            assertEquals("UPDATE [dbo].[TB_PC_CONDITION] SET $column=? WHERE REQUEST_ID=?",db.queries.single { it.startsWith("UPDATE") })
            assertEquals(listOf("C07",3,"2026-11-17","2026-11-17",payload,7L),db.binds)
        }
    }

    @Test fun draftWithoutRoundStoresNullRound() {
        for(target in CaptureSaveRepository.Target.entries) {
            val db=Database().apply { existing=7L }
            CaptureSaveRepository.save(target,"id","user",time,"{\"MACHINE\":\"C02\"}",connect=db::connection)
            // Without a 차수 there is nothing to match, so the save inserts.
            assertEquals(listOf("C02",null,"user",time,"{\"MACHINE\":\"C02\"}"),db.binds)
            assertEquals(1,db.inserts); assertEquals(0,db.updates)
        }
    }

    @Test fun roundAndDeviceLabel() {
        assertEquals(12,CaptureSaveRepository.roundNo("{\"ROUND\" : 12,\"X\":1}"))
        assertNull(CaptureSaveRepository.roundNo("{\"ROUND\":0}"))
        assertNull(CaptureSaveRepository.roundNo("{\"ROUND\":null}"))
        assertEquals("C05 · 3차",CaptureSaveRepository.deviceLabel("C05",3))
        assertEquals("C05",CaptureSaveRepository.deviceLabel("C05",null))
        assertNull(CaptureSaveRepository.deviceLabel("CP14",2))
    }

    @Test fun lookupIsBySameDayDeviceAndRoundOnly() {
        val db=Database()
        CaptureSaveRepository.save(CaptureSaveRepository.Target.MONO,"id","user",time,payload,connect=db::connection)
        val lookup=db.queries.single { it.contains("UPDLOCK") }
        // No JSON or menu condition: any row of the same date, 호기 and 차수 is the one to update.
        assertFalse(lookup.contains("JSON"))
        assertEquals(Cp3TableReader.INJECTION,Cp3TableReader.pageOf(null))
    }

    @Test fun deletingOneMenuKeepsTheOtherMenuOfTheRow() {
        val colorInjection="(JSON1 IS NOT NULL AND COALESCE(JSON_VALUE(JSON1,'$.HMI'),'C')='C')"
        assertEquals(listOf(
            "UPDATE [dbo].[TB_PC_CONDITION] SET JSON1=NULL WHERE $colorInjection AND JSON2 IS NOT NULL",
            "DELETE FROM [dbo].[TB_PC_CONDITION] WHERE $colorInjection AND JSON2 IS NULL"
        ),CaptureSaveRepository.Part.INJECTION.deletion(CaptureSaveRepository.HMI_COLOR))
        val monoMetering="(JSON2 IS NOT NULL AND COALESCE(JSON_VALUE(JSON2,'$.HMI'),'M')='M')"
        assertEquals(listOf(
            "UPDATE [dbo].[TB_PC_CONDITION] SET JSON2=NULL WHERE $monoMetering AND JSON1 IS NOT NULL",
            "DELETE FROM [dbo].[TB_PC_CONDITION] WHERE $monoMetering AND JSON1 IS NULL"
        ),CaptureSaveRepository.Part.METERING.deletion(CaptureSaveRepository.HMI_MONO))
        assertEquals("JSON1 IS NOT NULL",CaptureSaveRepository.Part.INJECTION.rows)
        assertEquals("JSON2",CaptureSaveRepository.Part.METERING.column)
    }

    @Test fun historyKeepsMonoAndColorApartByHmi() {
        assertEquals("(JSON1 IS NOT NULL AND COALESCE(JSON_VALUE(JSON1,'$.HMI'),'C')='C')",
            CaptureSaveRepository.Part.INJECTION.rowsFor(CaptureSaveRepository.HMI_COLOR))
        assertEquals("(JSON2 IS NOT NULL AND COALESCE(JSON_VALUE(JSON2,'$.HMI'),'M')='M')",
            CaptureSaveRepository.Part.METERING.rowsFor(CaptureSaveRepository.HMI_MONO))
        assertThrows(IllegalArgumentException::class.java) { CaptureSaveRepository.Part.INJECTION.rowsFor("X' OR 1=1 --") }
    }

    @Test fun requestIdMustBeASequenceNumber() {
        assertEquals(17L,CaptureSaveRepository.requestId("17"))
        assertThrows(IllegalArgumentException::class.java) {
            CaptureSaveRepository.requestId("6f1c2a5e-0000-4000-8000-000000000000")
        }
    }

    @Test fun timeoutReportsFailingStageAndSession() {
        val db=Database().apply { failInsert=true }
        val error=assertThrows(IllegalStateException::class.java) {
            CaptureSaveRepository.save(CaptureSaveRepository.Target.COLORED,"id","user",time,payload,connect=db::connection)
        }
        assertTrue(error.message!!.contains("입력값을 저장하는 중"))
        assertTrue(error.message!!.contains("DB 세션 73"))
        assertTrue(error.message!!.contains("HYT00"))
        assertEquals(1,db.rollbacks); assertEquals(0,db.commits)
    }
}
