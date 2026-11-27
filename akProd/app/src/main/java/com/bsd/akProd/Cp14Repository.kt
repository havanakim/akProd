package com.bsd.akProd

/**
 * CP14 mono and PB records in TB_PC_CONDITION, by menu ([menu] is the menu's "screenType" extra):
 *
 * | menu                                   | column | "HMI" |
 * |----------------------------------------|--------|-------|
 * | CP 사출/보압(mono) ("", any CP_mono screen) | JSON1  | M     |
 * | CP 계량/배압(m) ([Cp14TableReader.METERING]) | JSON2  | M     |
 * | PB 사출/보압 ([Cp14TableReader.PB_INJECTION]) | JSON1  | P     |
 * | PB 계량/배압 ([Cp14TableReader.PB_METERING]) | JSON2  | P     |
 *
 * One row holds both columns for the same day, 호기 and 차수.
 */
object Cp14Repository {
    data class Summary(val requestId: String,val user: String,val capturedAt: String,val photoCount: Int,val screenType: String=Cp14TableReader.LEGACY,val machineNo: String?=null)
    data class Record(val summary: Summary,val json: String)

    private fun partOf(menu: String) =
        if (menu == Cp14TableReader.METERING || menu == Cp14TableReader.PB_METERING) CaptureSaveRepository.Part.METERING
        else CaptureSaveRepository.Part.INJECTION

    /** The JSON "HMI" of the [menu]: PB menus "P", CP_mono menus "M". */
    fun hmiOf(menu: String) =
        if (menu in Cp14TableReader.pbPages) CaptureSaveRepository.HMI_PB else CaptureSaveRepository.HMI_MONO

    private fun targetOf(menu: String) = when (menu) {
        Cp14TableReader.METERING -> CaptureSaveRepository.Target.MONO_METERING
        Cp14TableReader.PB_INJECTION -> CaptureSaveRepository.Target.PB
        Cp14TableReader.PB_METERING -> CaptureSaveRepository.Target.PB_METERING
        else -> CaptureSaveRepository.Target.MONO
    }

    /**
     * Saves into the [menu]'s column. A row of the same day, 호기 and 차수 is updated in that column;
     * otherwise a row is inserted. Returns the REQUEST_ID written.
     */
    fun save(menu: String,requestId: String,user: String,capturedAt: String,json: String,
             onStage: (String) -> Unit = {}): Long =
        CaptureSaveRepository.save(targetOf(menu),requestId,user,capturedAt,json,onStage,trace=CaptureDbLog::write)

    /** Removes the history's screen values from the user's selected records; returns the number changed. */
    fun delete(user: String,ids: Collection<String>,screenType: String): Int =
        CaptureSaveRepository.delete(partOf(screenType),hmiOf(screenType),user,ids)

    fun search(user: String,from: String,toExclusive: String,offset: Int,screenType: String=""): List<Summary> {
        require(user.isNotBlank() && offset>=0)
        val part=partOf(screenType)
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement("""
                SELECT REQUEST_ID,USER_ID,CONVERT(varchar(23),CAPTURED_AT,121) AS LOCAL_CAPTURED_AT,JSON_VALUE(${part.column},'$.SCREEN_TYPE') AS SCREEN_TYPE,PHOTO_COUNT,DEVICE_CODE,ROUND_NO
                FROM [dbo].[${CaptureSaveRepository.TABLE}]
                WHERE USER_ID=? AND ${part.rowsFor(hmiOf(screenType))} AND CAPTURED_AT>=CONVERT(datetime2,?,126) AND CAPTURED_AT<CONVERT(datetime2,?,126)
                ORDER BY CAPTURED_AT DESC,REQUEST_ID DESC OFFSET ? ROWS FETCH NEXT 21 ROWS ONLY
            """.trimIndent()).use { statement ->
                statement.queryTimeout=30; statement.setString(1,user)
                statement.setString(2,from); statement.setString(3,toExclusive); statement.setInt(4,offset)
                statement.executeQuery().use { rows -> buildList {
                    while(rows.next()) add(Summary(rows.getString("REQUEST_ID"),rows.getString("USER_ID"),rows.getString("LOCAL_CAPTURED_AT"),rows.getInt("PHOTO_COUNT"),rows.getString("SCREEN_TYPE") ?: Cp14TableReader.LEGACY,CaptureSaveRepository.deviceLabel(rows.getString("DEVICE_CODE"),rows.getObject("ROUND_NO") as? Number)))
                } }
            }
        }
    }
    fun get(user: String,id: String,screenType: String=""): Record {
        require(user.isNotBlank())
        val part=partOf(screenType)
        return (DBHelper.getConnection() ?: error("DB 연결 실패")).use { connection ->
            connection.prepareStatement("""
                SELECT REQUEST_ID,USER_ID,CONVERT(varchar(23),CAPTURED_AT,121) AS LOCAL_CAPTURED_AT,JSON_VALUE(${part.column},'$.SCREEN_TYPE') AS SCREEN_TYPE,PHOTO_COUNT,${part.column} AS VALUES_JSON
                FROM [dbo].[${CaptureSaveRepository.TABLE}] WHERE REQUEST_ID=? AND USER_ID=? AND ${part.rowsFor(hmiOf(screenType))}
            """.trimIndent()).use { statement ->
                statement.queryTimeout=30; statement.setLong(1,CaptureSaveRepository.requestId(id)); statement.setString(2,user)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "내 저장자료에서 해당 건을 찾을 수 없습니다." }
                    Record(Summary(rows.getString("REQUEST_ID"),rows.getString("USER_ID"),rows.getString("LOCAL_CAPTURED_AT"),rows.getInt("PHOTO_COUNT"),rows.getString("SCREEN_TYPE") ?: Cp14TableReader.LEGACY),rows.getString("VALUES_JSON"))
                }
            }
        }
    }
}
