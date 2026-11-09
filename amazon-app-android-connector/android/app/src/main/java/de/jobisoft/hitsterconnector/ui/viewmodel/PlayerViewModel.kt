package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.reader.ReportRefresh
import de.jobisoft.hitsterconnector.reader.ResultRow
import de.jobisoft.hitsterconnector.domain.model.SongMapping
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a card is on its way to the speakers. */
enum class SendState { IDLE, SENDING, SUCCESS, FAILED }

/** Which card of which set is on the screen (for "Report as wrong"). */
data class CardRef(val setFile: String, val number: Int)

/** What the reveal box shows once it is tapped. The year is null for the test song. */
data class SongInfo(val title: String, val artist: String, val year: Int?)

data class GameUiState(
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
    val controlError: String? = null,
    /** The song of the card. Only the reveal box may show it, and only after a tap. */
    val song: SongInfo? = null,
    /** Display name of the card set, null if unknown. */
    val setName: String? = null,
    /** True if a test song plays in place of an unknown card. */
    val isTestSong: Boolean = false,
    val revealed: Boolean = false,
    /** True while the song that plays is stopped before the next request, the buttons wait. */
    val stopping: Boolean = false,
    /** A short line for the result screen, for example "All cards played, starting again". */
    val info: String? = null,
    /** "Amazon played a different version: ..." if the song is a remix, live version etc. Null if the version looks right. */
    val versionNote: String? = null,
    /** True from the end of the song until the next request. */
    val songEnded: Boolean = false,
    /** The user pressed Stop: paused and rewound. Play or restart go on from the start offset. */
    val stopped: Boolean = false,
    /** The card on the screen, null for the test song of an unknown card. */
    val cardRef: CardRef? = null,
    /** Counts up with every card shown, a new value means a new card. */
    val cardSerial: Int = 0
)

/**
 * What the scanner and the quiz have in common: one card goes to Amazon Music, the player is
 * controlled and polled, and the song can be revealed after a tap.
 */
