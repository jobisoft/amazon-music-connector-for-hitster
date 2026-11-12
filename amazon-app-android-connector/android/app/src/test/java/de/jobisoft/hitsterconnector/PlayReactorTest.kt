package de.jobisoft.hitsterconnector

import android.media.session.PlaybackState
import de.jobisoft.hitsterconnector.util.PlayFlow.Item
import de.jobisoft.hitsterconnector.util.PlayReactor
import de.jobisoft.hitsterconnector.util.PlayReactor.Action
import de.jobisoft.hitsterconnector.util.SessionSnapshot
import de.jobisoft.hitsterconnector.util.StartPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        duration: Long = durationMs,
        title: String? = "Song"
    ) = SessionSnapshot(state, positionMs, 0L, 1f, actions, mediaId, title, "Artist", duration)

    private fun armed(canReload: Boolean = true, cold: Boolean = false, baseline: String? = "OLD") =
        PlayReactor.Ctx(canReload = canReload, cold = cold, baselineMediaId = baseline)

    private fun step(s: SessionSnapshot, item: Item, c: PlayReactor.Ctx, now: Long = 1_000) = PlayReactor.onSnapshot(s, item, c, now)

    // ── pause first, whoever the item is ──────────────────────────────────────

    @Test
    fun aForeignItemThatLoadsIsPausedAtOnce() {
        val s = snap(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, mediaId = "RESTORED")
        assertTrue(step(s, Item.FOREIGN, armed()).action is Action.Pause)
    }

    @Test
    fun aForeignItemThatPlaysIsPausedAtOnce() {
        val s = snap(PlaybackState.STATE_PLAYING, mediaId = "RESTORED", positionMs = 4_000)
        val r = step(s, Item.FOREIGN, armed())
        assertTrue(r.action is Action.Pause)
        assertEquals(1, r.ctx.pauses)
        // a foreign pause does not count as a pause of our item
        assertEquals(0, r.ctx.ownPauses)
    }

    @Test
    fun aForeignItemThatBuffersIsPausedToo() {
        assertTrue(step(snap(PlaybackState.STATE_BUFFERING, mediaId = "RESTORED"), Item.FOREIGN, armed()).action is Action.Pause)
    }

    @Test
    fun ourItemThatSkipsBuffersOrPlaysIsPaused() {
        for (state in listOf(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)) {
            val r = step(snap(state), Item.OURS, armed())
            assertTrue("state $state", r.action is Action.Pause)
            assertEquals(1, r.ctx.ownPauses)
        }
        // playing at position 0, the intro runs
        assertTrue(step(snap(PlaybackState.STATE_PLAYING, positionMs = 0), Item.OURS, armed()).action is Action.Pause)
    }

    @Test
    fun anItemWithoutMetadataThatIsOnItsWayIsPausedToo() {
        val s = snap(PlaybackState.STATE_BUFFERING, mediaId = null, title = null)
        assertTrue(step(s, Item.NONE, armed()).action is Action.Pause)
    }

    @Test
    fun pausingComesBeforeSeeking() {
        // our item buffers, the seek action is there and the duration is known: the pause still goes first
        val r = step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, armed())
        assertTrue(r.action is Action.Pause)
    }

    @Test
    fun anItemThatIsPausedAlreadyGetsNoPause() {
        assertEquals(Action.None, step(snap(PlaybackState.STATE_PAUSED, mediaId = "OLD", positionMs = 20_000), Item.FOREIGN, armed()).action)
        assertEquals(Action.None, step(snap(PlaybackState.STATE_STOPPED, mediaId = "OLD"), Item.FOREIGN, armed()).action)
    }

    @Test
    fun repeatedReportsOfTheSameStateGetOnePause() {
        val s = snap(PlaybackState.STATE_BUFFERING)
        val first = step(s, Item.OURS, armed())
        assertTrue(first.action is Action.Pause)
        // the pause is pending, the same report again (a metadata report after a state report) sends no second pause
        val second = step(s, Item.OURS, first.ctx, 1_010)
        assertFalse(second.action is Action.Pause)
        assertEquals(1, second.ctx.pauses)
    }

    @Test
    fun aFurtherReportThatStillShowsAnActivePlayerGetsAnotherPause() {
        val first = step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, armed())
        // the player went on to PLAYING in spite of the pause: another state, another pause
        val second = step(snap(PlaybackState.STATE_PLAYING, positionMs = 300), Item.OURS, first.ctx, 1_050)
        assertTrue(second.action is Action.Pause)
        assertEquals(2, second.ctx.pauses)
    }

    @Test
    fun afterPausedTheSameStateCountsAgain() {
        val first = step(snap(PlaybackState.STATE_PLAYING), Item.OURS, armed())
        val paused = step(snap(PlaybackState.STATE_PAUSED), Item.OURS, first.ctx, 1_050)
        assertNull(paused.ctx.lastPauseKey)
        // the player resumes by itself
        val again = step(snap(PlaybackState.STATE_PLAYING, positionMs = 500), Item.OURS, paused.ctx, 1_100)
        assertTrue(again.action is Action.Pause)
    }

    @Test
    fun theNumberOfPausesIsBounded() {
        var ctx = armed()
        var sent = 0
        for (i in 0 until 40) {
            // alternating states, so that no report repeats the one before
            val state = if (i % 2 == 0) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PLAYING
            val r = step(snap(state), Item.OURS, ctx, 1_000L + i)
            if (r.action is Action.Pause) sent++
            ctx = r.ctx
        }
        assertEquals(PlayReactor.PAUSES_MAX, sent)
    }

    @Test
    fun theBufferingOfOurOwnSeekIsNotPaused() {
        // paused, the seek went out, now the player buffers at the new position
        var ctx = armed().copy(pausedSinceMs = 900, ownPauses = 1, seeks = 1, seekSentAtMs = 950, seekSentFromMs = 0)
        val r = step(snap(PlaybackState.STATE_BUFFERING, positionMs = 20_000), Item.OURS, ctx, 1_000)
        assertEquals(Action.None, r.action)
        ctx = r.ctx
        assertEquals(0, ctx.pauses)
    }

    // ── then the item decides ─────────────────────────────────────────────────

    @Test
    fun ourPausedItemIsSeekedToTheStartOffset() {
        // after its first pause (it went out already) a paused item is seeked on its next report
        val ctx = armed().copy(ownPauses = 1, pauses = 1, pausedSinceMs = 900)
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, ctx)
        val action = r.action
        assertTrue(action is Action.Seek)
        assertEquals(20_000L, (action as Action.Seek).toMs)
        assertEquals(1, r.ctx.seeks)
    }

    // ── the first report of our item says PAUSED ──────────────────────────────

    @Test
    fun ourItemFirstSeenPausedGetsOnePauseAtOnceAndTheSeekWithIt() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, armed())
        assertTrue(r.action is Action.Pause)
        val then = r.andThen
        assertTrue(then is Action.Seek)
        assertEquals(20_000L, (then as Action.Seek).toMs)
        assertEquals(1, r.ctx.pauses)
        assertEquals(1, r.ctx.ownPauses)
        assertEquals(1, r.ctx.seeks)
        assertEquals(1_000L, r.ctx.pausedSinceMs)
    }

    @Test
    fun noSecondPauseForTheSameUnchangedPausedReport() {
        val first = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, armed(), 1_000)
        assertTrue(first.action is Action.Pause)
        val second = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, first.ctx, 1_010)
        assertFalse(second.action is Action.Pause)
        assertEquals(1, second.ctx.pauses)
        // and not for a later report either, the seek is waited for
        val third = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, second.ctx, 1_500)
        assertFalse(third.action is Action.Pause)
    }

    @Test
    fun ourItemFirstSeenPausedWithoutSeekPossibleGetsThePauseOnly() {
        val r = step(snap(PlaybackState.STATE_PAUSED, actions = 0), Item.OURS, armed())
        assertTrue(r.action is Action.Pause)
        assertNull(r.andThen)
    }

    @Test
    fun ourItemFirstSeenPausedAtTheOffsetNeedsNoPause() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 20_000), Item.OURS, armed(cold = true), 1_000)
        assertEquals(Action.None, r.action)
        assertNull(r.andThen)
    }

    @Test
    fun ourItemFirstSeenPausedAtTheOffsetGetsTheFinalPlayNotAPause() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 20_000), Item.OURS, armed())
        assertTrue(r.action is Action.Play)
    }

    @Test
    fun aForeignPausedItemGetsNoFirstSightPause() {
        val r = step(snap(PlaybackState.STATE_PAUSED, mediaId = "OLD", positionMs = 20_000), Item.FOREIGN, armed())
        assertEquals(Action.None, r.action)
        assertNull(r.andThen)
    }

    @Test
    fun anErrorOnFirstSightIsNotPaused() {
        val r = step(snap(PlaybackState.STATE_ERROR), Item.OURS, armed())
        assertTrue(r.action is Action.GiveUp)
    }

    @Test
    fun theSeekNeedsTheSeekAction() {
        val ctx = armed().copy(ownPauses = 1, pauses = 1, pausedSinceMs = 900)
        val r = step(snap(PlaybackState.STATE_PAUSED, actions = 0), Item.OURS, ctx)
        assertEquals(Action.None, r.action)
    }

    @Test
    fun theSeekNeedsADuration() {
        val ctx = armed().copy(ownPauses = 1, pauses = 1, pausedSinceMs = 900)
        val r = step(snap(PlaybackState.STATE_PAUSED, duration = 0), Item.OURS, ctx)
        assertEquals(Action.None, r.action)
    }

    @Test
    fun ourItemIsSeekedAfterTheFirstPauseEvenIfItIsStillLoading() {
        val first = step(snap(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM), Item.OURS, armed())
        assertTrue(first.action is Action.Pause)
        // the next report still says loading, the pause is out, the seek goes now
        val second = step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, first.ctx, 1_040)
        assertTrue(second.action is Action.Pause) // another state still gets its pause first
        val third = step(snap(PlaybackState.STATE_BUFFERING, positionMs = 1), Item.OURS, second.ctx, 1_050)
        assertTrue(third.action is Action.Seek)
    }

    @Test
    fun aSeekThatMovedNothingIsSentAgainAfterTheWaitAndNotBefore() {
        val first = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, armed(), 1_000)
        assertTrue(first.action is Action.Pause)
        assertTrue(first.andThen is Action.Seek) // the first seek went out with the first pause
        val sent = first
        val early = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, sent.ctx, 1_500)
        assertEquals(Action.None, early.action)
        assertEquals(1_000 + PlayReactor.SEEK_WAIT_MS, early.wakeAtMs)
        val late = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, early.ctx, 1_000 + PlayReactor.SEEK_WAIT_MS)
        assertTrue(late.action is Action.Seek)
        assertEquals(2, late.ctx.seeks)
    }

    @Test
    fun noPlayWhileTheItemIsNotAtTheStartOffset() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, armed())
        assertFalse(r.action is Action.Play)
    }

    @Test
    fun ourPausedItemNearTheOffsetGetsTheFinalPlay() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 20_100), Item.OURS, armed())
        assertTrue(r.action is Action.Play)
        assertFalse(r.ctx.armed)
        assertEquals(1, r.ctx.playsSent)
    }

    @Test
    fun noPlayWhileTheItemBuffers() {
        val ctx = armed().copy(pausedSinceMs = 900, ownPauses = 1, lastPauseKey = "x")
        val r = step(snap(PlaybackState.STATE_BUFFERING, positionMs = 20_000), Item.OURS, ctx)
        assertFalse(r.action is Action.Play)
    }

    @Test
    fun afterAColdStartTheFinalPlayWaitsForTheSettleTime() {
        val cold = armed(cold = true)
        val first = step(snap(PlaybackState.STATE_PAUSED, positionMs = 20_000), Item.OURS, cold, 5_000)
        assertEquals(Action.None, first.action)
        assertEquals(5_000 + 1_000L, first.wakeAtMs)
        val later = step(snap(PlaybackState.STATE_PAUSED, positionMs = 20_000), Item.OURS, first.ctx, 6_000)
        assertTrue(later.action is Action.Play)
    }

    @Test
    fun withoutDurationOrSeekActionThePlayGoesOutAfterTheJumpWait() {
        val s = snap(PlaybackState.STATE_PAUSED, duration = 0)
        val first = step(s, Item.OURS, armed(), 5_000)
        assertTrue(first.action is Action.Pause) // the first report of our item, paused: the pause goes out at once
        assertNull(first.andThen)
        assertEquals(5_000 + PlayReactor.JUMP_WAIT_MS, first.wakeAtMs)
        val later = step(s, Item.OURS, first.ctx, 5_000 + PlayReactor.JUMP_WAIT_MS)
        assertTrue(later.action is Action.Play)
    }

    @Test
    fun afterAllSeeksHaveBeenTriedThePlayGoesOutAnyway() {
        val ctx = armed().copy(pausedSinceMs = 100, ownPauses = 1, seeks = PlayReactor.SEEKS_MAX, seekSentAtMs = 100, seekSentFromMs = 0)
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, ctx, 5_000)
        assertTrue(r.action is Action.Play)
    }

    @Test
    fun aForeignItemThatIsPausedAndNewIsAReplacement() {
        val r = step(snap(PlaybackState.STATE_PAUSED, mediaId = "RESTORED"), Item.FOREIGN, armed())
        assertEquals(Action.Reload("RESTORED"), r.action)
    }

    @Test
    fun theOldSongIsNoReplacement() {
        val r = step(snap(PlaybackState.STATE_PAUSED, mediaId = "OLD"), Item.FOREIGN, armed(baseline = "OLD"))
        assertEquals(Action.None, r.action)
    }

    @Test
    fun theSearchDoesNotReload() {
        val r = step(snap(PlaybackState.STATE_PAUSED, mediaId = "RESTORED"), Item.FOREIGN, armed(canReload = false))
        assertEquals(Action.None, r.action)
    }

    @Test
    fun aForeignItemIsPausedBeforeItIsReplaced() {
        val loading = step(snap(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, mediaId = "RESTORED"), Item.FOREIGN, armed())
        assertTrue(loading.action is Action.Pause)
        val paused = step(snap(PlaybackState.STATE_PAUSED, mediaId = "RESTORED"), Item.FOREIGN, loading.ctx, 1_050)
        assertEquals(Action.Reload("RESTORED"), paused.action)
    }

    @Test
    fun anItemWithoutMetadataThatIsPausedWaits() {
        val r = step(snap(PlaybackState.STATE_PAUSED, mediaId = null, title = null), Item.NONE, armed())
        assertEquals(Action.None, r.action)
    }

    @Test
    fun errorOfOurItemGivesUp() {
        assertTrue(step(snap(PlaybackState.STATE_ERROR), Item.OURS, armed()).action is Action.GiveUp)
    }

    @Test
    fun ourItemThatDoesNotPlayInTimeGivesUp() {
        val ctx = armed().copy(oursSeenAtMs = 1_000, oursSinceMs = 1_000)
        val r = step(snap(PlaybackState.STATE_BUFFERING), Item.OURS, ctx, 1_000 + ctx.limitMs)
        assertTrue(r.action is Action.GiveUp)
    }

    // ── nothing after the final play() ────────────────────────────────────────

    private fun played(): PlayReactor.Ctx = armed().copy(
        armed = false, oursSeenAtMs = 1_000, oursSinceMs = 1_000, playsSent = 1, lastPlayAtMs = 2_000, pausedSinceMs = 1_500,
        ownPauses = 1, seeks = 1
    )

    @Test
    fun afterTheFinalPlayBufferingGetsNoPauseAndNoSeek() {
        val r = step(snap(PlaybackState.STATE_BUFFERING, positionMs = 20_000), Item.OURS, played(), 2_100)
        assertEquals(Action.None, r.action)
    }

    @Test
    fun afterTheFinalPlayAForeignItemGetsNoPause() {
        val r = step(snap(PlaybackState.STATE_PLAYING, mediaId = "RESTORED", positionMs = 9_000), Item.FOREIGN, played(), 2_100)
        assertEquals(Action.None, r.action)
    }

    @Test
    fun playingNearTheOffsetIsDone() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 20_400), Item.OURS, played(), 2_300)
        assertEquals(Action.Done, r.action)
    }

    @Test
    fun playingFarBelowTheOffsetGetsOneSeekAndNoPause() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 800), Item.OURS, played(), 2_300)
        assertTrue(r.action is Action.Seek)
        assertEquals(2, r.ctx.seeks)
    }

    @Test
    fun aPauseSoonAfterThePlayWaitsAndLaterIsPlayedAgain() {
        val s = snap(PlaybackState.STATE_PAUSED, positionMs = 20_000)
        val soon = step(s, Item.OURS, played(), 2_500)
        assertEquals(Action.None, soon.action)
        assertEquals(2_000 + 1_500L, soon.wakeAtMs)
        val later = step(s, Item.OURS, soon.ctx, 3_500)
        assertTrue(later.action is Action.Play)
        assertEquals(2, later.ctx.playsSent)
    }

    @Test
    fun theGiveUpTimeStillApplies() {
        val ctx = played()
        val r = step(snap(PlaybackState.STATE_BUFFERING, positionMs = 20_000), Item.OURS, ctx, 1_000 + ctx.limitMs)
        assertTrue(r.action is Action.GiveUp)
    }

    @Test
    fun theRuleEndsWithTheFinalPlay() {
        val toPlay = step(snap(PlaybackState.STATE_PAUSED, positionMs = 20_000), Item.OURS, armed())
        assertTrue(toPlay.action is Action.Play)
        // every later report, whatever it shows, gets no pause
        for (state in listOf(PlaybackState.STATE_BUFFERING, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_CONNECTING)) {
            assertEquals(Action.None, step(snap(state, positionMs = 20_000), Item.OURS, toPlay.ctx, 1_100).action)
        }
    }

    // ── the start position ──────────────────────────────────────────────────────

    private fun armedAt(position: StartPosition, cold: Boolean = false) =
        PlayReactor.Ctx(canReload = true, cold = cold, baselineMediaId = "OLD", position = position)

    @Test
    fun withOffOurItemIsNotPausedWhileItStarts() {
        for (state in listOf(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)) {
            val r = step(snap(state), Item.OURS, armedAt(StartPosition.OFF))
            assertEquals("state $state", Action.None, r.action)
            assertEquals(0, r.ctx.ownPauses)
        }
    }

    @Test
    fun withOffOurItemThatPlaysIsDoneAtOnce() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 300), Item.OURS, armedAt(StartPosition.OFF))
        assertEquals(Action.Done, r.action)
        assertFalse(r.ctx.armed)
    }

    @Test
    fun withOffAColdStartWaitsForTheSettleTimeBeforeItIsDone() {
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 300), Item.OURS, armedAt(StartPosition.OFF, cold = true).copy(oursSinceMs = 1_000), 1_100)
        assertEquals(Action.None, r.action)
    }

    @Test
    fun withOffAPausedItemGetsPlayAndNoSeek() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, armedAt(StartPosition.OFF))
        assertTrue(r.action is Action.Play)
        assertNull(r.andThen)
    }

    @Test
    fun withOffAForeignItemIsStillPaused() {
        val r = step(snap(PlaybackState.STATE_PLAYING, mediaId = "RESTORED", positionMs = 4_000), Item.FOREIGN, armedAt(StartPosition.OFF))
        assertTrue(r.action is Action.Pause)
    }

    @Test
    fun withOffThereIsNoCorrectionSeekAfterThePlay() {
        val ctx = armedAt(StartPosition.OFF).copy(armed = false, oursSeenAtMs = 900, lastPlayAtMs = 950)
        val r = step(snap(PlaybackState.STATE_PLAYING, positionMs = 400), Item.OURS, ctx)
        assertEquals(Action.Done, r.action)
    }

    @Test
    fun theMiddleOfTheSongIsTheSeekTarget() {
        val ctx = armedAt(StartPosition.MIDDLE).copy(ownPauses = 1, pauses = 1, pausedSinceMs = 900)
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, ctx)
        assertEquals(Action.Seek(100_000L, "to the start offset"), r.action)
    }

    @Test
    fun theMiddleWaitsForTheLengthAndThenMoves() {
        val ctx = armedAt(StartPosition.MIDDLE).copy(ownPauses = 1, pauses = 1, pausedSinceMs = 900)
        // no length yet: no seek
        assertEquals(Action.None, step(snap(PlaybackState.STATE_PAUSED, duration = 0), Item.OURS, ctx).action)
        // the length arrives: the next report seeks
        assertEquals(Action.Seek(100_000L, "to the start offset"), step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, ctx).action)
    }

    @Test
    fun theMiddleGetsTheFinalPlayWhenItIsThere() {
        val r = step(snap(PlaybackState.STATE_PAUSED, positionMs = 100_200), Item.OURS, armedAt(StartPosition.MIDDLE))
        assertTrue(r.action is Action.Play)
    }

    @Test
    fun thirtySecondsIsTheTargetForS30() {
        val ctx = armedAt(StartPosition.S30).copy(ownPauses = 1, pauses = 1, pausedSinceMs = 900)
        assertEquals(Action.Seek(30_000L, "to the start offset"), step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, ctx).action)
    }

    @Test
    fun tenSecondsIsTheTargetForS10() {
        val ctx = armedAt(StartPosition.S10).copy(ownPauses = 1, pauses = 1, pausedSinceMs = 900)
        assertEquals(Action.Seek(10_000L, "to the start offset"), step(snap(PlaybackState.STATE_PAUSED, positionMs = 0), Item.OURS, ctx).action)
    }
}
