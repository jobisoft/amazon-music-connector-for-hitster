package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.local.CardSetNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CardSetNamesTest {

    private val names = CardSetNames.parse(File("../../../card-sets/assets/card_sets.csv").readText())

    @Test
    fun knownFilesHaveTheirGameName() {
        assertEquals("Hitster Deutschland", CardSetNames.displayName(names, "hitster-de.csv"))
        assertEquals("Hitster Deutschland Rock", CardSetNames.displayName(names, "hitster-de-aaaa0039.csv"))
        assertEquals("Hitster - Original", CardSetNames.displayName(names, "hitster-de-aaaa0075.csv"))
        assertEquals(
            "Hitster Deutschland Movies & TV Soundtracks Expansion Pack",
            CardSetNames.displayName(names, "hitster-de-aaaa0026.csv")
        )
        assertEquals("Hitster magyar kiadás", CardSetNames.displayName(names, "hitster-hu-aaae0003.csv"))
    }

    @Test
    fun unknownFileFallsBackToTheFileNameWithoutCsv() {
        assertEquals("my-own-set", CardSetNames.displayName(names, "my-own-set.csv"))
        assertEquals("no-extension", CardSetNames.displayName(names, "no-extension"))
        assertEquals("hitster-de", CardSetNames.displayName(emptyMap(), "hitster-de.csv"))
    }

    @Test
    fun everyBundledCardSetHasAName() {
        val bundled = File("../../../card-sets/assets/csv").listFiles { f -> f.extension == "csv" }!!.map { it.name }
        assertTrue(bundled.isNotEmpty())
        for (file in bundled) assertTrue("no name for $file", file in names)
        assertEquals(bundled.size, names.size)
    }

    @Test
    fun parserSkipsHeaderBlankLinesAndQuotes() {
        val parsed = CardSetNames.parse("File,Game\n\na.csv,\"Name A\"\nbroken line\nb.csv,Name B, with comma\n")
        assertEquals(mapOf("a.csv" to "Name A", "b.csv" to "Name B, with comma"), parsed)
    }
}