abstract class PlayerViewModel(
    protected val connectorRepository: ConnectorRepository,
    /** Saved reports of the user: the info box shows the corrected values at once, without a new scan. */
    reports: SharedFlow<ResultRow>
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var cardCounter = 0

    init {
        viewModelScope.launch {
            reports.collect { row ->
                val ref = _uiState.value.cardRef ?: return@collect
                val song = _uiState.value.song ?: return@collect
                if (!ReportRefresh.isFor(ref.setFile, ref.number, row)) return@collect
                val (year, title, artist) = ReportRefresh.values(song.year, song.title, song.artist, row)
                _uiState.value = _uiState.value.copy(song = SongInfo(title, artist, year))
            }
        }
        viewModelScope.launch {
            connectorRepository.songEnded.collect { ended -> _uiState.value = _uiState.value.copy(songEnded = ended) }
        }
    }

    protected val state: GameUiState get() = _uiState.value

    /** Set at once on the first trigger, so repeated scans and taps do nothing while the song is stopped. */
    private var stopping = false

    /** True while a stop is on its way, or a request is being sent. Triggers of the same kind do nothing then. */
    protected val busy: Boolean get() = stopping || _uiState.value.sendState == SendState.SENDING

    /** A request of this screen went out (or is on its way): leaving has something to stop. */
    private var played = false

    /** The screen is being left: nothing new may start, a request in flight gives up. */
    protected var leaving = false
        private set

    /** True if a trigger was taken. False: another one is stopping the song already, do nothing. */
    protected fun markStopping(): Boolean {
        if (stopping || leaving) return false
        stopping = true
        played = true
        _uiState.value = _uiState.value.copy(stopping = true)
        return true
    }

    /**
     * Stops the song that plays and waits for the player to confirm, then switches to the "sending"
     * screen. Until then the screen stays as it is. A stop that is not confirmed does not hold
     * anything up. Call after [markStopping].
     */
    protected suspend fun stopThenBeginSending(): Boolean {
        pollJob?.cancel() // the old song is not asked about any more
        connectorRepository.stopAndConfirm()
        if (leaving) return false // the screen was left meanwhile: no new card
        // sound off for the load of the new song, back on when it plays at its start offset
        connectorRepository.muteForNewSong()
        beginSending()
        stopping = false
        return true
    }

    /** A new card: the previous one is forgotten, the song is hidden again. Set at once, scans may repeat. */
    protected fun beginSending() {
        pollJob?.cancel()
        _uiState.value = GameUiState(sendState = SendState.SENDING)
    }

    /** Back 10 s, forward 10 s, play again from the start offset, stop, or go to a position. */
    fun control(action: String, position: Float? = null) {
        if (_uiState.value.controlBusy) return
        _uiState.value = _uiState.value.copy(controlBusy = true, controlError = null)
        viewModelScope.launch {
            val result = connectorRepository.control(action, position)
            val answer = result.getOrNull()
            _uiState.value = _uiState.value.copy(
                controlBusy = false,
                position = answer?.position ?: _uiState.value.position,
                duration = answer?.duration ?: _uiState.value.duration,
                playing = answer?.playing ?: _uiState.value.playing,
                stopped = answer?.stopped ?: _uiState.value.stopped,
                controlError = result.exceptionOrNull()?.message
            )
        }
    }

    /** Tap on the reveal box. */
    fun toggleReveal() {
        _uiState.value = _uiState.value.copy(revealed = !_uiState.value.revealed)
    }

    /** Asks the Amazon Music app once a second where the song is, as long as the result is shown. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _uiState.value
                if (current.controlBusy || current.sendState != SendState.SUCCESS) continue
                val answer = connectorRepository.control("state").getOrNull() ?: continue
                // a button may have been pressed while the answer was on its way
                if (_uiState.value.controlBusy) continue
                _uiState.value = _uiState.value.copy(
                    position = answer.position,
                    duration = answer.duration,
                    playing = answer.playing,
                    stopped = answer.stopped
                )
            }
        }
    }

    /** Plays the card and shows the result. [mapping] is the song, [setName] the set it is from. */
    protected suspend fun send(
        mapping: SongMapping, setName: String?, isTestSong: Boolean, warning: String?, info: String? = null, cardRef: CardRef? = null
    ) {
        if (leaving) return
        val result = connectorRepository.play(mapping.title, mapping.artist, mapping.year, mapping.url)
        result.fold(
            onSuccess = { answer ->
                _uiState.value = _uiState.value.copy(
                    versionNote = answer.versionNote,
                    sendState = SendState.SUCCESS,
                    playing = true,
                    position = 0f,
                    duration = 0f,
                    controlError = null,
                    message = null,
                    warning = warning,
                    song = SongInfo(mapping.title, mapping.artist, if (isTestSong) null else mapping.year),
                    setName = setName,
                    isTestSong = isTestSong,
                    revealed = false,
                    info = info,
                    cardRef = cardRef,
                    cardSerial = ++cardCounter
                )
            },
            onFailure = { fail(it.message ?: "Unknown error") }
        )
        if (_uiState.value.sendState == SendState.SUCCESS) startPolling()
    }

    protected fun fail(message: String) {
        connectorRepository.unmuteNow("request failed")
        _uiState.value = _uiState.value.copy(sendState = SendState.FAILED, message = message)
    }

    /**
     * The screen with the controls is left (the Back button, the system back, the screen destroyed,
     * but not "Scan next card"): stop the song (pause and rewind), disarm the guard, unmute, in
     * this order. It runs in a scope of the application and does not hold up the navigation, it also
     * finishes after this view model is gone. Only once, whichever way the screen is left first.
     */
    open fun leave() {
        if (leaving) return
        leaving = true
        pollJob?.cancel()
        connectorRepository.leaveScreen(playedHere = played)
    }

    override fun onCleared() {
        leave()
        super.onCleared()
    }

    /** Back to the camera, or out of the quiz. */
    fun reset() {
        pollJob?.cancel()
        _uiState.value = GameUiState()
    }
}
