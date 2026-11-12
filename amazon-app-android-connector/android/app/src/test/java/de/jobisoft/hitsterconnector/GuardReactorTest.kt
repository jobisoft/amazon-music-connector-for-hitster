package de.jobisoft.hitsterconnector

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.EndDecision
import de.jobisoft.hitsterconnector.util.EndPlanner
import de.jobisoft.hitsterconnector.util.GuardReactor
import de.jobisoft.hitsterconnector.util.GuardReactor.Why
import de.jobisoft.hitsterconnector.util.SessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the end guard does with a report of the session: the end, the end zone, the switch, and what it leaves alone. */
class GuardReactorTest {

    private val playing = PlaybackState.STATE_PLAYING
    private val paused = PlaybackState.STATE_PAUSED
    private val buffering = PlaybackState.STATE_BUFFERING

    private val zoneMs = (EndDecision.END_ZONE_S * 1000).toLong()
    private val durationMs = 200_000L
    private val now = 1_000_000L

    private val ctx = GuardReactor.Ctx("B0SONG", "Song")

    /** A report taken at [now] with the position valid at that moment. */
    private fun snap(
        state: Int = playing,
        positionMs: Long = 50_000L,
        id: String? = "B0SONG",
        title: String? = "Song",
        duration: Long = durationMs,
        speed: Float = 1f
    ) = SessionSnapshot(
        state = state, positionMs = positionMs, updatedAtMs = now, speed = if (state == playing) speed else 0f,
        actions = 0L, mediaId = id, title = title, artist = null, durationMs = duration
    )

    // ── the end timer ───────────────────────────────────────────────────────────

    @Test
    fun aSongThatPlaysInFrontOfTheZoneGetsATimerForTheMomentItReachesTheZone() {
        val d = GuardReactor.onSnapshot(ctx, snap(positionMs = 50_000L), now)
        assertNull(d.pause)
        // 200 s - 1 s zone - 50 s = 149 s
        assertEquals(149_000L, d.timerMs)
    }

    @Test
    fun theTimerCountsTheTimeSinceTheReportAndTheSpeed() {
        // reported 2 s ago at 50 s: the playhead is at 52 s now
        val s = snap(positionMs = 50_000L).copy(updatedAtMs = now - 2_000L)
        assertEquals(147_000L, GuardReactor.onSnapshot(ctx, s, now).timerMs)
        // double speed halves the wait
        assertEquals(74_500L, GuardReactor.onSnapshot(ctx, snap(positionMs = 50_000L, speed = 2f), now).timerMs)
    }

    @Test
    fun noTimerWhilePausedBufferingOrWithoutALength() {
        assertNull(GuardReactor.onSnapshot(ctx, snap(state = paused), now).timerMs)
        assertNull(GuardReactor.onSnapshot(ctx, snap(state = buffering), now).timerMs)
        assertNull(GuardReactor.onSnapshot(ctx, snap(duration = 0L), now).timerMs)
        assertNull(GuardReactor.onSnapshot(ctx, snap(duration = 2_000L, positionMs = 500L), now).timerMs) // too short for an end
        assertNull(GuardReactor.onSnapshot(ctx, null, now).timerMs) // no session
    }

    @Test
    fun theTimerThatRanOutPausesTheSongForItsEnd() {
        // armed for 149 s, ran out: the playhead is at the zone
        val atZone = snap(positionMs = durationMs - zoneMs)
        val d = GuardReactor.onTimer(ctx, atZone, now)
        assertEquals(Why.END, d.pause)
        assertTrue(d.endNow)
    }

    @Test
    fun aTimerThatRanOutALittleEarlyStillCountsAsTheEnd() {
        val d = GuardReactor.onTimer(ctx, snap(positionMs = durationMs - zoneMs - 300), now)
        assertEquals(Why.END, d.pause)
    }

    @Test
    fun aTimerThatRanOutFarFromTheZoneIsJudgedAsANormalReport() {
        // the playhead was moved back and no report came: the timer is planned again, nothing is paused
        val d = GuardReactor.onTimer(ctx, snap(positionMs = 30_000L), now)
        assertNull(d.pause)
        assertEquals(169_000L, d.timerMs)
    }

    @Test
    fun aTimerThatRanOutWhilePausedOrOnAnotherItemDoesNotEndTheSong() {
        assertNull(GuardReactor.onTimer(ctx, snap(state = paused, positionMs = durationMs - zoneMs), now).pause)
        assertNull(GuardReactor.onTimer(ctx, null, now).pause)
    }

    // ── the end zone ────────────────────────────────────────────────────────────

