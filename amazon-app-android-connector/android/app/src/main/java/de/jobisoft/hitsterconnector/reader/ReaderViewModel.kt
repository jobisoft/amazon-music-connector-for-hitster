package de.jobisoft.hitsterconnector.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.data.local.CardSetNames
import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.util.CSVUrlParser
import de.jobisoft.hitsterconnector.util.CardOverride
import de.jobisoft.hitsterconnector.util.QRCodeParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The card on the screen. [corrected]: a correction of this phone lies over its values. */
data class ReaderCard(
    val serial: Int,
    val setFile: String,
    val setName: String,
    val number: Int,
    val year: Int?,
    val title: String,
    val artist: String,
    val corrected: Boolean
)

data class ReaderUiState(
    val card: ReaderCard? = null,
    /** Why the last card could not be shown (unknown card, missing set). The camera stays on. */
    val error: String? = null,
    /** The cards with a correction on this phone. */
    val counters: Counters = Counters(0),
    /** The cards scanned since the screen was opened. */
    val scanned: Int = 0,
    /** A short line for the screen, for example when there is nothing to export. */
    val message: String? = null,
    /** "Send to Amazon Music app": where it stands, the error if it failed, and whether the song has ended. */
    val send: SendStatus = SendStatus.IDLE,
    val sendError: String? = null,
    val songEnded: Boolean = false,
    /** "Amazon played a different version: ..." or null. */
    val versionNote: String? = null,
    /** Why sending is not possible (Amazon Music missing, no notification access), null if it is. */
    val amazonProblem: String? = null
)

