package de.jobisoft.amster

import de.jobisoft.amster.util.QuizPicker
import de.jobisoft.amster.util.SongIdentity
import de.jobisoft.amster.util.TitleMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatchTest {
    @Test
    fun caseAccentsBracketsAndPunctuationDoNotCount() {
        assertEquals("cafedelmar", TitleMatch.normalize("Café del Mar [Explicit]"))
        assertEquals("dontstopmenow", TitleMatch.normalize("Don't Stop Me Now (Remastered 2011)"))
        assertTrue(TitleMatch.same("Wahnsinn (2021 Remastered)", "Wahnsinn"))
        assertTrue(TitleMatch.same("No Son of Mine (2007 Remaster)", "No Son Of Mine"))
    }

    @Test
    fun oneTitleMayContainTheOther() {
        assertTrue(TitleMatch.same("Heroes - 2017 Remaster", "Heroes"))
        assertTrue(TitleMatch.same("Heroes", "Heroes - 2017 Remaster"))
        assertFalse(TitleMatch.same("Heroes", "Villains"))
    }

    @Test
    fun nothingShownOrNothingToCompareIsNotTheSameSong() {
        assertFalse(TitleMatch.same(null, "Heroes"))
        assertFalse(TitleMatch.same("", "Heroes"))
        assertFalse(TitleMatch.same("Heroes", "(Live)"))
    }

    // The two ways of comparing titles are not the same, this pins the difference: the quiz and the guard drop a
    // trailing " - ... Remaster/Version/Edit..." suffix, the classification of a search result does not.
    @Test
    fun theSuffixOfAVersionIsOnlyDroppedByTheQuizAndTheGuard() {
        assertFalse(TitleMatch.same("Heroes - Radio Edit", "Heroes - Single Version"))
        assertTrue(SongIdentity.same(null, "Heroes - Radio Edit", null, "Heroes - Single Version"))
        assertEquals("heroes", QuizPicker.normalize("Heroes - 2017 Remaster"))
        assertEquals("heroes2017remaster", TitleMatch.normalize("Heroes - 2017 Remaster"))
    }
}
