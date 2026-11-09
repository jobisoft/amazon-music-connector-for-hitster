package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.data.local.ConfigDataStore
import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import de.jobisoft.hitsterconnector.data.repository.ConnectionManager
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.domain.model.ConnectorConfig
import de.jobisoft.hitsterconnector.domain.model.SongMapping
import de.jobisoft.hitsterconnector.util.CSVUrlParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a scanned card is on its way to the speakers. */
enum class SendState { IDLE, SENDING, SUCCESS, FAILED }

/**
 * Scans a card, translates its QR code into title and artist (CSV files of the
 * songseeker-hitster-playlists project) and hands both to the connector.
 *
 * The title and the artist are kept out of the UI state on purpose, because
 * showing them would spoil the game.
 */
@HiltViewModel
class GameViewModel @Inject constructor(
    private val configDataStore: ConfigDataStore,
    private val csvRepository: CSVRepository,
    private val connectorRepository: ConnectorRepository,
    private val connectionManager: ConnectionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** Why the last card was not found. Set while a test song stands in for it. */
    private var lastWarning: String? = null

    init {
        viewModelScope.launch {
            configDataStore.configFlow.collect { config ->
                _uiState.value = _uiState.value.copy(config = config)
            }
        }
    }

    /** Process a scanned QR code URL. */
    fun onQRCodeScanned(qrCodeUrl: String) {
        if (_uiState.value.sendState != SendState.IDLE) return
        // Set synchronously, the scanner may deliver the same code several times in a row.
        _uiState.value = _uiState.value.copy(sendState = SendState.SENDING, message = null)
        viewModelScope.launch {
            val config = _uiState.value.config
            if (config == null) {
                fail("No computer connected yet. Go back to the start screen, or use Manual setup.")
                return@launch
            }

            val lookup = findSongMapping(qrCodeUrl)
            var mapping = lookup.first
            lastWarning = null
            if (mapping == null) {
                // The card is not in any card set. Play a test song instead, so that the
                // whole chain (phone, computer, browser, Amazon Music) can still be checked.
                // It says so, nobody is led to think this was the song of the card.
                mapping = testSong(qrCodeUrl)
                lastWarning = lookup.second ?: "Unknown card."
            }

            send(config, mapping)
        }
    }

    /** Back 10 s, forward 10 s, play again from the start offset, stop, or go to a position. */
    fun control(action: String, position: Float? = null) {
        val config = _uiState.value.config ?: return
        if (_uiState.value.controlBusy) return
        _uiState.value = _uiState.value.copy(controlBusy = true, controlError = null)
        viewModelScope.launch {
            val result = connectorRepository.control(config, action, position)
            val answer = result.getOrNull()
            _uiState.value = _uiState.value.copy(
                controlBusy = false,
                position = answer?.position ?: _uiState.value.position,
                duration = answer?.duration ?: _uiState.value.duration,
                playing = answer?.playing ?: _uiState.value.playing,
                controlError = result.exceptionOrNull()?.message
            )
        }
    }

    /** Asks the computer once a second where the song is, as long as the result is shown. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val state = _uiState.value
                val config = state.config ?: continue
                if (state.controlBusy || state.sendState != SendState.SUCCESS) continue
                val answer = connectorRepository.control(config, "state").getOrNull() ?: continue
                // a button may have been pressed while the answer was on its way
                if (_uiState.value.controlBusy) continue
                _uiState.value = _uiState.value.copy(
                    position = answer.position,
                    duration = answer.duration,
                    playing = answer.playing
                )
            }
        }
    }

    private suspend fun send(config: ConnectorConfig, mapping: SongMapping) {
        var result = connectorRepository.play(config, mapping.title, mapping.artist, mapping.year, mapping.url)
        // The address of the computer may have changed, for example in another Wi-Fi.
        // Look for it once, then try again.
        if (result.isFailure && result.exceptionOrNull()?.message?.startsWith("Cannot reach") == true) {
            val found = connectionManager.findAgain()
            if (found != null) {
                result = connectorRepository.play(found, mapping.title, mapping.artist, mapping.year, mapping.url)
            }
        }
        result.fold(
            onSuccess = { answer ->
                val warning = lastWarning
                _uiState.value = _uiState.value.copy(
                    sendState = SendState.SUCCESS,
                    playing = true,
                    position = 0f,
                    duration = 0f,
                    controlError = null,
                    message = null,
                    warning = warning
                )
            },
            onFailure = { fail(it.message ?: "Unknown error") }
        )
        if (_uiState.value.sendState == SendState.SUCCESS) startPolling()
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

    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(sendState = SendState.FAILED, message = message)
    }

    /** Back to the camera. */
    fun resetToScanner() {
        pollJob?.cancel()
        _uiState.value = _uiState.value.copy(sendState = SendState.IDLE, message = null, warning = null)
    }

    /**
     * Find title and artist for a QR code URL.
     * Returns the mapping, or null together with an error message.
     */
    private suspend fun findSongMapping(url: String): Pair<SongMapping?, String?> {
        val csvInfo = CSVUrlParser.parseHitsterUrl(url)
        if (csvInfo != null) {
            val csvResult = csvRepository.getSongFromCSV(csvInfo.csvFileName, csvInfo.index)
            val song = csvResult.getOrNull()
            return when {
                song != null -> SongMapping(url, song.title, song.artist, song.year) to null
                // The card number and the file name tell no song, so they are fine to show.
                csvResult.isSuccess -> null to "Card ${csvInfo.index} is not in ${csvInfo.csvFileName}. Please scan the next card."
                else -> null to "The card set ${csvInfo.csvFileName} is missing: " +
                    "${csvResult.exceptionOrNull()?.message ?: "unknown error"}. Card: $url"
            }
        }

        // Fallback for URL based CSV files (for example YouTube links in a column).
        for (csvUrl in configDataStore.getCsvUrls()) {
            val song = csvRepository.getSongByUrl(csvUrl, url).getOrNull()
            if (song != null) {
                return SongMapping(url, song.title, song.artist, song.year) to null
            }
        }
        return null to "This app does not know this QR code: $url"
    }
}

data class GameUiState(
    val config: ConnectorConfig? = null,
    val sendState: SendState = SendState.IDLE,
    val message: String? = null,
    /** The reason why the card was not found, shown together with the test song. */
    val warning: String? = null,
    /** False after "stop", then the middle button plays the song again. */
    val playing: Boolean = true,
    /** Where the song is and how long it is, in seconds. 0 while unknown. */
    val position: Float = 0f,
    val duration: Float = 0f,
    val controlBusy: Boolean = false,
    val controlError: String? = null
)
