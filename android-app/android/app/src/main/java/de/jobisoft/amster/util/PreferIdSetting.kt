package de.jobisoft.amster.util

import android.content.Context

/**
 * The switch "Prefer Amazon ID over search" on the Options screen, kept in the preferences of the app. On, a card
 * with an Amazon id is played by its id and searched only if that fails. Off, every card is searched by title and
 * artist. The ids were only checked with German Amazon accounts. The setting is read at the start of every request.
 */
object PreferIdSetting {
    private const val PREFS = "prefer_id"
    private const val KEY = "enabled"

    /** On unless the user turned it off. */
    const val DEFAULT = true

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, DEFAULT)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }

    /** The id of the card is tried first only if the setting is on and the card has an id. */
    fun useId(enabled: Boolean, amazonId: String?): Boolean = enabled && amazonId != null
}