    @Test
    fun aSongThatPlaysInsideTheZoneIsPausedAtOnceAndThatIsTheEnd() {
        val d = GuardReactor.onSnapshot(ctx, snap(positionMs = durationMs - 500), now)
        assertEquals(Why.END, d.pause)
        assertTrue(d.endNow)
        assertNull(d.timerMs)
    }

    @Test
    fun noPauseInFrontOfTheZone() {
        val d = GuardReactor.onSnapshot(ctx, snap(positionMs = durationMs - zoneMs - 1_000), now)
        assertNull(d.pause)
        assertNotNull(d.timerMs)
    }

    @Test
    fun aPlayStartedInsideTheZoneAfterTheEndIsPausedAgainButIsNoNewEnd() {
        val ended = ctx.copy(ended = true, pausesSent = 1)
        val d = GuardReactor.onSnapshot(ended, snap(positionMs = durationMs - 400), now)
        assertEquals(Why.END_ZONE, d.pause)
        assertFalse("the end was reported once", d.endNow)
    }

    @Test
    fun aPausedSongInsideTheZoneIsLeftAlone() {
        val ended = ctx.copy(ended = true, pausesSent = 1)
        val d = GuardReactor.onSnapshot(ended, snap(state = paused, positionMs = durationMs - 400), now)
        assertNull(d.pause)
        assertFalse(d.clearEnded)
        assertNull(d.timerMs)
    }

    @Test
    fun aSeekBackOutOfTheZoneClearsTheEndAndPlanTheTimerAgain() {
        val ended = ctx.copy(ended = true, pausesSent = 1)
        val d = GuardReactor.onSnapshot(ended, snap(positionMs = 100_000L), now)
        assertTrue(d.clearEnded)
        assertNull(d.pause)
        assertEquals(99_000L, d.timerMs)
        // and the end comes again, as a new end
        val again = GuardReactor.onSnapshot(ended.copy(ended = false), snap(positionMs = durationMs - 900), now)
        assertTrue(again.endNow)
    }

    @Test
    fun aPauseThatLandedJustInFrontOfTheZoneDoesNotUndoTheEnd() {
        val ended = ctx.copy(ended = true, pausesSent = 1)
        // paused 0.3 s in front of the zone boundary: inside the margin
        val d = GuardReactor.onSnapshot(ended, snap(state = paused, positionMs = durationMs - zoneMs - 300), now)
        assertFalse(d.clearEnded)
    }

    @Test
    fun noEndHandlingForAShortOrUnknownLength() {
        val d = GuardReactor.onSnapshot(ctx, snap(duration = 2_500L, positionMs = 2_000L), now)
        assertNull(d.pause)
        assertNull(d.timerMs)
        assertNull(GuardReactor.onSnapshot(ctx, snap(duration = 0L, positionMs = 99_000L), now).pause)
    }

    @Test
    fun theZoneAndTheLengthLimitAreOneConstantEach() {
        assertTrue(EndDecision.inEndZone(199.0f, 200f))
        assertFalse(EndDecision.inEndZone(198.9f, 200f))
        assertFalse(EndDecision.inEndZone(2.5f, 2.9f)) // too short to have an end
        assertEquals(1.0f, EndDecision.END_ZONE_S, 0f)
    }

    // ── the switch detector ─────────────────────────────────────────────────────

    @Test
    fun anotherItemThatPlaysOrBuffersIsPausedAtOnce() {
        assertEquals(Why.SWITCH, GuardReactor.onSnapshot(ctx, snap(id = "B0OTHER", title = "Other"), now).pause)
        assertEquals(Why.SWITCH, GuardReactor.onSnapshot(ctx, snap(state = buffering, id = "B0OTHER", title = "Other"), now).pause)
    }

    @Test
    fun anotherItemThatPlaysAfterTheEndIsPausedToo() {
        val ended = ctx.copy(ended = true, pausesSent = 1)
        assertEquals(Why.SWITCH, GuardReactor.onSnapshot(ended, snap(id = "B0OTHER", title = "Other", positionMs = 0L), now).pause)
    }

    @Test
    fun anotherItemThatDoesNotPlayIsLeftAloneAndGetsNoTimer() {
        val d = GuardReactor.onSnapshot(ctx, snap(state = paused, id = "B0OTHER", title = "Other"), now)
        assertNull(d.pause)
        assertNull(d.timerMs)
    }

    @Test
    fun anotherIdWithoutATitleYetIsTheGapBetweenTwoSongsAndWaits() {
        assertNull(GuardReactor.onSnapshot(ctx, snap(id = "B0OTHER", title = null), now).pause)
        assertNull(GuardReactor.onSnapshot(ctx, snap(id = null, title = null), now).pause)
    }

