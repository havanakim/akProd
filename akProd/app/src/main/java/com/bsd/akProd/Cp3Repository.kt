package com.bsd.akProd

/**
 * CP3 color records in TB_PC_CONDITION. 사출/보압 values are in JSON1, 계량·흘림방지
 * values in JSON2; one row can hold both screens for the same day, 호기 and 차수.
 */
object Cp3Repository {
    data class Summary(
        val requestId: String,
        val user: String,
        val capturedAt: String,
        val photoCount: Int,
        val machineNo: String? = null
    )

    data class Record(val summary: Summary, val json: String)

    private fun partOf(screenType: String): CaptureSaveRepository.Part {
        require(screenType in setOf(Cp3TableReader.INJECTION, Cp3TableReader.METERING))
        return if (screenType == Cp3TableReader.METERING) CaptureSaveRepository.Part.METERING
        else CaptureSaveRepository.Part.INJECTION
    }

    /**
     * [hmiColor] is the 사출/보압(color) screen (JSON1); 계량·흘림방지(color) is JSON2. A row of the same
     * day, 호기 and 차수 is updated in that column instead of inserting; returns the REQUEST_ID written.
     */
    fun save(
        hmiColor: Boolean,
        requestId: String, user: String, capturedAt: String, json: String,
        onStage: (String) -> Unit = {}
    ): Long = CaptureSaveRepository.save(
        if (hmiColor) CaptureSaveRepository.Target.COLORED_HMI else CaptureSaveRepository.Target.COLORED,
        requestId,
        user,
        capturedAt,
        json,
        onStage,
        trace = CaptureDbLog::write
    )

    /**
     * Removes [screenType]'s values from the user's selected records; a row that also holds the other
     * color screen keeps it. Returns the number of records changed.
     */
    fun delete(user: String, ids: Collection<String>, screenType: String): Int {
        val part = partOf(screenType)
        return CaptureSaveRepository.delete(part, CaptureSaveRepository.HMI_COLOR, user, ids)
    }

    fun search(user: String, from: String, toExclusive: String, offset: Int, screenType: String = Cp3TableReader.INJECTION): List<Summary> {
        require(user.isNotBlank() && offset >= 0)
        val part = partOf(screenType)
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement(
                """
                SELECT REQUEST_ID,USER_ID,CONVERT(varchar(23),CAPTURED_AT,121) AS LOCAL_CAPTURED_AT,PHOTO_COUNT,DEVICE_CODE,ROUND_NO
                FROM [dbo].[${CaptureSaveRepository.TABLE}]
                WHERE USER_ID=? AND ${part.rowsFor(CaptureSaveRepository.HMI_COLOR)} AND CAPTURED_AT>=CONVERT(datetime2,?,126) AND CAPTURED_AT<CONVERT(datetime2,?,126)
                ORDER BY CAPTURED_AT DESC,REQUEST_ID DESC OFFSET ? ROWS FETCH NEXT 21 ROWS ONLY
            """.trimIndent()
            ).use { statement ->
                // DEVICE_CODE holds the 사출기 호기 (C01~C16) and ROUND_NO its 차수.
                statement.queryTimeout = 30; statement.setString(1, user)
                statement.setString(2, from); statement.setString(3, toExclusive); statement.setInt(4, offset)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(
                            Summary(
                                rows.getString("REQUEST_ID"),
                                rows.getString("USER_ID"),
                                rows.getString("LOCAL_CAPTURED_AT"),
                                rows.getInt("PHOTO_COUNT"),
                                CaptureSaveRepository.deviceLabel(rows.getString("DEVICE_CODE"), rows.getObject("ROUND_NO") as? Number)
                            )
                        )
                    }
                }
            }
        }
    }

    fun get(user: String, id: String, screenType: String): Record {
        require(user.isNotBlank())
        val part = partOf(screenType)
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement(
                """
                SELECT REQUEST_ID,USER_ID,CONVERT(varchar(23),CAPTURED_AT,121) AS LOCAL_CAPTURED_AT,PHOTO_COUNT,${part.column} AS VALUES_JSON
                FROM [dbo].[${CaptureSaveRepository.TABLE}] WHERE REQUEST_ID=? AND USER_ID=? AND ${part.rowsFor(CaptureSaveRepository.HMI_COLOR)}
            """.trimIndent()
            ).use { statement ->
                statement.queryTimeout = 30; statement.setLong(1, CaptureSaveRepository.requestId(id)); statement.setString(2, user)
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
