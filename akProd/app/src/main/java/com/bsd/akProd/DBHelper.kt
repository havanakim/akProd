package com.bsd.akProd

import java.sql.Connection
import java.sql.DriverManager

object DBHelper {
    fun openConnection(
        database: String = BuildConfig.DB_NAME,
        directCapture: Boolean = false
    ): Connection {
        Class.forName(BuildConfig.DB_CLASSES)
        DriverManager.setLoginTimeout(10)
        val appName = "akProd-Capture"
        val options = when {
            directCapture -> ";prepareSQL=2;packetSize=512;appName=$appName;progName=$appName;tcpNoDelay=true"
            else -> ""
        }
        if (directCapture) CaptureDbLog.write("connection prepareSQL=2(sp_executesql) packetSize=512")
        return DriverManager.getConnection(
            "jdbc:jtds:sqlserver://${BuildConfig.DB_IP}:${BuildConfig.DB_PORT}/$database;loginTimeout=10;socketTimeout=30$options",
            BuildConfig.DB_USER, BuildConfig.DB_PASSWORD
        )
    }

    fun getConnection(): Connection = openConnection()
}
