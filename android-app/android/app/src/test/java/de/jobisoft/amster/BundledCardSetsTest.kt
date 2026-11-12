package de.jobisoft.amster

import de.jobisoft.amster.data.repository.CSVRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every card set file that is bundled with the app has the five columns, followed by Album and Edition, and clean rows. */
class BundledCardSetsTest {
    private val files = File("../../../assets/card-data").listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }

    /** The fields of a line, quotes and doubled quotes as in RFC 4180 (the bundled files have no line breaks inside a field). */
    private fun fields(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    @Test
    fun allSixteenCardSetsAreThere() {
        assertEquals(16, files.size)
    }

    @Test
    fun everyFileHasTheFiveColumnsAndCleanRows() {
        for (file in files) {
            val lines = file.readText().lines().filter { it.isNotBlank() }
            val header = fields(lines.first())
            assertEquals(file.name, listOf("Card#", "Title", "Artist", "Year", "AmazonID"), header.take(5))
            assertEquals(file.name, listOf("Album", "Edition"), header.drop(5))
            val rows = lines.drop(1)
            assertTrue("${file.name} has rows", rows.isNotEmpty())
            val numbers = HashSet<Int>()
            for (row in rows) {
                val f = fields(row)
                assertEquals("${file.name}: $row", header.size, f.size)
                val card = f[0].toIntOrNull()
                assertTrue("${file.name}: card number in $row", card != null && card > 0)
                assertTrue("${file.name}: card $card twice", numbers.add(card!!))
                assertTrue("${file.name}: title in $row", f[1].isNotBlank())
                assertTrue("${file.name}: artist in $row", f[2].isNotBlank())
                assertTrue("${file.name}: year in $row", Regex("^\\d{4}$").matches(f[3]))
                assertTrue("${file.name}: Amazon id in $row", f[4].isEmpty() || Regex("^B0[A-Z0-9]{8}$").matches(f[4]))
            }
            assertEquals("${file.name}: rows", rows.size, numbers.size)
            val names = rows.map { fields(it)[6] }
            assertTrue("${file.name}: the first row has the name", names.first().isNotBlank())
            assertTrue("${file.name}: only the first row has the name", names.drop(1).all { it.isEmpty() })
        }
    }

    @Test
    fun theAppParsesEveryRowOfEveryFile() {
        for (file in files) {
            val rows = file.readText().lines().filter { it.isNotBlank() }.size - 1
            val songs = CSVRepository.parseCSV(file.readText()).songsByIndex.size
            assertEquals(file.name, rows, songs)
        }
    }
}
