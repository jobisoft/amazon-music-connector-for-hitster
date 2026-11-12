package de.jobisoft.amster.util

/**
 * The camera timeout of Play Mode: the camera is on for [MS], then the app goes back to the main screen. It starts
 * when the camera turns on (the screen opens, "Scan next card", Cancel) and a scan ends it. Pure, so it can be tested.
 */
object ScanTimeout {
    const val MS = 15_000L

    /** The whole seconds shown at the start. */
    const val SECONDS = (MS / 1000).toInt()

    fun remainingMs(startedAtMs: Long, nowMs: Long): Long = (MS - (nowMs - startedAtMs)).coerceAtLeast(0L)

    /** The seconds left, rounded up: 15 at the start, 1 for the last second, 0 only when the time is up. */
    fun remainingSeconds(startedAtMs: Long, nowMs: Long): Int = ((remainingMs(startedAtMs, nowMs) + 999L) / 1000L).toInt()

    fun expired(startedAtMs: Long, nowMs: Long): Boolean = remainingMs(startedAtMs, nowMs) == 0L
}
