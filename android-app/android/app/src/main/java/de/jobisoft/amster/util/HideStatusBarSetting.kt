package de.jobisoft.amster.util

import android.content.Context

/**
 * The switch "Hide the status bar while playing" on the Options screen, kept in the preferences of the app. The status bar
 * of some phones shows the title of the song that plays in the Amazon Music app, which would give the card away.
 * The play and quiz screens read it when they are shown.
 */
object HideStatusBarSetting {
    private const val PREFS = "hide_status_bar"
    private const val KEY = "enabled"

    /** On unless the user turned it off. */
    const val DEFAULT = true

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, DEFAULT)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }

    /** The status bar is hidden while the setting is on and a card song is on the screen ([songOnScreen]). */
    fun shouldHide(enabled: Boolean, songOnScreen: Boolean): Boolean = enabled && songOnScreen
}
