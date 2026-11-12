package de.jobisoft.hitsterconnector.util

/** Is it the same song? One answer for the end guard, the restart and the quiz. */
object SongIdentity {
    /**
     * The same media id, or about the same title (case, accents, "(Remastered)" and so on do not
     * count, one title may contain the other). A missing title on the second side means the player
     * is between two songs, that is not "another song".
     */
    fun same(aId: String?, aTitle: String?, bId: String?, bTitle: String?): Boolean {
        if (aId != null && bId != null && aId == bId) return true
        val a = QuizPicker.normalize(aTitle ?: return aId == null || bId == null || aId == bId)
        val b = QuizPicker.normalize(bTitle ?: return true)
        return a.isEmpty() || b.isEmpty() || a.contains(b) || b.contains(a)
    }
}

/** What "Play again" does. */
enum class RestartAction {
    /** The player is on the requested song: seek to the start offset and play. */
    SEEK_AND_PLAY,

    /** The player is on another song (its queue went on) or there is no session: run the whole play path again. */
    REPLAY_STORED_SONG,

    /** There was no request yet. */
    NOTHING_TO_PLAY
}

object RestartDecision {
    /** After the end of the song ([songEnded]) the card song is started again from the beginning of the sequence. */
    fun decide(hasLastRequest: Boolean, hasSession: Boolean, sameSong: Boolean, songEnded: Boolean = false): RestartAction = when {
        !hasLastRequest -> RestartAction.NOTHING_TO_PLAY
        !hasSession || !sameSong || songEnded -> RestartAction.REPLAY_STORED_SONG
        else -> RestartAction.SEEK_AND_PLAY
    }
}

/**
 * The switch detector, a guard of a card song. It watches from the start of the song until the
 * next request of the app, also after the song has ended: Amazon may go on to the next item of its
 * queue, and that item must not play. It pauses when the
 * session is on anything but the guarded song and plays (or buffers). Something that does not play is
 * left alone. An item that shows only another id, without a title yet, waits: the player may be
 * between two songs.
 */
object SwitchDetector {
    fun shouldPause(
        guardedId: String?,
        guardedTitle: String?,
        currentId: String?,
        currentTitle: String?,
        state: Int?
    ): Boolean =
        StopConfirm.needsStop(state) && (currentId != null || currentTitle != null) &&
            !SongIdentity.same(guardedId, guardedTitle, currentId, currentTitle)
}
