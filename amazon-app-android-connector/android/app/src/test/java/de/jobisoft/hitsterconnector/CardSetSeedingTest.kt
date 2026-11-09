package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.util.CardSetSeeding
import de.jobisoft.hitsterconnector.util.SeedAction
import de.jobisoft.hitsterconnector.util.SeedDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The seeding rule of the bundled card sets, one file at a time, and the same rule over a whole phone. */
class CardSetSeedingTest {

    private val v1 = CardSetSeeding.sha256("version 1".toByteArray())
    private val v2 = CardSetSeeding.sha256("version 2".toByteArray())

    @Test
    fun aNewPhoneGetsTheFileCopiedAndRecorded() {
        assertEquals(SeedDecision(SeedAction.COPY, v1), CardSetSeeding.decide(v1, localExists = false, recordedHash = null))
    }

    @Test
    fun anUpdatedBundledFileOverwritesTheLocalOneAndIsRecorded() {
        assertEquals(SeedDecision(SeedAction.OVERWRITE, v2), CardSetSeeding.decide(v2, localExists = true, recordedHash = v1))
    }

    @Test
    fun anUnchangedBundledFileIsNotWrittenAgain() {
        assertEquals(SeedDecision(SeedAction.LEAVE, null), CardSetSeeding.decide(v1, localExists = true, recordedHash = v1))
    }

    @Test
    fun aFileTheUserDeletedStaysDeletedEvenIfTheBundledFileChanged() {
        assertEquals(SeedDecision(SeedAction.LEAVE, null), CardSetSeeding.decide(v1, localExists = false, recordedHash = v1))
        assertEquals(SeedDecision(SeedAction.LEAVE, null), CardSetSeeding.decide(v2, localExists = false, recordedHash = v1))
    }

    @Test
    fun aPhoneFromTheOldMarkerHasNoRecordAndGetsItsFilesRewrittenOnce() {
        // the old copy of hitster-de-aaaa0075.csv has no record: overwritten, so the corrected years arrive
        assertEquals(SeedDecision(SeedAction.OVERWRITE, v2), CardSetSeeding.decide(v2, localExists = true, recordedHash = null))
    }

    @Test
    fun aLocalFileThatWasReplacedIsOverwrittenWhenTheBundledFileChanges() {
        // the sets are app-owned, the corrections of the user are in card_overrides.csv: nothing is lost
        assertEquals(SeedAction.OVERWRITE, CardSetSeeding.decide(v2, localExists = true, recordedHash = v1).action)
    }

    /** The loop of CSVFileManager over a phone: only the bundled names are looked at, a foreign file is never touched. */
    private fun seed(
        assets: Map<String, String>, local: MutableMap<String, String>, record: MutableMap<String, String>
    ): List<String> {
        val written = ArrayList<String>()
        for ((name, content) in assets) {
            val hash = CardSetSeeding.sha256(content.toByteArray())
            val d = CardSetSeeding.decide(hash, name in local, record[name])
            if (d.action != SeedAction.LEAVE) { local[name] = content; written += name }
            d.record?.let { record[name] = it }
        }
        return written
    }

    @Test
    fun overAWholePhone() {
        val local = HashMap<String, String>()
        val record = HashMap<String, String>()
        val v1Assets = mapOf("a.csv" to "a1", "b.csv" to "b1")

        // new phone: both copied
        assertEquals(listOf("a.csv", "b.csv"), seed(v1Assets, local, record))
        // the user downloads a foreign file
        local["downloaded.csv"] = "from the web"
        // next start, nothing changed: no write
        assertEquals(emptyList<String>(), seed(v1Assets, local, record))
        // the user deletes b.csv
        local.remove("b.csv")
        assertEquals(emptyList<String>(), seed(v1Assets, local, record))
        assertEquals(null, local["b.csv"])
        // the app update changes a.csv and b.csv
        assertEquals(listOf("a.csv"), seed(mapOf("a.csv" to "a2", "b.csv" to "b2"), local, record))
        assertEquals("a2", local["a.csv"])
        assertEquals("b.csv stays deleted", null, local["b.csv"])
        assertEquals("a foreign file is never touched", "from the web", local["downloaded.csv"])
    }

    @Test
    fun aPhoneFromTheOldMarkerOverAllBundledFiles() {
        // old marker, no record: the local files exist
        val local = hashMapOf("a.csv" to "old a", "b.csv" to "same b", "mine.csv" to "mine")
        val record = HashMap<String, String>()
        val written = seed(mapOf("a.csv" to "corrected a", "b.csv" to "same b"), local, record)
        assertEquals("all bundled files are rewritten once", listOf("a.csv", "b.csv"), written)
        assertEquals("corrected a", local["a.csv"])
        assertEquals("mine", local["mine.csv"])
        assertNotEquals(null, record["a.csv"])
        // the second start writes nothing
        assertEquals(emptyList<String>(), seed(mapOf("a.csv" to "corrected a", "b.csv" to "same b"), local, record))
    }
}
