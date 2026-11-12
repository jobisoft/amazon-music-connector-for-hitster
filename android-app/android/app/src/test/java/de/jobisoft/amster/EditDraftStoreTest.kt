package de.jobisoft.amster

import de.jobisoft.amster.reader.EditDraft
import de.jobisoft.amster.reader.EditDraftStore
import de.jobisoft.amster.reader.OcrCard
import de.jobisoft.amster.reader.ReportOverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditDraftStoreTest {
    private fun draft(number: Int = 5) = EditDraft.of("Edit Card", "hitster-de-aaaa0075.csv", number, "Artist", 1999, "Title", null, null)

    @Test
    fun `an open draft means that an overlay is up, closing it ends that`() {
        val overlay = ReportOverlayState()
        val store = EditDraftStore(overlay)
        assertFalse(overlay.open.value)
        store.open(draft())
        assertTrue(overlay.open.value)
        store.close()
        assertFalse(overlay.open.value)
        assertNull(store.draft.value)
    }

    @Test
    fun `the OCR result goes into the open draft`() {
        val store = EditDraftStore(ReportOverlayState())
        store.open(draft())
        store.applyOcr(OcrCard("Other", 2001, "Song"))
        val d = store.draft.value!!
        assertEquals("Other", d.artist)
        assertEquals("2001", d.yearText)
        assertEquals("Song", d.title)
        assertTrue(d.ocrUsed)
    }

    @Test
    fun `an update for another card is ignored`() {
        val store = EditDraftStore(ReportOverlayState())
        store.open(draft(5))
        store.update(draft(6).copy(artist = "Changed"))
        assertEquals("Artist", store.draft.value!!.artist)
        store.update(draft(5).copy(artist = "Changed"))
        assertEquals("Changed", store.draft.value!!.artist)
    }

    @Test
    fun `an OCR result without an open overlay is dropped`() {
        val store = EditDraftStore(ReportOverlayState())
        store.applyOcr(OcrCard("Other", 2001, "Song"))
        assertNull(store.draft.value)
    }
}
