package de.jobisoft.hitsterconnector.util

import android.content.Context

/**
 * The start position chosen on the Options screen, kept in the preferences of the app. It is read at the start of
 * every request and by the controls, so a change counts from the next card.
 */
object StartSetting {
    private const val PREFS = "start_position"
    private const val KEY = "value"

    fun get(context: Context): StartPosition =
        StartPosition.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(context: Context, position: StartPosition) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, position.key).apply()
    }
}
