package de.jobisoft.hitsterconnector

import de.jobisoft.hitsterconnector.ui.viewmodel.UnknownCardTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownCardTextsTest {
    @Test
    fun aMissingCardOfAKnownSetNamesTheCardAndPointsToReaderMode() {
        val s = UnknownCardTexts.card(42, "Hitster Deutschland", setKnown = true)
        assertEquals("Unknown card 42", s.title)
        assertEquals(listOf("Hitster Deutschland", "You can manually add this card to the set in Reader Mode."), s.lines)
    }

    @Test
    fun aSetThatIsNotOnThePhoneSaysSo() {
        val s = UnknownCardTexts.card(7, "hitster-xx", setKnown = false)
        assertTrue(s.lines.contains("This card set is not on this phone."))
        assertEquals("You can check the project's GitHub page for the set file, or manually add the card in Reader Mode.", s.lines.last())
    }

    @Test
    fun aForeignCodeIsNoHitsterCard() {
        assertEquals("Unknown QR code", UnknownCardTexts.notHitster().title)
    }
}
