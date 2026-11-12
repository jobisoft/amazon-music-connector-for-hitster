package de.jobisoft.hitsterconnector.reader

/** Where a "Send to Amazon Music app" of the reader stands. */
enum class SendStatus { IDLE, SENDING, PLAYING, FAILED }

/** The rules of the "Send to Amazon Music app" button of the reader, without Android. */
object SendToAmazon {
    /** Why the button is off: the short reason shown under it, or null if Amazon Music is ready. */
    fun problem(installed: Boolean, notificationAccess: Boolean): String? = when {
        !installed -> "The Amazon Music app is not installed."
        !notificationAccess -> "Notification access is not granted (Home screen, Setup)."
        else -> null
    }

    /** A scan that is not dropped stops the song that the reader's button started (loading or playing). Nothing started here: nothing to stop. */
    fun stopsOnScan(status: SendStatus): Boolean = status == SendStatus.SENDING || status == SendStatus.PLAYING

    /** The button works with a card on the screen, Amazon Music ready, and no send under way. */
    fun enabled(hasCard: Boolean, problem: String?, status: SendStatus): Boolean =
        hasCard && problem == null && status != SendStatus.SENDING

    /** The one line under the buttons: what the player does, "" for nothing to say. An error is shown as a red box instead. */
    fun statusLine(status: SendStatus, songEnded: Boolean, playStatus: String? = null): String {
        val base = baseLine(status, songEnded)
        return when {
            status == SendStatus.SENDING && playStatus != null -> "$base\n$playStatus"
            else -> base
        }
    }

    private fun baseLine(status: SendStatus, songEnded: Boolean): String = when {
        status == SendStatus.SENDING -> "Sending card info to the Amazon Music app ..."
        status == SendStatus.PLAYING && songEnded -> "Song ended. Send the next card or leave."
        status == SendStatus.PLAYING -> "Playing"
        else -> ""
    }
}
