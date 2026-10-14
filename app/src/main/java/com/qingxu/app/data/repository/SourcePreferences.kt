package com.qingxu.app.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SourcePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("notification_sources", Context.MODE_PRIVATE)
    private val mutableSelected = MutableStateFlow(
        preferences.getStringSet("selected_packages", emptySet())!!.toSet(),
    )
    val selected = mutableSelected.asStateFlow()

    fun setSelected(packageName: String, enabled: Boolean) {
        val updated = mutableSelected.value.toMutableSet().apply {
            if (enabled) add(packageName) else remove(packageName)
        }.toSet()
        preferences.edit().putStringSet("selected_packages", updated).apply()
        mutableSelected.value = updated
    }

    fun accepts(packageName: String): Boolean = packageName in mutableSelected.value
}
