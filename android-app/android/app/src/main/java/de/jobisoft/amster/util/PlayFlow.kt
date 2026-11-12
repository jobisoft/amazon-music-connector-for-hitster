package de.jobisoft.amster.util

import android.media.session.PlaybackState

/**
 * The decisions of one play request between its phases, see [Phase].
 *
 * Amazon Music that was just started has a session before its player is ready. The end guard takes care of a
 * foreign item that replaces our song (see EndGuard), the request itself only waits for its song to play.
 */
object PlayFlow {
    /**
     * The phases of one request. A card with an Amazon id goes through LOAD (the session must show the id) and
     * START (the track plays at its start offset), the text search is the fallback of both. A card without an id
     * starts with SEARCH. WAKE is the cold start of Amazon Music before the first of them.
     */
    enum class Phase { WAKE, LOAD, START, SEARCH }

    /** What the request does next. */
    enum class Next { SEARCH, PLAYING, FAILED }

    /** What the session shows compared to the requested id: ours, another item, or nothing usable (no metadata yet). */
    enum class Item { OURS, FOREIGN, NONE }

    private const val PLAY_FROM_ACTIONS = PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
        PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_PLAY_FROM_URI

    /** Time for the session to show the requested id after the address was handed over. */
    const val LOAD_MS = 4_000L

    /** The load screen offers Cancel after this time without a playing song. */
    const val CANCEL_AFTER_MS = 10_000L

    /**
     * True for a session of Amazon Music that exists but has no player behind it yet: no title and either no
     * state or no action beyond the three `PLAY_FROM_*` ones. Such a session counts as no session.
     */
    fun isBareSession(hasTitle: Boolean, state: Int, actions: Long): Boolean =
        !hasTitle && (state == PlaybackState.STATE_NONE || (actions and PLAY_FROM_ACTIONS.inv()) == 0L)

    /** Compares the media id the session shows with the requested one. */
    fun classify(shownMediaId: String?, wantedId: String): Item = when {
        shownMediaId.isNullOrEmpty() -> Item.NONE
        shownMediaId == wantedId -> Item.OURS
        else -> Item.FOREIGN
    }

    fun afterSearch(playing: Boolean): Next = if (playing) Next.PLAYING else Next.FAILED
}
