package de.jobisoft.amster.util

/**
 * True while the activity of this app is resumed, i.e. really in front. Set by MainActivity.
 * The cold-start code uses it to see whether bringing the app back after the Amazon Music screen
 * has worked, the call "returned without exception" says nothing about that.
 */
object AppForeground {
    @Volatile
    var resumed: Boolean = false
}
