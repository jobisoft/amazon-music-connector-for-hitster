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

    /** What the lookup of a QR code found: the song and its set, or what the screen says about the code. */
    private data class Lookup(val mapping: SongMapping?, val setFile: String?, val unknown: UnknownScan?, val number: Int? = null)

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
                    found, lookup.setFile?.let { cardSetNames.displayName(it) },
                    cardRef = if (lookup.setFile != null && lookup.number != null) CardRef(lookup.setFile, lookup.number) else null
                )
            } else {
                // The card is not in any card set: the screen says so and that it can be added in Reader Mode. Nothing is played.
                showUnknown(lookup.unknown ?: UnknownCardTexts.notHitster())
            }
        }
    }

    /** Find title and artist for a QR code URL, with the card set file it is from. */
    private suspend fun findSongMapping(url: String): Lookup {
        val csvInfo = CSVUrlParser.parseHitsterUrl(url) ?: return Lookup(null, null, UnknownCardTexts.notHitster())
        val csvResult = csvRepository.getSongFromCSV(csvInfo.csvFileName, csvInfo.index)
        val song = csvResult.getOrNull()
        if (song != null) {
            return Lookup(SongMapping(url, song.title, song.artist, song.year, amazonId = song.amazonId, album = song.album), csvInfo.csvFileName, null, csvInfo.index)
        }
        // The card number and the name of the set tell no song, so they are fine to show. A set that is not on the phone and has no name
        // shows its file name.
        val setKnown = csvResult.isSuccess
        val setName = if (setKnown) cardSetNames.displayName(csvInfo.csvFileName) else csvInfo.csvFileName.removeSuffix(".csv")
        return Lookup(null, null, UnknownCardTexts.card(csvInfo.index, setName, setKnown))
    }
}
