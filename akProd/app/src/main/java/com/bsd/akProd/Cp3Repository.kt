package com.bsd.akProd

/** Stores values only; photo reads support records saved by older app versions. */
object Cp3Repository {
    data class Summary(
        val requestId: String,
        val user: String,
        val capturedAt: String,
        val photoCount: Int
    )

    data class Record(val summary: Summary, val json: String)
    data class StoredPhoto(val number: Int, val loadedAt: String, val jpeg: ByteArray)

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

    fun search(user: String, from: String, toExclusive: String, offset: Int): List<Summary> {
        require(user.isNotBlank() && offset >= 0)
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement(
                """
                SELECT REQUEST_ID,USER_ID,CONVERT(varchar(23),CAPTURED_AT,121) AS LOCAL_CAPTURED_AT,PHOTO_COUNT
                FROM [dbo].[CP_Colored]
                WHERE MACHINE_CODE=? AND USER_ID=? AND CAPTURED_AT>=CONVERT(datetime2,?,126) AND CAPTURED_AT<CONVERT(datetime2,?,126)
                ORDER BY CAPTURED_AT DESC,REQUEST_ID DESC OFFSET ? ROWS FETCH NEXT 21 ROWS ONLY
            """.trimIndent()
            ).use { statement ->
                statement.queryTimeout = 30; statement.setString(
                1,
                Cp3TableReader.MACHINE
            ); statement.setString(2, user)
                statement.setString(3, from); statement.setString(4, toExclusive); statement.setInt(
                5,
                offset
            )
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(
                            Summary(
                                rows.getString("REQUEST_ID"),
                                rows.getString("USER_ID"),
                                rows.getString("LOCAL_CAPTURED_AT"),
                                rows.getInt("PHOTO_COUNT")
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
                FROM [dbo].[CP_Colored] WHERE REQUEST_ID=? AND USER_ID=? AND MACHINE_CODE=?
            """.trimIndent()
            ).use { statement ->
                statement.queryTimeout = 30; statement.setString(1, id); statement.setString(
                2,
                user
            ); statement.setString(3, Cp3TableReader.MACHINE)
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

    fun photos(user: String, id: String): List<StoredPhoto> {
        require(user.isNotBlank())
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement(
                """
                SELECT P.PHOTO_NO,CONVERT(varchar(23),P.LOADED_AT,121) AS LOCAL_LOADED_AT,P.PHOTO_JPEG
                FROM [dbo].[CP_Colored_PHOTO] P
                INNER JOIN [dbo].[CP_Colored] C ON C.REQUEST_ID=P.REQUEST_ID
                WHERE C.REQUEST_ID=? AND C.USER_ID=? AND C.MACHINE_CODE=? ORDER BY P.PHOTO_NO
            """.trimIndent()
            ).use { statement ->
                statement.queryTimeout = 30; statement.setString(1, id); statement.setString(
                2,
                user
            ); statement.setString(3, Cp3TableReader.MACHINE)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(
                            StoredPhoto(
                                rows.getInt("PHOTO_NO"),
                                rows.getString("LOCAL_LOADED_AT"),
                                rows.getBytes("PHOTO_JPEG")
                            )
                        )
                    }
                }
            }
        }
    }
}



