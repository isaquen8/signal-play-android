package com.isaque.signalplay

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteStream(
    val name: String,
    val address: String,
    val protocol: String,
    val srtMode: String = "caller",
    val latencyMs: Int = 125,
    val deJitterMs: Int = 200
)

class FavoriteStreams(private val preferences: SharedPreferences) {
    fun save(item: FavoriteStream) {
        val items = (listOf(item) + get().filterNot { it.name.equals(item.name, true) }).take(50)
        val json = JSONArray()
        items.forEach { favorite ->
            json.put(JSONObject().apply {
                put("name", favorite.name)
                put("address", favorite.address)
                put("protocol", favorite.protocol)
                put("srtMode", favorite.srtMode)
                put("latencyMs", favorite.latencyMs)
                put("deJitterMs", favorite.deJitterMs)
            })
        }
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    fun get(): List<FavoriteStream> = runCatching {
        val json = JSONArray(preferences.getString(KEY, "[]"))
        buildList {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                add(FavoriteStream(
                    name = item.getString("name"),
                    address = item.getString("address"),
                    protocol = item.optString("protocol", "SRT"),
                    srtMode = item.optString("srtMode", "caller"),
                    latencyMs = item.optInt("latencyMs", 125),
                    deJitterMs = item.optInt("deJitterMs", 200)
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun remove(name: String) {
        val remaining = get().filterNot { it.name == name }
        preferences.edit().remove(KEY).apply()
        remaining.reversed().forEach(::save)
    }

    companion object { private const val KEY = "favorite_streams_v1" }
}
