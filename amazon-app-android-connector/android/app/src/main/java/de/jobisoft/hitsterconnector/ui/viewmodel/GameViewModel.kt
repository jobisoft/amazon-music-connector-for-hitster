package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.data.local.CardSetNames
import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.domain.model.SongMapping
import de.jobisoft.hitsterconnector.reader.ReaderStore
import de.jobisoft.hitsterconnector.util.CSVUrlParser
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Scans a card, translates its QR code into title and artist (CSV files of the
 * songseeker-hitster-playlists project) and hands both to the connector.
 *
 * The title and the artist reach the screen only through the reveal box, which shows them
 * after a tap. Everywhere else they would spoil the game.
 */
@HiltViewModel
class GameViewModel @Inject constructor(
    private val csvRepository: CSVRepository,
    private val cardSetNames: CardSetNames,
    connectorRepository: ConnectorRepository,
    readerStore: ReaderStore
) : PlayerViewModel(connectorRepository, readerStore.saved) {

    /** What the lookup of a QR code found: the song and its set, or the reason why not. */
    private data class Lookup(val mapping: SongMapping?, val setFile: String?, val error: String?, val number: Int? = null)

    /** Process a scanned QR code URL. */
    fun onQRCodeScanned(qrCodeUrl: String) {
        // the scanner may deliver the same code several times in a row, the first one takes it
        if (state.sendState != SendState.IDLE || !markStopping()) return
        connectorRepository.markScan()
        viewModelScope.launch {
            // the song of the card before is stopped first, the camera stays until it is confirmed
            if (!stopThenBeginSending()) return@launch
            val lookup = findSongMapping(qrCodeUrl)
            val found = lookup.mapping
            if (found != null) {
                send(
                    found, lookup.setFile?.let { cardSetNames.displayName(it) }, isTestSong = false, warning = null,
                    cardRef = if (lookup.setFile != null && lookup.number != null) CardRef(lookup.setFile, lookup.number) else null
                )
            } else {
                // The card is not in any card set. Play a test song instead, so that the
                // whole chain (phone, Amazon Music app) can still be checked.
                // It says so, nobody is led to think this was the song of the card.
                send(testSong(qrCodeUrl), setName = null, isTestSong = true, warning = lookup.error ?: "Unknown card.")
            }
        }
    }

    /** A few favourites of the author, one per card, so that scanning two unknown cards plays two songs. */
    private fun testSong(cardUrl: String): SongMapping {
        val songs = listOf(
            "Die Roboter" to "Kraftwerk",
            "Computer Love" to "Kraftwerk",
            "Das Model" to "Kraftwerk"
        )
        val (title, artist) = songs[Math.floorMod(cardUrl.hashCode(), songs.size)]
        return SongMapping(url = cardUrl, title = title, artist = artist, year = null)
    }

    /** Find title and artist for a QR code URL, with the card set file it is from. */
    private suspend fun findSongMapping(url: String): Lookup {
        val csvInfo = CSVUrlParser.parseHitsterUrl(url)
        if (csvInfo != null) {
            val csvResult = csvRepository.getSongFromCSV(csvInfo.csvFileName, csvInfo.index)
            val song = csvResult.getOrNull()
            return when {
                song != null -> Lookup(SongMapping(url, song.title, song.artist, song.year, amazonId = song.amazonId, album = song.album), csvInfo.csvFileName, null, csvInfo.index)
                // The card number and the file name tell no song, so they are fine to show.
                csvResult.isSuccess -> Lookup(
                    null, null,
                    "Card ${csvInfo.index} is not in ${csvInfo.csvFileName}. Please scan the next card."
                )
                else -> Lookup(
                    null, null,
                    "The card set ${csvInfo.csvFileName} is missing: " +
                        "${csvResult.exceptionOrNull()?.message ?: "unknown error"}. Card: $url"
                )
            }
        }

        return Lookup(null, null, "This app does not know this QR code: $url")
    }
}
