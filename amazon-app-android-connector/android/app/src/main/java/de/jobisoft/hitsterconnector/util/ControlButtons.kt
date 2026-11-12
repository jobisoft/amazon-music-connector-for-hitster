package de.jobisoft.hitsterconnector.util

/** The four things the two toggle buttons can do. [wire] is the action name of `ConnectorRepository.control`. */
enum class ControlAction(val wire: String) {
    PAUSE("pause"),
    PLAY("play"),
    STOP("stop"),
    RESTART("restart")
}

/** What the two toggles show. Each shows the NEXT possible action, not the state. */
data class ControlButtonState(val pausePlay: ControlAction, val stopRestart: ControlAction)

object ControlButtons {
    /**
     * Pause <-> Play: "Pause" while the song plays, otherwise "Play" (paused, stopped, ended).
     * Stop <-> Restart: "Stop" while the song is there (playing or paused), "Restart" once it was
     * stopped or has ended.
     */
    fun from(playing: Boolean, stopped: Boolean, songEnded: Boolean): ControlButtonState = ControlButtonState(
        pausePlay = if (playing && !stopped && !songEnded) ControlAction.PAUSE else ControlAction.PLAY,
        stopRestart = if (stopped || songEnded) ControlAction.RESTART else ControlAction.STOP
    )
}

/** What a tap on "Play" does. */
enum class PlayAction {
    /** Paused by hand, the player is on the card song: `play()` goes on at the same position. */
    RESUME,

    /** Stopped before: the position is the start offset already, seek there (harmless) and play, like restart. */
    SEEK_AND_PLAY,

    /** The song has ended or the player is on another item or gone: the whole start sequence for the card song. */
    FULL_SEQUENCE,

    /** There was no request yet. */
    NOTHING_TO_PLAY
}

object PlayDecision {
    /** "Play" must never resume a wrong item, and after the end it starts the card song again. */
    fun forPlay(hasLastRequest: Boolean, hasSession: Boolean, sameSong: Boolean, songEnded: Boolean, stopped: Boolean): PlayAction = when {
        !hasLastRequest -> PlayAction.NOTHING_TO_PLAY
        !hasSession || !sameSong || songEnded -> PlayAction.FULL_SEQUENCE
        stopped -> PlayAction.SEEK_AND_PLAY
        else -> PlayAction.RESUME
    }
}

/** Leaving the screen with the controls (Back, the system back, the screen destroyed). */
object LeaveScreen {
    /**
     * The song is stopped (paused and rewound) only if a request of this screen went out and Amazon
     * Music has a session. A screen that never played something leaves the player alone. The guard is
     * disarmed in every case.
     */
    fun shouldStop(playedHere: Boolean, hasSession: Boolean): Boolean = playedHere && hasSession
}