/**
 * Reader mode: scan a card, see artist, year and title from our CSV at once. If something is wrong the user
 * reports it with "Report as wrong" (the same component as in the game, it goes through the [ReaderStore]:
 * `reader_results.csv`, the correction and the debug log), if not the next card is scanned. A scan plays
 * nothing, only the button "Send to Amazon Music app" plays the shown card.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val store: ReaderStore,
    private val overlay: ReportOverlayState,
    private val csvRepository: CSVRepository,
    private val cardSetNames: CardSetNames,
    private val connectorRepository: ConnectorRepository
) : ViewModel() {

    private val _state = MutableStateFlow(
        ReaderUiState(
            counters = store.counters.value,
            amazonProblem = SendToAmazon.problem(connectorRepository.isAmazonMusicInstalled(), connectorRepository.hasNotificationAccess())
        )
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var lastCode: String? = null
    private var lastReadAt = 0L
    private var serial = 0

    /** A song was sent from this screen: leaving has something to stop. */
    private var played = false
    private var leaving = false

    init {
        viewModelScope.launch { store.counters.collect { c -> _state.value = _state.value.copy(counters = c) } }
        // a report was saved for the card that is shown: its values and the mark "corrected" are current at once
        viewModelScope.launch {
            store.saved.collect { row ->
                val shown = _state.value.card ?: return@collect
                if (!ReportRefresh.isFor(shown.setFile, shown.number, row)) return@collect
                val (year, title, artist) = ReportRefresh.values(shown.year, shown.title, shown.artist, row)
                _state.value = _state.value.copy(card = shown.copy(year = year, title = title, artist = artist, corrected = true))
            }
        }
        // the song ended (muted since its last second): the screen says so
        viewModelScope.launch { connectorRepository.songEnded.collect { e -> _state.value = _state.value.copy(songEnded = e) } }
    }

    /** Look again whether Amazon Music is ready, for example after the user came back from the settings. */
    fun refreshAmazon() {
        _state.value = _state.value.copy(
            amazonProblem = SendToAmazon.problem(connectorRepository.isAmazonMusicInstalled(), connectorRepository.hasNotificationAccess())
        )
    }

    /**
     * "Send to Amazon Music app": the shown card (with the corrected title and artist, as shown) goes through
     * the same play path as the game: stop of the song that plays and its confirmation, mute, load, pause,
     * seek to the start offset, play, unmute, the end guard armed. The user stays here and the card stays.
     */
    fun sendToAmazon() {
        val s = _state.value
        val card = s.card ?: return
        if (leaving || !SendToAmazon.enabled(true, s.amazonProblem, s.send)) return
        played = true
        _state.value = s.copy(send = SendStatus.SENDING, sendError = null, versionNote = null)
        sendJob = viewModelScope.launch {
            connectorRepository.markScan()
            connectorRepository.stopAndConfirm()
            if (leaving) return@launch
            connectorRepository.muteForNewSong()
            val result = connectorRepository.play(card.title, card.artist, card.year, card.setFile + "#" + card.number)
            if (leaving) return@launch
            _state.value = _state.value.copy(
                send = if (result.isSuccess) SendStatus.PLAYING else SendStatus.FAILED,
                versionNote = result.getOrNull()?.versionNote,
                sendError = result.exceptionOrNull()?.message
            )
        }
    }

    private var sendJob: kotlinx.coroutines.Job? = null

    /**
     * A new card was scanned: the song that "Send to Amazon Music app" started is stopped like with the Stop
     * button (pause and back to the start offset, guard disarmed, stream unmuted), the status line is cleared and
     * the button is usable again. A send under way is cancelled first. Nothing was sent from here: nothing happens.
     */
    private fun stopSongOfTheButton() {
        val s = _state.value
        if (leaving || !SendToAmazon.stopsOnScan(s.send)) return
        sendJob?.cancel()
        sendJob = null
        connectorRepository.leaveScreen(playedHere = true)
        _state.value = s.copy(send = SendStatus.IDLE, sendError = null, versionNote = null, songEnded = false)
    }

    /**
     * The reader is left (the Back button, the system back, the screen destroyed): the song is stopped (pause and
     * rewind), the end guard disarmed, the music stream unmuted, like on the control screen. Once.
     */
    fun leave() {
        if (leaving) return
        leaving = true
        connectorRepository.leaveScreen(playedHere = played)
    }

    override fun onCleared() {
        leave()
        super.onCleared()
    }

    // ── a scan ────────────────────────────────────────────────────────────────────

    /** A code from the camera. The same code again within a few seconds is the same card in front of the lens. */
    fun onCode(raw: String) {
        val at = System.currentTimeMillis()
        // while the overlay of "Report as wrong" is open a new card must not replace the card under it: the scan is dropped
        if (ScanFilter.ignore(overlay.open.value, raw, lastCode, at - lastReadAt)) {
            if (raw == lastCode) lastReadAt = at // the card in front of the lens stays the same card
            return
        }
        lastCode = raw
        lastReadAt = at
        stopSongOfTheButton()
        val mine = ++serial
        viewModelScope.launch {
            val url = QRCodeParser.parseHitsterUrl(raw)
            val info = url?.let { CSVUrlParser.parseHitsterUrl(it) }
            if (url == null || info == null) {
                show(mine, null, "This app does not know this QR code: $raw")
                return@launch
            }
            val result = csvRepository.getSongFromCSV(info.csvFileName, info.index)
            val song = result.getOrNull()
            when {
                song != null -> {
                    val card = ReaderCard(
                        mine, info.csvFileName, cardSetNames.displayName(info.csvFileName), info.index,
                        song.year, song.title, song.artist, song.corrected
                    )
                    show(mine, card, null)
                    DebugLog.log(
                        "reader", "reader card",
                        mapOf("set" to card.setFile, "card" to card.number, "year" to card.year, "title" to card.title,
                            "artist" to card.artist, "corrected" to card.corrected)
                    )
                }
                result.isSuccess -> show(mine, null, "Card ${info.index} is not in ${info.csvFileName}.")
                else -> show(mine, null, "The card set ${info.csvFileName} is missing: ${result.exceptionOrNull()?.message ?: "unknown error"}. Card: $url")
            }
        }
    }

    /** Only the newest scan is shown, a slow lookup of an older one is dropped. */
    private fun show(mine: Int, card: ReaderCard?, error: String?) {
        if (mine != serial) return
        // a card that is shown counts as scanned
        _state.value = _state.value.copy(
            card = card, error = error, message = null,
            scanned = if (card != null) _state.value.scanned + 1 else _state.value.scanned
        )
    }

    // ── the export ────────────────────────────────────────────────────────────────

    /** The corrections of this phone, for the export. */
    fun corrections(): List<CardOverride> = store.corrections()

    /** A short line for the screen, for example when there is nothing to export. */
    fun say(text: String) {
        _state.value = _state.value.copy(message = text)
    }

}
