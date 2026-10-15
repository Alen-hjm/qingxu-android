package com.qingxu.app.data.repository

import android.content.Context
import com.qingxu.app.data.local.UserLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OnboardingState(
    val completed: Boolean = false,
    val practice: Int = 0,
    val label: UserLabel = UserLabel.UNSORTED,
    val interested: Boolean = false,
) {
    fun step(hasSources: Boolean, permissionGranted: Boolean, listenerConnected: Boolean = true): Int = when {
        !hasSources -> 0
        !permissionGranted || !listenerConnected -> 1
        else -> 2 + practice
    }
}

/** Only tutorial progress is stored here; these offline examples never enter the notification database. */
class OnboardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("qingxu_onboarding", Context.MODE_PRIVATE)
    private val currentGuide = preferences.getInt("guideVersion", 0) == 2
    private val mutableState = MutableStateFlow(OnboardingState(
        completed = preferences.getBoolean("completed", false),
        practice = if (currentGuide) preferences.getInt("practice", 0).coerceIn(0, 2) else 0,
        label = if (currentGuide) UserLabel.entries.firstOrNull { it.name == preferences.getString("label", null) }
            ?.takeUnless { it == UserLabel.FILTERED } ?: UserLabel.UNSORTED else UserLabel.UNSORTED,
        interested = currentGuide && preferences.getBoolean("interested", false),
    ))
    val state = mutableState.asStateFlow()

    fun complete() = save(state.value.copy(completed = true))
    fun restart() = save(OnboardingState())

    fun demo() {
        if (!state.value.completed) save(state.value.copy(practice = 2))
    }

    fun classify(label: UserLabel) {
        val current = state.value
        if (current.completed || current.practice == 1) return
        if (label == UserLabel.FILTERED) {
            save(current.copy(practice = 1, label = current.label.takeUnless { it == UserLabel.UNSORTED }
                ?: UserLabel.IMPORTANT_URGENT))
        } else {
            save(current.copy(label = label))
        }
    }

    fun star() {
        if (!state.value.completed) save(state.value.copy(interested = !state.value.interested))
    }

    fun restore() {
        if (!state.value.completed && state.value.practice == 1) save(state.value.copy(practice = 2))
    }

    private fun save(updated: OnboardingState) {
        preferences.edit().putInt("guideVersion", 2).putBoolean("completed", updated.completed)
            .putInt("practice", updated.practice).putString("label", updated.label.name)
            .putBoolean("interested", updated.interested).apply()
        mutableState.value = updated
    }
}
