package de.jobisoft.amster

import de.jobisoft.amster.data.local.CardSetNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CardSetNamesTest {

    private val bundled = File("../../../assets/card-data").listFiles { f -> f.extension == "csv" }!!.sortedBy { it.name }

    private fun nameOf(file: String) = CardSetNames.editionOf(File("../../../assets/card-data/$file"))

    @Test
    fun knownFilesHaveTheirName() {
        assertEquals("Hitster Deutschland", nameOf("hitster-de.csv"))
        assertEquals("Hitster Deutschland Rock", nameOf("hitster-de-aaaa0039.csv"))
        assertEquals("Hitster Deutschland 2025", nameOf("hitster-de-aaaa0075.csv"))
        assertEquals("Hitster Deutschland Movies & TV Soundtracks Expansion Pack", nameOf("hitster-de-aaaa0026.csv"))
        assertEquals("Hitster Deutschland Celebration", nameOf("hitster-de-aaaa0040.csv"))
    }

    @Test
    fun everyBundledCardSetHasAName() {
        assertTrue(bundled.isNotEmpty())
        for (file in bundled) assertTrue("no Edition in ${file.name}", !CardSetNames.editionOf(file).isNullOrBlank())
    }

    @Test
    fun theFirstFilledValueWinsAndOtherColumnOrdersWork() {
        val csv = "Edition,Card#,Artist,Title,Year\n,1,A,T1,1990\nMy Set,2,B,T2,1991\nOther,3,C,T3,1992\n"
        assertEquals("My Set", CardSetNames.editionOf(csv))
    }

    @Test
    fun noColumnOrNoValueGivesNoName() {
        assertNull(CardSetNames.editionOf("Card#,Title,Artist,Year\n1,T,A,1990\n"))
        assertNull(CardSetNames.editionOf("Card#,Title,Artist,Year,Edition\n1,T,A,1990,\n"))
        assertNull(CardSetNames.editionOf("not a card set"))
    }

    @Test
    fun fallbackIsTheFileNameWithoutCsv() {
        assertEquals("my-own-set", CardSetNames.fallback("my-own-set.csv"))
        assertEquals("no-extension", CardSetNames.fallback("no-extension"))
    }
}
