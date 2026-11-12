package de.jobisoft.hitsterconnector.util

/** What [VolumeDuck] does with the volume level of the music stream, without Android, to test the rules. */
object DuckPolicy {
    enum class OnDuck {
        /** The setting on the Options screen is off: the level is not changed, nothing on disk, no timer. */
        OFF,

        /** Remember the current level and set the level to 0. */
        DUCK,

        /** The start position is Off: the song is not moved, so there is no moment to hide. Nothing is ducked. */
        NO_START,

        /** The level is 0 and it was this app that set it: keep the remembered level. */
        KEEP,

        /** The level is 0 and it was not this app: nothing to duck, nothing to restore later. */
        SKIP
    }

    /**
     * [enabled] is the switch of the Options screen, [startPositionOff] says the start position there is Off,
     * [current] the level of the stream now, [pending] the level this app remembered before it set 0.
     */
    fun onDuck(enabled: Boolean, current: Int, pending: Int?, startPositionOff: Boolean = false): OnDuck = when {
        !enabled -> OnDuck.OFF
        startPositionOff -> OnDuck.NO_START
        current > 0 -> OnDuck.DUCK
        pending != null -> OnDuck.KEEP
        else -> OnDuck.SKIP
    }

    /** The level is restored only if it is still 0. A level the user set meanwhile stays. */
    fun shouldRestore(current: Int, saved: Int?): Boolean = saved != null && saved > 0 && current == 0
}

/** The level that is remembered between the duck and the restore. */
class DuckState(initial: Int? = null) {
    var pending: Int? = initial
        private set

    fun remember(level: Int) {
        pending = level
    }

    /** Returns the remembered level and forgets it. Null if there is none (the second call of a restore). */
    fun take(): Int? {
        val saved = pending
        pending = null
        return saved
    }
}
