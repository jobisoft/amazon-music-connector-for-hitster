package de.jobisoft.amster

import android.media.session.PlaybackState
import de.jobisoft.amster.util.PlayFlow.Item
import de.jobisoft.amster.util.PlayReactor
import de.jobisoft.amster.util.PlayReactor.Action
import de.jobisoft.amster.util.SessionSnapshot
import de.jobisoft.amster.util.StartPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayReactorTest {
    private val seekTo = PlaybackState.ACTION_SEEK_TO
    private val durationMs = 200_000L // start offset 20 s

    private fun snap(
        state: Int,
        mediaId: String? = "OURS",
        positionMs: Long = 0,
        actions: Long = seekTo,
        duration: Long = durationMs
    ) = SessionSnapshot(state, positionMs, 0L, 1f, actions, mediaId, "Song", "Artist", duration)

    private fun ctx() = PlayReactor.Ctx()

    private fun step(s: SessionSnapshot, item: Item, c: PlayReactor.Ctx, now: Long = 1_000) = PlayReactor.onSnapshot(s, item, c, now)

    // ── before our song plays: nothing is sent ─────────────────────────────────

    @Test
    fun nothingIsPausedOrPlayedWhileOurItemLoadsOrBuffers() {
        for (state in listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)) {
            assertEquals(Action.None, step(snap(state), Item.OURS, ctx()).action)
        }
    }

    @Test
    fun anItemOfAnotherSongThatPlaysGetsNoSeek() {
        val r = step(snap(PlaybackState.STATE_PLAYING, mediaId = "OLD", positionMs = 9_000), Item.FOREIGN, ctx())
        assertEquals(Action.None, r.action)
        assertNull(r.ctx.seekSentAtMs)
    }

    @Test
    fun anItemWithoutMetadataGetsNoSeek() {
        assertEquals(Action.None, step(snap(PlaybackState.STATE_PLAYING, mediaId = null), Item.NONE, ctx()).action)
    }

    // ── the seek ────────────────────────────────────────────────────────────────

    @Test
    fun theFirstPlayingReportOfOursGetsOneSeekToTheOffsetEvenWhenItIsNearAlready() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 20_400), Item.OURS, ctx(), 2_300)
        assertEquals(Action.Seek(20_000, "to the start offset, after PLAYING"), r.action)
        assertEquals(2_300L, r.ctx.seekSentAtMs)
        assertEquals(2_300 + PlayReactor.POLL_MS, r.wakeAtMs) // looks again in 125 ms, with or without a report
    }

    @Test
    fun theSeekGoesToTheOffsetWhereverThePlayheadIs() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 800), Item.OURS, ctx(), 2_300)
        assertEquals(Action.Seek(20_000, "to the start offset, after PLAYING"), r.action)
    }

    @Test
    fun aShortSongStartsAtAQuarterOfItsLength() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 100, duration = 40_000), Item.OURS, ctx(), 2_300)
        assertEquals(Action.Seek(10_000, "to the start offset, after PLAYING"), r.action)
    }

    @Test
    fun noSeekWhenThePlayerCannotSeekOrTheLengthIsUnknownOrTheStartPositionIsOff() {
        assertEquals(Action.Done, step(snap(PlaybackState.STATE_PLAYING, actions = 0), Item.OURS, ctx()).action)
        assertEquals(Action.Done, step(snap(PlaybackState.STATE_PLAYING, duration = 0), Item.OURS, ctx()).action)
        assertEquals(
            Action.Done,
            step(snap(PlaybackState.STATE_PLAYING), Item.OURS, PlayReactor.Ctx(position = StartPosition.OFF)).action
        )
    }

    // ── after the seek: look every 125 ms ────────────────────────────────────

    private fun sent(): PlayReactor.Ctx = step(snap(PlaybackState.STATE_PLAYING, positionMs = 20_650), Item.OURS, ctx(), 2_300).ctx

    @Test
    fun playingAtTheOffsetAfterTheSeekIsDoneAtOnce() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 20_650), Item.OURS, sent(), 2_300 + PlayReactor.POLL_MS)
        assertEquals(Action.Done, r.action)
    }

    @Test
    fun playingAfterTheOffsetCountsAsArrived() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 25_000), Item.OURS, sent(), 2_500)
        assertEquals(Action.Done, r.action)
    }

    @Test
    fun whileThePlayheadIsStillAwayTheSessionIsLookedAtAgainButNoSecondSeekGoesOut() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 700), Item.OURS, sent(), 2_300 + PlayReactor.POLL_MS)
        assertEquals(Action.None, r.action)
        assertEquals(2_300 + 2 * PlayReactor.POLL_MS, r.wakeAtMs)
    }

    @Test
    fun pausedAfterTheSeekIsWaitedForAndNoPlayIsSent() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, sent(), 2_500)
        assertEquals(Action.None, r.action)
    }

    @Test
    fun afterThreeSecondsItGoesOnAnyway() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 700), Item.OURS, sent(), 2_300 + PlayReactor.ARRIVE_MAX_MS)
        assertEquals(Action.Done, r.action)
        val paused = step(snap(PlaybackState.STATE_PAUSED), Item.OURS, sent(), 2_300 + PlayReactor.ARRIVE_MAX_MS)
        assertEquals(Action.Done, paused.action)
    }

    @Test
    fun theWakeNeverLiesBeyondTheThreeSeconds() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 700), Item.OURS, sent(), 2_300 + PlayReactor.ARRIVE_MAX_MS - 50)
        assertEquals(2_300 + PlayReactor.ARRIVE_MAX_MS, r.wakeAtMs)
    }

    // ── give up ────────────────────────────────────────────────────────────────

    @Test
    fun errorOfOurItemGivesUp() {
        assertTrue(step(snap(PlaybackState.STATE_ERROR), Item.OURS, ctx()).action is Action.GiveUp)
    }

    @Test
    fun ourItemThatDoesNotPlayInTimeGivesUp() {
        val seen = step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, ctx(), 1_000)
        assertEquals(1_000L + ctx().limitMs, seen.wakeAtMs)
        val late = step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, seen.ctx, 1_000 + ctx().limitMs)
        assertTrue(late.action is Action.GiveUp)
    }

    @Test
    fun theLimitCanBeShorter() {
        val c = PlayReactor.Ctx(limitMs = 4_500)
        val seen = step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, c, 1_000)
        assertTrue(step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, seen.ctx, 5_500).action is Action.GiveUp)
    }

    // ── the position targets ───────────────────────────────────────────────────

    @Test
    fun theMiddleOfTheSongIsTheSeekTarget() {
        val r = step(snap(PlaybackState.STATE_PLAYING), Item.OURS, PlayReactor.Ctx(position = StartPosition.MIDDLE))
        assertEquals(Action.Seek(100_000, "to the start offset, after PLAYING"), r.action)
    }

    @Test
    fun thirtySecondsIsTheTargetForS30() {
        val r = step(snap(PlaybackState.STATE_PLAYING), Item.OURS, PlayReactor.Ctx(position = StartPosition.S30))
        assertEquals(Action.Seek(30_000, "to the start offset, after PLAYING"), r.action)
    }

    @Test
    fun tenSecondsIsTheTargetForS10() {
        val r = step(snap(PlaybackState.STATE_PLAYING), Item.OURS, PlayReactor.Ctx(position = StartPosition.S10))
        assertEquals(Action.Seek(10_000, "to the start offset, after PLAYING"), r.action)
    }
}
