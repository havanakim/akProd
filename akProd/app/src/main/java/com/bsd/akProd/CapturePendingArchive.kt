package com.bsd.akProd

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Keep unresolved submissions separate from the new-registration form, scoped to the user. */
object CapturePendingArchive {
    data class Item(val key: String,val raw: String,val label: String)
    private fun prefix(userKey: String)="archive.$userKey."
    fun archiveActive(context: Context,store: String,userKey: String): Boolean {
        val prefs=context.getSharedPreferences(store,Context.MODE_PRIVATE)
        val raw=prefs.getString(userKey,null) ?: return false
        val id=runCatching { JSONObject(raw).getString("requestId") }.getOrElse { UUID.randomUUID().toString() }
        prefs.edit().putString(prefix(userKey)+id,raw).remove(userKey).apply()
        return true
    }
    fun items(context: Context,store: String,userKey: String): List<Item> =
        context.getSharedPreferences(store,Context.MODE_PRIVATE).all.entries
            .filter { it.key.startsWith(prefix(userKey)) && it.value is String }.map { entry ->
                val raw=entry.value as String
                val label=runCatching {
                    val data=JSONObject(raw)
                    data.optString("capturedAt","시각 미확인")+" · 사진 "+(data.optJSONArray("photos")?.length() ?: 0)+"장 · "+data.optString("requestId").take(8)
                }.getOrDefault("복원 확인 필요 · "+entry.key.takeLast(8))
                Item(entry.key,raw,label)
            }.sortedByDescending { it.label }
    fun activate(context: Context,store: String,userKey: String,item: Item) {
        archiveActive(context,store,userKey)
        context.getSharedPreferences(store,Context.MODE_PRIVATE).edit().putString(userKey,item.raw).apply()
    }
    fun removeSaved(context: Context,store: String,userKey: String,id: String) {
        context.getSharedPreferences(store,Context.MODE_PRIVATE).edit().remove(userKey).remove(prefix(userKey)+id).apply()
    }
}
