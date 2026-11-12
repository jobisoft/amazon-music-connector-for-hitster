package de.jobisoft.amster

import android.app.SearchManager
import android.provider.MediaStore
import de.jobisoft.amster.data.repository.CSVRepository
import de.jobisoft.amster.data.repository.CSVSong
import de.jobisoft.amster.util.CardOverride
import de.jobisoft.amster.util.CorrectionLogic
import de.jobisoft.amster.util.SearchExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AlbumColumnTest {
    private fun albums(csv: String) = CSVRepository.parseCSV(csv).songsByIndex.mapValues { it.value.album }

    @Test
    fun theAlbumColumnIsReadAndAnEmptyOneIsNull() {
        val csv = """
            Card#,Title,Artist,Year,AmazonID,Album
            8,Machinehead,Bush,1994,B071F4K9SQ,Sixteen Stone
            9,Rehab,Amy Winehouse,2006,,
        """.trimIndent()
        assertEquals(mapOf(8 to "Sixteen Stone", 9 to null), albums(csv))
    }

    @Test
    fun withoutTheColumnThereIsNoAlbum() {
        val csv = """
            Card#,Title,Artist,Year,AmazonID
            8,Machinehead,Bush,1994,B071F4K9SQ
        """.trimIndent()
        assertEquals(mapOf(8 to null), albums(csv))
    }

    @Test
    fun anAlbumWithACommaAndQuotesIsRead() {
        val csv = "Card#,Title,Artist,Year,AmazonID,Album\n" +
            "76,\"Rigoletto: \"\"La Donna È Mobile\"\"\",Enrico Caruso,1908,B0033NS2XS,\"Caruso, Vol. 1\"\n"
        assertEquals(mapOf(76 to "Caruso, Vol. 1"), albums(csv))
    }

    @Test
    fun theSearchCarriesTheAlbumOnlyIfThereIsOne() {
        val withAlbum = SearchExtras.fields("Machinehead", "Bush", "Sixteen Stone").toMap()
        assertEquals("Sixteen Stone", withAlbum[MediaStore.EXTRA_MEDIA_ALBUM])
        assertEquals("Machinehead", withAlbum[MediaStore.EXTRA_MEDIA_TITLE])
        assertEquals("Bush", withAlbum[MediaStore.EXTRA_MEDIA_ARTIST])
        assertEquals("Bush, Machinehead", withAlbum[SearchManager.QUERY])
        for (none in listOf(null, "", "   ")) {
            val fields = SearchExtras.fields("Machinehead", "Bush", none).toMap()
            assertFalse("no album field for '$none'", fields.containsKey(MediaStore.EXTRA_MEDIA_ALBUM))
            assertEquals("Bush, Machinehead", fields[SearchManager.QUERY])
        }
    }

    @Test
    fun theAlbumNameIsTrimmedAndTheQueryStaysArtistCommaTitle() {
        val fields = SearchExtras.fields("Rehab", "Amy Winehouse", "  Back to Black ").toMap()
        assertEquals("Back to Black", fields[MediaStore.EXTRA_MEDIA_ALBUM])
        assertEquals("Amy Winehouse, Rehab", fields[SearchManager.QUERY])
    }

    @Test
    fun aCorrectedCardKeepsTheAlbumOfTheSet() {
        val song = CSVSong(8, "Machinehead", "Bush", 1994, "B071F4K9SQ", "Sixteen Stone")
        val fixed = CorrectionLogic.apply(song, CardOverride("hitster-de-aaaa0039.csv", 8, 1995, "Other", "Someone", "t1", "reader"))
        assertEquals("a correction does not touch the album, the data stays as it was saved", "Sixteen Stone", fixed.album)
        assertEquals("B071F4K9SQ", fixed.amazonId)
    }
}