    @Test
    fun theSameSongWithAChangedTitleIsNotASwitch() {
        assertNull(GuardReactor.onSnapshot(ctx, snap(id = "B0NEWID", title = "Song (Remastered)"), now).pause)
    }

    @Test
    fun theNumberOfPausesIsCapped() {
        val spent = ctx.copy(pausesSent = GuardReactor.PAUSES_MAX)
        assertNull(GuardReactor.onSnapshot(spent, snap(id = "B0OTHER", title = "Other"), now).pause)
        assertNull(GuardReactor.onSnapshot(spent, snap(positionMs = durationMs - 400), now).pause)
        assertNull(GuardReactor.onTimer(spent, snap(positionMs = durationMs - zoneMs), now).pause)
        val one = ctx.copy(pausesSent = GuardReactor.PAUSES_MAX - 1)
        assertEquals(Why.SWITCH, GuardReactor.onSnapshot(one, snap(id = "B0OTHER", title = "Other"), now).pause)
    }

    // ── the arithmetic of the timer ──────────────────────────────────────────────

    @Test
    fun theDelayIsZeroInsideTheZoneAndNullWhenNothingPlays() {
        assertEquals(0L, EndPlanner.delayUntilPauseMs(playing, 199_500L, now, 1f, durationMs, now))
        assertNull(EndPlanner.delayUntilPauseMs(paused, 50_000L, now, 0f, durationMs, now))
        assertNull(EndPlanner.delayUntilPauseMs(playing, 50_000L, now, 0f, durationMs, now)) // speed 0
        assertNull(EndPlanner.delayUntilPauseMs(playing, 50_000L, now, 1f, 0L, now)) // unknown length
        assertNull(EndPlanner.delayUntilPauseMs(playing, 1_000L, now, 1f, 2_999L, now)) // shorter than 3 s
    }

    @Test
    fun theDelayRoundsUpSoTheTimerNeverFiresBeforeTheZone() {
        // (200000 - 1000 - 50000) / 1.5 = 99333.33 ms
        assertEquals(99_334L, EndPlanner.delayUntilPauseMs(playing, 50_000L, now, 1.5f, durationMs, now))
    }

    @Test
    fun anUnknownReportTimeMeansNoElapsedTime() {
        assertEquals(149_000L, EndPlanner.delayUntilPauseMs(playing, 50_000L, 0L, 1f, durationMs, now))
    }

    // ── the end by a switch or a stop ───────────────────────────────────────────

    private val stopped = PlaybackState.STATE_STOPPED
    private val none = PlaybackState.STATE_NONE

    /** The context after the card song was seen playing at [positionMs], taken at [now]. */
    private fun seen(positionMs: Long, state: Int = playing, base: GuardReactor.Ctx = ctx) =
        GuardReactor.track(base, snap(state = state, positionMs = positionMs), now)

    private fun foreign(state: Int = playing) = snap(state = state, positionMs = 0L, id = "B0NEXT", title = "Next")

    @Test
    fun aSwitchOneSecondBeforeTheEndPausesTheOtherItemAndEndsTheSong() {
        val c = seen(199_000L)
        val d = GuardReactor.onSnapshot(c, foreign(), now)
        assertEquals(Why.SWITCH, d.pause)
        assertTrue(d.endNow)
        assertEquals(GuardReactor.END_SWITCH, d.endReason)
    }

    @Test
    fun aSwitchInTheMiddleOfTheSongPausesButDoesNotEndIt() {
        val d = GuardReactor.onSnapshot(seen(170_000L), foreign(), now) // 30 s before the end
        assertEquals(Why.SWITCH, d.pause)
        assertFalse(d.endNow)
    }

    @Test
    fun theWindowIsTheLastFiveSeconds() {
        assertTrue(GuardReactor.onSnapshot(seen(195_100L), foreign(), now).endNow) // 4.9 s before the end
        assertFalse(GuardReactor.onSnapshot(seen(194_900L), foreign(), now).endNow) // 5.1 s before the end
    }

    @Test
    fun aSwitchAfterTheSongEndedFiresNoSecondEnd() {
        val c = seen(199_000L).copy(ended = true)
        val d = GuardReactor.onSnapshot(c, foreign(), now)
        assertEquals(Why.SWITCH, d.pause)
        assertFalse(d.endNow)
    }

