package com.bsd.akProd

/** Stores values only; photo reads support records saved by older app versions. */
object Cp3Repository {
    data class Summary(
        val requestId: String,
        val user: String,
        val capturedAt: String,
        val photoCount: Int,
        val machineNo: String? = null
    )

    data class Record(val summary: Summary, val json: String)

    fun save(
        requestId: String, user: String, capturedAt: String, json: String,
        onStage: (String) -> Unit = {}
    ) {
        CaptureSaveRepository.save(
            CaptureSaveRepository.Target.COLORED,
            requestId,
            user,
            capturedAt,
            json,
            onStage,
            trace = CaptureDbLog::write
        )
    }

    /** Permanently deletes the user's selected records; returns the number removed. */
    fun delete(user: String, ids: Collection<String>): Int =
        CaptureSaveRepository.delete(CaptureSaveRepository.Target.COLORED, user, ids)

    fun search(user: String, from: String, toExclusive: String, offset: Int, screenType: String = Cp3TableReader.INJECTION): List<Summary> {
        require(user.isNotBlank() && offset >= 0)
        require(screenType in setOf(Cp3TableReader.INJECTION,Cp3TableReader.METERING))
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement(
                """
                SELECT REQUEST_ID,USER_ID,CONVERT(varchar(23),CAPTURED_AT,121) AS LOCAL_CAPTURED_AT,PHOTO_COUNT,MACHINE_CODE
                FROM [dbo].[CP_Colored]
                WHERE USER_ID=? AND CAPTURED_AT>=CONVERT(datetime2,?,126) AND CAPTURED_AT<CONVERT(datetime2,?,126)
                AND COALESCE(NULLIF(JSON_VALUE(VALUES_JSON,'$.SCREEN_TYPE'),''),'INJECTION_HOLDING')=?
                ORDER BY CAPTURED_AT DESC,REQUEST_ID DESC OFFSET ? ROWS FETCH NEXT 21 ROWS ONLY
            """.trimIndent()
            ).use { statement ->
                // MACHINE_CODE now holds the 사출기 호기 (C01~C16); the table itself is the color menu.
                statement.queryTimeout = 30; statement.setString(1, user)
                statement.setString(2, from); statement.setString(3, toExclusive); statement.setString(4, screenType); statement.setInt(5, offset)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(
                            Summary(
                                rows.getString("REQUEST_ID"),
                                rows.getString("USER_ID"),
                                rows.getString("LOCAL_CAPTURED_AT"),
                                rows.getInt("PHOTO_COUNT"),
                                rows.getString("MACHINE_CODE").takeIf { it.matches(Regex("C[0-9]{2}")) }
                            )
                        )
                    }
                }
            }
        }
    }

    fun get(user: String, id: String): Record {
        require(user.isNotBlank())
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement(
                """
                SELECT REQUEST_ID,USER_ID,CONVERT(varchar(23),CAPTURED_AT,121) AS LOCAL_CAPTURED_AT,PHOTO_COUNT,VALUES_JSON
                FROM [dbo].[CP_Colored] WHERE REQUEST_ID=? AND USER_ID=?
            """.trimIndent()
            ).use { statement ->
                statement.queryTimeout = 30; statement.setString(1, id); statement.setString(2, user)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "내 저장자료에서 해당 건을 찾을 수 없습니다." }
                    Record(
                        Summary(
                            rows.getString("REQUEST_ID"),
                            rows.getString("USER_ID"),
                            rows.getString("LOCAL_CAPTURED_AT"),
                            rows.getInt("PHOTO_COUNT")
                        ), rows.getString("VALUES_JSON")
                    )
                }
            }
        }
    }

}



