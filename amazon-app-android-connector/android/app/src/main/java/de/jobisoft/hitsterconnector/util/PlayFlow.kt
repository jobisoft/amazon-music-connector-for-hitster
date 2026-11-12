package de.jobisoft.hitsterconnector.util

import android.media.session.PlaybackState

/**
 * The decisions of one play request between its phases, see [Phase].
 *
 * Amazon Music that was just started has a session before its player is ready. A track loaded into such
 * a session can be replaced by Amazon's own old queue a moment later. The request therefore lets the item
 * settle after the load when it began from a bare session, and loads the id again (at most [RELOAD_MAX]
 * times, only within [RELOAD_WINDOW_MS] of the start of the id route) when a foreign item took its place.
 * Worst case of the id route: the last reload begins before 12 s and takes up to 4 s load, 1 s settle and
 * 15 s start wait, so about 32 s.
 */
object PlayFlow {
    /**
     * The phases of one request. A card with an Amazon id goes through LOAD (the session must show the id) and
     * START (the track plays at its start offset), the text search is the fallback of both. A card without an id
     * starts with SEARCH. WAKE is the cold start of Amazon Music before the first of them.
     */
    enum class Phase { WAKE, LOAD, START, SEARCH }

    /** What the request does next. */
    enum class Next { SEARCH, PLAYING, FAILED, RELOAD }

    /** What the session shows compared to the requested id: ours, another item, or nothing usable (no metadata yet). */
    enum class Item { OURS, FOREIGN, NONE }

    /** The loaded item must stay ours this long before the final play() is sent (cold start only). */
    const val SETTLE_MS = 1_000L

    /** How often the id is loaded again after a foreign item replaced it. */
    const val RELOAD_MAX = 2

    /** A reload is only started within this time since the id route began, so the route stays bounded. */
    const val RELOAD_WINDOW_MS = 12_000L

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

    /**
     * May the final play() be sent? The settle check only runs when the request began from a cold or bare session:
     * then the item must have been ours for [SETTLE_MS] without a break.
     */
    fun settled(coldStart: Boolean, oursForMs: Long): Boolean = !coldStart || oursForMs >= SETTLE_MS

    /** Compares the media id the session shows with the requested one. */
    fun classify(shownMediaId: String?, wantedId: String): Item = when {
        shownMediaId.isNullOrEmpty() -> Item.NONE
        shownMediaId == wantedId -> Item.OURS
        else -> Item.FOREIGN
    }

    /** A foreign item replaced ours: load again, or give up to the search when the reloads or the time window are used up. */
    fun afterReplaced(reloadsDone: Int, sinceRouteStartMs: Long): Next =
        if (reloadsDone < RELOAD_MAX && sinceRouteStartMs < RELOAD_WINDOW_MS) Next.RELOAD else Next.SEARCH

    fun afterSearch(playing: Boolean): Next = if (playing) Next.PLAYING else Next.FAILED
}
