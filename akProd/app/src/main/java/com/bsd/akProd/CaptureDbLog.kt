package com.bsd.akProd

import android.util.Log

/** Debug diagnostics omit credentials, employee IDs and measurement payloads. */
object CaptureDbLog {
    fun write(message: String) {
        if (BuildConfig.DEBUG) Log.i("akProdDB", message)
    }
}
