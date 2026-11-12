package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The layouts of the card set files the app reads: the slim files of this repository and the upstream files
 * of SongSeeker (more columns, other orders), as the user imports them in Card Config. The columns are
 * found by name, the others are ignored.
 */
class CardSetFormatsTest {
    private fun songs(csv: String) = CSVRepository.parseCSV(csv).songsByIndex

    private fun check(csv: String, expectedYear: Int) {
        val songs = songs(csv)
        assertEquals(setOf(103, 104), songs.keys)
        val rehab = songs.getValue(103)
        assertEquals("Rehab", rehab.title)
        assertEquals("Amy Winehouse", rehab.artist)
        assertEquals(expectedYear, rehab.year)
        assertNull(rehab.amazonId)
        val quoted = songs.getValue(104)
        assertEquals("Hello, Goodbye", quoted.title)
        assertEquals("The Beatles", quoted.artist)
        assertEquals(1967, quoted.year)
        assertNull(quoted.amazonId)
    }

    @Test
    fun theSlimLayoutWithoutAnId() {
        check(
            """
            Card#,Title,Artist,Year
            103,Rehab,Amy Winehouse,2006
            104,"Hello, Goodbye",The Beatles,1967
            """.trimIndent(), 2006
        )
    }

    @Test
    fun theUpstreamLayoutWithIsrc() {
        check(
            """
            Card#,Title,Artist,Year,URL,Hashed Info,Youtube-Title,ISRC
            103,Rehab,Amy Winehouse,2006,https://www.youtube.com/watch?v=t2TKqJ2FXGM,2855,Rehab,GBUM70602904
            104,"Hello, Goodbye",The Beatles,1967,https://www.youtube.com/watch?v=abc,1234,Hello Goodbye,GBAYE0601477
            """.trimIndent(), 2006
        )
    }

    @Test
    fun theUpstreamLayoutWithArtistFirstAndTheYearLast() {
        check(
            """
            Card#,Artist,Title,URL,Hashed Info,Youtube-Title,Year
            103,Amy Winehouse,Rehab,https://www.youtube.com/watch?v=t2TKqJ2FXGM,2855,Rehab,2006
            104,The Beatles,"Hello, Goodbye",https://www.youtube.com/watch?v=abc,1234,Hello Goodbye,1967
            """.trimIndent(), 2006
        )
    }

    @Test
    fun theNordicsLayoutWithTheCardNumberInTheFifthColumn() {
        check(
            """
            Title,Artist,Album,URL,Card#,Year,Youtube-Title,Hashed Info
            Rehab,Amy Winehouse,Back to Black,https://www.youtube.com/watch?v=t2TKqJ2FXGM,103,2006,Rehab,2855
            "Hello, Goodbye",The Beatles,Magical Mystery Tour,https://www.youtube.com/watch?v=abc,104,1967,Hello Goodbye,1234
            """.trimIndent(), 2006
        )
    }

    @Test
    fun aListWithTheCardsDownwardsKeepsTheCardNumbers() {
        val songs = songs(
            """
            Card#,Title,Artist,Year,AmazonID
            104,B,Two,1990,
            103,A,One,1980,B006LSV05O
            """.trimIndent()
        )
        assertEquals("A", songs.getValue(103).title)
        assertEquals("B", songs.getValue(104).title)
    }

    @Test
    fun theNamesOfTheColumnsDoNotNeedTheCaseAndTheColumnsCanBeAnywhere() {
        val songs = songs(
            """
            extra,ARTIST,year,title,Card#,more,amazonid
            x,Amy Winehouse,2006,Rehab,103,y,B006LSV05O
            """.trimIndent()
        )
        val rehab = songs.getValue(103)
        assertEquals("Rehab", rehab.title)
        assertEquals("Amy Winehouse", rehab.artist)
        assertEquals(2006, rehab.year)
        assertEquals("B006LSV05O", rehab.amazonId)
    }

    @Test
    fun unknownColumnsAreIgnoredAndTheIdMayBeEmpty() {
        val songs = songs(
            """
            Card#,Title,Artist,Whatever,Year,URL
            103,Rehab,Amy Winehouse,anything,2006,not read
            104,Hello,The Beatles,else,1967,
            """.trimIndent()
        )
        assertEquals(2006, songs.getValue(103).year)
        assertEquals(1967, songs.getValue(104).year)
        assertNull(songs.getValue(104).amazonId)
    }

    @Test
    fun aRowWithoutANumberATitleAnArtistOrAYearRejectsTheWholeFile() {
        val rows = listOf("x,No Number,A,1990", "2,,No Title,1990", "3,No Artist,,1990", "4,No Year,A,", "5,Bad Year,A,soon")
        for (bad in rows) {
            val error = try {
                songs("Card#,Title,Artist,Year\n1,Fine,Artist,1990\n$bad")
                null
            } catch (e: Exception) {
                e.message
            }
            assertEquals(bad, String.format(CSVRepository.MISSING_VALUES, 3), error)
        }
        assertEquals(setOf(1, 4), songs("Card#,Title,Artist,Year\n1,Fine,Artist,1990\n\n4,Fine,Artist,1990\n").keys)
    }

    @Test
    fun aByteOrderMarkBeforeTheHeaderIsIgnored() {
        assertEquals(setOf(1), songs("\uFEFFCard#,Title,Artist,Year\n1,A,B,1990").keys)
    }

    @Test
    fun aFileWithoutTheHeaderColumnsGivesAClearError() {
        for (csv in listOf(
            "103,Rehab,Amy Winehouse,2006,https://www.youtube.com/watch?v=t2TKqJ2FXGM", // no header at all
            "number,song,by,when\n1,Rehab,Amy Winehouse,2006",
            "Card#,Title,Year\n1,Rehab,2006", // no artist
            "Card#,Title,Artist\n1,Rehab,Amy Winehouse", // no year
            "Title,Artist,Year\nRehab,Amy Winehouse,2006", // no card number
            ""
        )) {
            val error = try {
                songs(csv)
                null
            } catch (e: Exception) {
                e.message
            }
            assertEquals(csv, CSVRepository.MISSING_COLUMNS, error)
        }
    }
}