    @Test
    fun theLastPositionIsCarriedOnWithTheClockWhileItPlayed() {
        val c = seen(190_000L) // 10 s before the end, seen at [now]
        assertFalse(GuardReactor.onSnapshot(c, foreign(), now + 4_000L).endNow) // 6 s before the end
        assertTrue(GuardReactor.onSnapshot(c, foreign(), now + 6_000L).endNow) // 4 s before the end
        assertEquals(194f, GuardReactor.extrapolatedS(c, now + 4_000L), 0.01f)
    }

    @Test
    fun aPausedSongDoesNotMoveOnWithTheClock() {
        val c = seen(199_000L, state = paused)
        assertEquals(199f, GuardReactor.extrapolatedS(c, now + 60_000L), 0.01f)
    }

    @Test
    fun aSwitchWithoutAKnownLengthIsNotAnEnd() {
        val c = GuardReactor.track(ctx, snap(duration = 0L, positionMs = 199_000L), now)
        assertFalse(GuardReactor.onSnapshot(c, foreign(), now).endNow)
        val short = GuardReactor.track(ctx, snap(duration = 2_000L, positionMs = 1_900L), now)
        assertFalse(GuardReactor.onSnapshot(short, foreign(), now).endNow)
    }

    @Test
    fun aSwitchBeforeAnyReportOfTheSongIsNotAnEnd() {
        assertFalse(GuardReactor.onSnapshot(ctx, foreign(), now).endNow)
    }

    @Test
    fun aForeignItemThatIsPausedAtTheEndEndsTheSongToo() {
        val d = GuardReactor.onSnapshot(seen(199_000L), foreign(state = paused), now)
        assertTrue(d.endNow)
        assertEquals(GuardReactor.END_SWITCH, d.endReason)
    }

    @Test
    fun aStopWithinTheLastSecondsEndsTheSongWithoutAPause() {
        val d = GuardReactor.onSnapshot(seen(197_000L), snap(state = stopped, positionMs = 0L), now)
        assertNull(d.pause)
        assertTrue(d.endNow)
        assertEquals(GuardReactor.END_STOPPED, d.endReason)
        assertTrue(GuardReactor.onSnapshot(seen(197_000L), snap(state = none, positionMs = 0L), now).endNow)
    }

    @Test
    fun aStopInTheMiddleOfTheSongIsNotAnEnd() {
        val d = GuardReactor.onSnapshot(seen(100_000L), snap(state = stopped, positionMs = 0L), now)
        assertFalse(d.endNow)
    }

    @Test
    fun aPauseByHandNearTheEndIsNotAnEnd() {
        val d = GuardReactor.onSnapshot(seen(197_000L), snap(state = paused, positionMs = 197_000L), now)
        assertFalse(d.endNow)
        assertNull(d.pause)
    }

    @Test
    fun aStopAfterTheEndWasPausedFiresNoSecondEnd() {
        val c = seen(199_000L).copy(ended = true)
        assertFalse(GuardReactor.onSnapshot(c, snap(state = stopped, positionMs = 0L), now).endNow)
    }

    @Test
    fun aStoppedReportDoesNotOverwriteTheLastPosition() {
        val c = seen(197_000L)
        val after = GuardReactor.track(c, snap(state = stopped, positionMs = 0L), now + 100L)
        assertEquals(c, after)
        // a report of another item is not taken either
        assertEquals(c, GuardReactor.track(c, foreign(), now + 100L))
    }

    @Test
    fun theEndByATimerKeepsItsReason() {
        val d = GuardReactor.onSnapshot(ctx, snap(positionMs = 199_500L), now)
        assertTrue(d.endNow)
        assertEquals(GuardReactor.END_PAUSED, d.endReason)
    }

    // ── no expected song: the guard does nothing ───────────────────────────────

    private val nothing = GuardReactor.Ctx(null, null)

    @Test
    fun withoutAnExpectedSongNothingIsPausedWhateverPlays() {
        assertFalse(GuardReactor.hasSong(nothing))
        val d = GuardReactor.onSnapshot(nothing, snap(id = "B0OLD", title = "Old"), now)
        assertNull(d.pause)
        assertFalse(d.endNow)
        assertNull(d.timerMs)
    }

    @Test
    fun withoutAnExpectedSongTheTimerAndTheTrackingDoNothing() {
        assertEquals(nothing, GuardReactor.track(nothing, snap(), now))
        val d = GuardReactor.onTimer(nothing, snap(positionMs = 199_000L), now)
        assertNull(d.pause)
        assertFalse(d.endNow)
    }

    @Test
    fun anExpectedSongIsGuardedAsBefore() {
        assertTrue(GuardReactor.hasSong(ctx))
        assertEquals(Why.SWITCH, GuardReactor.onSnapshot(ctx, snap(id = "B0OLD", title = "Old"), now).pause)
    }
}
