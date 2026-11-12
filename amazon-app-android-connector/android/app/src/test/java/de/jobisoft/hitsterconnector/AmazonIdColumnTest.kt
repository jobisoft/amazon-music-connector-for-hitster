package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AmazonIdColumnTest {
    private fun ids(csv: String) = CSVRepository.parseCSV(csv).songsByIndex.mapValues { it.value.amazonId }

    @Test
    fun theColumnGivesTheIdOfAnEmptyOneNull() {
        val csv = """
            Card#,Title,Artist,Year,AmazonID
            103,Rehab,Amy Winehouse,2006,B006LSV05O
            104,Blue Monday,New Order,1983,
        """.trimIndent()
        assertEquals(mapOf(103 to "B006LSV05O", 104 to null), ids(csv))
    }

    @Test
    fun withoutTheColumnThereIsNoId() {
        val csv = """
            Card#,Title,Artist,Year
            103,Rehab,Amy Winehouse,2006
        """.trimIndent()
        assertEquals(mapOf(103 to null), ids(csv))
    }

    @Test
    fun theHeaderNameIgnoresTheCaseAndTheColumnCanBeAnywhere() {
        val csv = """
            Card#,amazonid,Artist,Title,Year
            103,B006LSV05O,Amy Winehouse,Rehab,2006
        """.trimIndent()
        assertEquals(mapOf(103 to "B006LSV05O"), ids(csv))
    }

    @Test
    fun quotedAndPaddedFieldsAreRead() {
        val csv = """
            Card#,Title,Artist,Year,AmazonID
            7,"Hello, Goodbye",The Beatles,1967," B0ABCDEFGH "
        """.trimIndent()
        val song = CSVRepository.parseCSV(csv).songsByIndex.getValue(7)
        assertEquals("Hello, Goodbye", song.title)
        assertEquals("B0ABCDEFGH", song.amazonId)
    }

    @Test
    fun theIdOfRehabInTheOriginalEditionIsSet() {
        val songs = CSVRepository.parseCSV(File("../../../card-sets/assets/csv/hitster-de-aaaa0075.csv").readText()).songsByIndex
        assertEquals("B006LSV05O", songs.getValue(103).amazonId)
    }
}
