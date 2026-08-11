package com.isaque.signalplay

import android.content.SharedPreferences

class RecentStreams(private val preferences: SharedPreferences) {
    fun add(address: String) {
        val next = (listOf(address) + get()).distinct().take(8)
        preferences.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    fun get(): List<String> = preferences.getString(KEY, "")
        .orEmpty().lineSequence().filter { it.isNotBlank() }.toList()

    companion object { private const val KEY = "recent_streams" }
}
