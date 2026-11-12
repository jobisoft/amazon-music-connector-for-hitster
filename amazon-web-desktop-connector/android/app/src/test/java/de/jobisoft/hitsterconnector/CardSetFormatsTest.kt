package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layouts of the card set files the app reads: the slim files of this repository, and the upstream files
 * of SongSeeker (more columns, other orders) with their addresses, which find a card by its address as well.
 */
class CardSetFormatsTest {
    private fun parsed(csv: String) = CSVRepository.parseCSV(csv)

    private fun check(csv: String, urlLookup: Boolean) {
        val p = parsed(csv)
        assertEquals(setOf(103, 104), p.songsByIndex.keys)
        val rehab = p.songsByIndex.getValue(103)
        assertEquals("Rehab", rehab.title)
        assertEquals("Amy Winehouse", rehab.artist)
        assertEquals(2006, rehab.year)
        val quoted = p.songsByIndex.getValue(104)
        assertEquals("Hello, Goodbye", quoted.title)
        assertEquals("The Beatles", quoted.artist)
        assertEquals(1967, quoted.year)
        if (urlLookup) {
            assertEquals("Rehab", p.songsByUrl.getValue("https://www.youtube.com/watch?v=t2TKqJ2FXGM").title)
            assertEquals("Rehab", p.songsByUrl.getValue("youtube:t2TKqJ2FXGM").title)
            assertEquals("Hello, Goodbye", p.songsByUrl.getValue("youtube:abc").title)
        } else {
            assertTrue(p.songsByUrl.isEmpty())
        }
    }

    @Test
    fun theSlimLayoutWithAnAmazonIdColumnHasNoAddresses() {
        check(
            """
            Card#,Title,Artist,Year,AmazonID
            103,Rehab,Amy Winehouse,2006,B006LSV05O
            104,"Hello, Goodbye",The Beatles,1967,
            """.trimIndent(), urlLookup = false
        )
    }

    @Test
    fun theSlimLayoutWithoutTheIdColumnHasNoAddresses() {
        check(
            """
            Card#,Title,Artist,Year
            103,Rehab,Amy Winehouse,2006
            104,"Hello, Goodbye",The Beatles,1967
            """.trimIndent(), urlLookup = false
        )
    }

    @Test
    fun theUpstreamLayoutWithIsrc() {
        check(
            """
            Card#,Title,Artist,Year,URL,Hashed Info,Youtube-Title,ISRC
            103,Rehab,Amy Winehouse,2006,https://www.youtube.com/watch?v=t2TKqJ2FXGM,2855,Rehab,GBUM70602904
            104,"Hello, Goodbye",The Beatles,1967,https://www.youtube.com/watch?v=abc,1234,Hello Goodbye,GBAYE0601477
            """.trimIndent(), urlLookup = true
        )
    }

    @Test
    fun theUpstreamLayoutWithArtistFirstAndTheYearLast() {
        check(
            """
            Card#,Artist,Title,URL,Hashed Info,Youtube-Title,Year
            103,Amy Winehouse,Rehab,https://www.youtube.com/watch?v=t2TKqJ2FXGM,2855,Rehab,2006
            104,The Beatles,"Hello, Goodbye",https://www.youtube.com/watch?v=abc,1234,Hello Goodbye,1967
            """.trimIndent(), urlLookup = true
        )
    }

    @Test
    fun theNordicsLayoutWithTheCardNumberInTheFifthColumn() {
        check(
            """
            Title,Artist,Album,URL,Card#,Year,Youtube-Title,Hashed Info
            Rehab,Amy Winehouse,Back to Black,https://www.youtube.com/watch?v=t2TKqJ2FXGM,103,2006,Rehab,2855
            "Hello, Goodbye",The Beatles,Magical Mystery Tour,https://www.youtube.com/watch?v=abc,104,1967,Hello Goodbye,1234
            """.trimIndent(), urlLookup = true
        )
    }

    @Test
    fun aLegacyFileWithOtherHeaderNamesIsReadByPosition() {
        // number,title,artist,year,address: the names in the first line are not ours, the first line is skipped
        check(
            """
            number,song,by,when,link
            103,Rehab,Amy Winehouse,2006,https://www.youtube.com/watch?v=t2TKqJ2FXGM
            104,"Hello, Goodbye",The Beatles,1967,https://www.youtube.com/watch?v=abc
            """.trimIndent(), urlLookup = true
        )
    }

    @Test
    fun aListWithTheCardsDownwardsKeepsTheCardNumbers() {
        val p = parsed(
            """
            Card#,Title,Artist,Year,AmazonID
            104,B,Two,1990,
            103,A,One,1980,B006LSV05O
            """.trimIndent()
        )
        assertEquals("A", p.songsByIndex.getValue(103).title)
        assertNotNull(p.songsByIndex[104])
    }
}
