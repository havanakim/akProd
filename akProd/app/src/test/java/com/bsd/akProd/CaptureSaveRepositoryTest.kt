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
    private val payload="{\"FIELDS\":{\"pressure\":\"10\"}}"

    private class Database {
        var row: List<Any>?=null
        var failInsert=false
        var commits=0; var rollbacks=0; var inserts=0
        val queries=mutableListOf<String>()
        private fun result(values: List<Any>?): ResultSet {
            var visited=false
            return proxy(ResultSet::class.java) { method,args -> when(method) {
                "next" -> if(visited || values==null) false else { visited=true; true }
                "getString" -> values!![(args!![0] as Int)-1].toString()
                "getInt" -> values!![(args!![0] as Int)-1] as Int
                "getObject" -> values!![(args!![0] as Int)-1]
                else -> null
            } }
        }
        private fun statement(sql: String?=null): Statement {
            if(sql!=null) queries.add(sql)
            val type=if(sql==null) Statement::class.java else PreparedStatement::class.java
            return proxy(type) { method,args -> when(method) {
                "setBytes", "setBinaryStream", "setBlob" -> error("사진 전송 API를 호출하면 안 됩니다.")
                "execute" -> false
                "executeQuery" -> {
                    val query=sql ?: args!![0] as String
                    if(sql==null) queries.add(query)
                    when {
                        query.contains("@@SPID") -> result(listOf(73))
                        query.contains("OBJECT_ID") -> result(listOf(123))
                        else -> result(row)
                    }
                }
                "executeUpdate" -> {
                    if(sql!!.contains("INSERT INTO") && !sql.contains("REGISTERED_AT"))
                        throw SQLException("REGISTERED_AT cannot be NULL","23000",515)
                    if(failInsert) throw SQLException("The query has timed out.","HYT00",-2)
                    inserts++; 1
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

    @Test fun bothMenusInsertOnlyValuesAndZeroPhotos() {
        CaptureSaveRepository.Target.entries.forEach { target ->
            val db=Database()
            CaptureSaveRepository.save(target,"id","user",time,payload,connect=db::connection)
            assertEquals(1,db.inserts); assertEquals(1,db.commits); assertEquals(0,db.rollbacks)
            assertTrue(db.queries.any { it.contains("INSERT INTO [dbo].[${target.table}]") &&
                it.contains("REGISTERED_AT") && it.contains("?,?,0,GETDATE())") })
            assertFalse(db.queries.any { it.contains("_PHOTO") || it.contains(".WRITE") })
        }
    }

    @Test fun unchangedRequestIsConfirmedWithoutInsert() {
        val db=Database().apply {
            row=listOf(CaptureSaveRepository.digest("user",time,payload),"user","CP3",payload,1,0)
        }
        CaptureSaveRepository.save(CaptureSaveRepository.Target.COLORED,"id","user",time,payload,connect=db::connection)
        assertEquals(0,db.inserts); assertEquals(1,db.commits)
    }

    @Test fun legacyPhotoRequestCanBeConfirmedWithoutPhotoFiles() {
        val db=Database().apply { row=listOf("old-photo-inclusive-hash","user","CP3",payload,1,2) }
        CaptureSaveRepository.save(CaptureSaveRepository.Target.COLORED,"id","user",time,payload,connect=db::connection)
        assertEquals(0,db.inserts); assertEquals(1,db.commits)
        assertFalse(db.queries.any { it.contains("_PHOTO") })
    }

    @Test fun changedOrOtherUsersRequestIsRejectedAndRolledBack() {
        for(row in listOf(
            listOf("old","other-user","CP3",payload,1,2),
            listOf("old","user","CP3","changed values",1,2),
            listOf("wrong-hash","user","CP3",payload,1,0),
            listOf("old","user","CP3",payload,0,2)
        )) {
            val db=Database().apply { this.row=row }
            assertThrows(IllegalStateException::class.java) {
                CaptureSaveRepository.save(CaptureSaveRepository.Target.COLORED,"id","user",time,payload,connect=db::connection)
            }
            assertEquals(0,db.inserts); assertEquals(0,db.commits); assertEquals(1,db.rollbacks)
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
