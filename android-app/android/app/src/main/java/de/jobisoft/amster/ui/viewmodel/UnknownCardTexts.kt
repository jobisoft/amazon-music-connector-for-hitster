package de.jobisoft.amster.ui.viewmodel

/** What Play Mode says about a code it cannot play: the lines of the screen, nothing is sent to Amazon Music. */
data class UnknownScan(val title: String, val lines: List<String>)

object UnknownCardTexts {
    /** A card of a Hitster set that the app does not have. [setName] is the name of its set, or its file name. [setKnown]: the set is on the phone. */
    fun card(number: Int, setName: String, setKnown: Boolean): UnknownScan = UnknownScan(
        "Unknown card $number",
        if (setKnown) listOf(setName, "You can manually add this card to the set in Reader Mode.")
        else listOf(setName, "This card set is not on this phone.", "You can check the project's GitHub page for the set file, or manually add the card in Reader Mode.")
    )

    /** A code that is no Hitster card at all. */
    fun notHitster(): UnknownScan = UnknownScan("Unknown QR code", listOf("This is not a Hitster card."))
}
