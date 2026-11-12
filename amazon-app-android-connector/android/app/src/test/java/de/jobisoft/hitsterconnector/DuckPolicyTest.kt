package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.DuckPolicy
import de.jobisoft.hitsterconnector.util.DuckPolicy.OnDuck
import de.jobisoft.hitsterconnector.util.DuckState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckPolicyTest {
    @Test
    fun aLevelAboveZeroIsDucked() {
        assertEquals(OnDuck.DUCK, DuckPolicy.onDuck(enabled = true, current = 7, pending = null))
        // the user raised the volume while an older duck was still pending: the new level is the one to keep
        assertEquals(OnDuck.DUCK, DuckPolicy.onDuck(enabled = true, current = 5, pending = 9))
    }

    @Test
    fun theSettingOffMeansNothingIsDone() {
        assertEquals(OnDuck.OFF, DuckPolicy.onDuck(enabled = false, current = 7, pending = null))
        assertEquals("also with a duck of an older run pending", OnDuck.OFF, DuckPolicy.onDuck(enabled = false, current = 0, pending = 9))
    }

    @Test
    fun restoreDoesNotDependOnTheSetting() {
        // shouldRestore has no setting parameter: a duck that is still pending is restored with the setting off too
        assertTrue(DuckPolicy.shouldRestore(current = 0, saved = 9))
    }

    @Test
    fun aLevelOfZeroThatIsNotOursIsLeftAlone() {
        assertEquals(OnDuck.SKIP, DuckPolicy.onDuck(enabled = true, current = 0, pending = null))
    }

    @Test
    fun aLevelOfZeroThatWeSetKeepsTheRememberedLevel() {
        assertEquals(OnDuck.KEEP, DuckPolicy.onDuck(enabled = true, current = 0, pending = 9))
    }

    @Test
    fun restoreOnlyWhenTheLevelIsStillZero() {
        assertTrue(DuckPolicy.shouldRestore(current = 0, saved = 9))
        assertFalse("the user set a level meanwhile", DuckPolicy.shouldRestore(current = 4, saved = 9))
        assertFalse("nothing was remembered", DuckPolicy.shouldRestore(current = 0, saved = null))
        assertFalse("a remembered 0 is nothing to restore", DuckPolicy.shouldRestore(current = 0, saved = 0))
    }

    @Test
    fun theStateGivesTheLevelBackOnlyOnce() {
        val state = DuckState()
        assertNull(state.pending)
        state.remember(8)
        assertEquals(8, state.pending)
        assertEquals(8, state.take())
        assertNull("the second restore finds nothing", state.take())
        assertNull(state.pending)
    }

    @Test
    fun theStateCanStartWithALevelFromDisk() {
        val state = DuckState(initial = 6)
        assertEquals(6, state.take())
    }

    @Test
    fun theRememberedLevelIsReplacedByANewDuck() {
        val state = DuckState()
        state.remember(8)
        state.remember(5)
        assertEquals(5, state.take())
    }

    @Test
    fun noStartPositionMeansNothingIsDucked() {
        assertEquals(OnDuck.NO_START, DuckPolicy.onDuck(enabled = true, current = 7, pending = null, startPositionOff = true))
        assertEquals(OnDuck.NO_START, DuckPolicy.onDuck(enabled = true, current = 0, pending = 9, startPositionOff = true))
    }

    @Test
    fun theSwitchOffComesBeforeTheStartPosition() {
        assertEquals(OnDuck.OFF, DuckPolicy.onDuck(enabled = false, current = 7, pending = null, startPositionOff = true))
    }

    @Test
    fun aStartPositionOtherThanOffChangesNothing() {
        assertEquals(OnDuck.DUCK, DuckPolicy.onDuck(enabled = true, current = 7, pending = null, startPositionOff = false))
    }
}
