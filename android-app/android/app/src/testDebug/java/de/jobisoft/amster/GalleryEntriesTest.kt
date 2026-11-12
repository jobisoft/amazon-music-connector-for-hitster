package de.jobisoft.amster

import de.jobisoft.amster.gallery.galleryEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens gallery of the debug app is plain data: every entry has an own id, a name and a group. */
class GalleryEntriesTest {
    @Test
    fun theIdsAreUnique() {
        assertEquals(galleryEntries.size, galleryEntries.map { it.id }.toSet().size)
    }

    @Test
    fun everyEntryHasANameAndAGroup() {
        assertTrue(galleryEntries.all { it.name.isNotBlank() && it.group.isNotBlank() })
    }

    @Test
    fun theIdsCanBeUsedInARoute() {
        assertTrue(galleryEntries.all { Regex("[a-z0-9-]+").matches(it.id) })
    }

    @Test
    fun theGroupsStayTogether() {
        val order = galleryEntries.map { it.group }.fold(emptyList<String>()) { acc, g -> if (acc.lastOrNull() == g) acc else acc + g }
        assertEquals("a group appears in one block of the list", order.size, order.toSet().size)
    }
}
