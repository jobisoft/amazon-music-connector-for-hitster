package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.AmazonId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmazonIdTest {
    @Test
    fun mediaIdWrapsTheAsin() {
        assertEquals("asin://B006LSV05O/", AmazonId.mediaId("B006LSV05O"))
    }

    @Test
    fun playUriIsTheWebAddressOfTheTrackWithTheAgentParameter() {
        assertEquals("https://music.amazon.de/tracks/B006LSV05O?agent=google", AmazonId.playUri("B006LSV05O", "de"))
        assertEquals("https://music.amazon.co.uk/tracks/B006LSV05O?agent=google", AmazonId.playUri("B006LSV05O", "co.uk"))
        assertEquals("https://music.amazon.com/tracks/B001SLQMJ8?agent=google", AmazonId.playUri("B001SLQMJ8", "com"))
    }

    @Test
    fun aBareIdIsTakenInUpperCase() {
        assertEquals("B006LSV05O", AmazonId.fromInput("B006LSV05O"))
        assertEquals("B006LSV05O", AmazonId.fromInput("b006lsv05o"))
        assertEquals("B006LSV05O", AmazonId.fromInput("  B006LSV05O \n"))
    }

    @Test
    fun anIdIsTakenFromALink() {
        assertEquals("B006LSV05O", AmazonId.fromInput("https://music.amazon.de/tracks/B006LSV05O?do=play"))
        assertEquals("B006LSV05O", AmazonId.fromInput("https://music.amazon.com/tracks/b006lsv05o"))
        assertEquals("B006LSV05O", AmazonId.fromInput("https://music.amazon.de/albums/B006LSUZVY?trackAsin=B006LSV05O"))
        assertEquals("B006LSV05O", AmazonId.fromInput("music.amazon.de/albums/B006LSUZVY/?trackAsin=B006LSV05O&ref=x"))
    }

    @Test
    fun blankMeansNoIdAndJunkIsNull() {
        assertEquals("", AmazonId.fromInput(""))
        assertEquals("", AmazonId.fromInput("   "))
        assertNull(AmazonId.fromInput("hello"))
        assertNull(AmazonId.fromInput("B006LSV05"))
        assertNull(AmazonId.fromInput("B006LSV05OX"))
        assertNull(AmazonId.fromInput("A006LSV05O"))
        assertNull("an album link has no track", AmazonId.fromInput("https://music.amazon.de/albums/B006LSUZVY"))
        assertNull(AmazonId.fromInput("https://music.amazon.de/artists/B001Q9VJFY"))
    }
}
