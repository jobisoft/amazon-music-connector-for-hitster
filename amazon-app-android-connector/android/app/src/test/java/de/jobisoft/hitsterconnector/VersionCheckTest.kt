package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.VersionCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCheckTest {
    private fun sus(wanted: String, shown: String?) = VersionCheck.suspicious(wanted, shown)

    @Test
    fun rehabRemixIsSuspicious() {
        assertTrue(sus("Rehab", "Rehab (Pharoahe Monch Remix) [Explicit]"))
    }

    @Test
    fun harmlessAdditionsAreFine() {
        assertFalse(sus("Rehab", "Rehab"))
        assertFalse(sus("Rehab", "Rehab (Remastered)"))
        assertFalse(sus("Rehab", "Rehab - Remastered 2011"))
        assertFalse(sus("Rehab", "Rehab (Single Version)"))
        assertFalse(sus("Rehab", "Rehab (Album Version) [Explicit]"))
        assertFalse(sus("Rehab", "Rehab (Radio Edit)"))
        assertFalse(sus("Rehab", "Rehab (Mono)"))
        assertFalse(sus("Rehab", "Rehab (2011 Stereo)"))
        assertFalse(sus("Rehab", "Rehab (feat. Someone)"))
        assertFalse(sus("Rehab", null))
    }

    @Test
    fun singleVersionInSquareBracketsWithYearIsHarmless() {
        assertFalse(sus("Rehab", "Rehab [Single Version, 2006]"))
        assertFalse(sus("Rehab", "Rehab [Single Version]"))
        assertTrue(sus("Rehab", "Rehab [Single Version, 2006] (Remix)"))
    }

    @Test
    fun mixIsAWordOnly() {
        assertTrue(sus("Song", "Song (Club Mix)"))
        assertTrue(sus("Song", "Song - Dub Mix"))
        assertFalse(sus("Song", "Song (Mixtape Edit)"))
        assertFalse(sus("Mixed Emotions", "Mixed Emotions"))
    }

    @Test
    fun otherMarkers() {
        assertTrue(sus("Song", "Song (Live at Wembley)"))
        assertTrue(sus("Song", "Song - Live"))
        assertTrue(sus("Song", "Song (Karaoke Version)"))
        assertTrue(sus("Song", "Song (Instrumental)"))
        assertTrue(sus("Song", "Song (Acoustic)"))
        assertTrue(sus("Song", "Song (Sped Up)"))
        assertTrue(sus("Song", "Song (Lullaby Version)"))
        assertTrue(sus("Song", "Song (Originally Performed by X)"))
        assertTrue(sus("Song", "Song (In the Style of X)"))
        assertTrue(sus("Song", "Song (Tribute to X)"))
    }

    @Test
    fun markerInTheWantedTitleIsExcused() {
        assertFalse(sus("Live and Let Die", "Live and Let Die (Remastered)"))
        assertFalse(sus("Song (Live)", "Song (Live)"))
        assertFalse(sus("Live Forever", "Live Forever - Live"))
        assertTrue(sus("Song (Live)", "Song (Live) (Remix)"))
    }

    @Test
    fun titleWordsOutsideBracketsAreNotChecked() {
        assertFalse(sus("Cover Me", "Cover Me"))
        assertFalse(sus("Demo", "Demo"))
    }
}
