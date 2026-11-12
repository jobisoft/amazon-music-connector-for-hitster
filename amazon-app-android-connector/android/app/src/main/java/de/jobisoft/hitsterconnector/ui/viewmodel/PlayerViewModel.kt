package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.reader.ReportRefresh
import de.jobisoft.hitsterconnector.reader.ResultRow
import de.jobisoft.hitsterconnector.domain.model.SongMapping
import de.jobisoft.hitsterconnector.util.PlayFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a card is on its way to the speakers. */
/** [UNKNOWN]: the scan is a card the app does not have, the screen says so and offers Cancel (nothing is sent to Amazon Music). */
enum class SendState { IDLE, SENDING, SUCCESS, FAILED, UNKNOWN }

/** Which card of which set is on the screen (for "Report as wrong"). */
data class CardRef(val setFile: String, val number: Int)

/** What the reveal box shows once it is tapped. */
data class SongInfo(val title: String, val artist: String, val year: Int?, val amazonId: String? = null, val album: String? = null)

data class GameUiState(
    val sendState: SendState = SendState.IDLE,
    val message: String? = null,
    /** What the screen says about an unknown card, in the state [SendState.UNKNOWN]. */
    val unknown: UnknownScan? = null,
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
    val revealed: Boolean = false,
    /** True while the song that plays is stopped before the next request, the buttons wait. */
    val stopping: Boolean = false,
    /** A short line for the result screen, for example "All cards played, starting again". */
    val info: String? = null,
    /** True from the end of the song until the next request. */
    val songEnded: Boolean = false,
    /** The user pressed Stop: paused and rewound. Play or restart go on from the start offset. */
    val stopped: Boolean = false,
    /** The card on the screen. */
    val cardRef: CardRef? = null,
    /** Counts up with every card shown, a new value means a new card. */
    val cardSerial: Int = 0,
    /** The line under the sending text ("loading", "buffering", "searching"), null for none. */
    val status: String? = null,
    /** True when the request took [PlayFlow.CANCEL_AFTER_MS] without playing: the screen offers Cancel. */
    val canCancel: Boolean = false
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
        // the scan mode starts: the end guard listens while the screen is open, a song that plays is paused (fire and forget)
        connectorRepository.startGuard()
        connectorRepository.pauseSong()
        viewModelScope.launch {
            reports.collect { row ->
                val ref = _uiState.value.cardRef ?: return@collect
                val song = _uiState.value.song ?: return@collect
                if (!ReportRefresh.isFor(ref.setFile, ref.number, row)) return@collect
                val v = ReportRefresh.values(song.year, song.title, song.artist, song.amazonId, song.album, row)
                _uiState.value = _uiState.value.copy(song = SongInfo(v.title, v.artist, v.year, v.amazonId, v.album))
            }
        }
        viewModelScope.launch {
            connectorRepository.songEnded.collect { ended -> _uiState.value = _uiState.value.copy(songEnded = ended) }
        }
        viewModelScope.launch {
            connectorRepository.playStatus.collect { status ->
                // only while a request is on its way, the status of the last one must not stay on the result
                if (_uiState.value.sendState == SendState.SENDING) _uiState.value = _uiState.value.copy(status = status)
            }
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
     * Switches to the "sending" screen. The song that plays was paused when the scan mode started, nothing is stopped
     * or waited for here. Call after [markStopping].
     */
    protected suspend fun stopThenBeginSending(): Boolean {
        pollJob?.cancel() // the old song is not asked about any more
        if (leaving) return false // the screen was left meanwhile: no new card
        beginSending()
        stopping = false
        return true
    }

    /** A new card: the previous one is forgotten, the song is hidden again. Set at once, scans may repeat. */
    protected fun beginSending() {
        pollJob?.cancel()
        _uiState.value = GameUiState(sendState = SendState.SENDING)
        // no Cancel before the request took a while, then it is the way out of a request that hangs
        cancelOffer?.cancel()
        cancelOffer = viewModelScope.launch {
            delay(PlayFlow.CANCEL_AFTER_MS)
            if (_uiState.value.sendState == SendState.SENDING) _uiState.value = _uiState.value.copy(canCancel = true)
        }
    }

    private var cancelOffer: Job? = null

    /** The job of the request that is on its way, so that Cancel can end it. */
    private var sendJob: Job? = null

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
        mapping: SongMapping, setName: String?, info: String? = null, cardRef: CardRef? = null
    ) {
        if (leaving) return
        sendJob = currentCoroutineContext()[Job]
        val result = connectorRepository.play(mapping.title, mapping.artist, mapping.year, mapping.url, mapping.amazonId, mapping.album)
        result.fold(
            onSuccess = { answer ->
                _uiState.value = _uiState.value.copy(
                    sendState = SendState.SUCCESS,
                    playing = true,
                    position = 0f,
                    duration = 0f,
                    controlError = null,
                    message = null,
                    status = null,
                    canCancel = false,
                    unknown = null,
                    song = SongInfo(mapping.title, mapping.artist, mapping.year, mapping.amazonId, mapping.album),
                    setName = setName,
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

    /** An unknown card: the screen says so, nothing is sent. Cancel brings the camera back ([cancelSending]). */
    protected fun showUnknown(scan: UnknownScan) {
        cancelOffer?.cancel()
        _uiState.value = GameUiState(sendState = SendState.UNKNOWN, unknown = scan)
    }

    protected fun fail(message: String) {
        cancelOffer?.cancel()
        _uiState.value = _uiState.value.copy(sendState = SendState.FAILED, message = message, status = null, canCancel = false)
    }

    /**
     * The screen with the controls is left (the Back button, the system back, the screen destroyed,
     * but not "Scan next card"): stop the song (pause and rewind), disarm the guard, in this
     * order. It runs in a scope of the application and does not hold up the navigation, it also
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

    /**
     * "Scan next card": the song of this card is paused (fire and forget), then the camera comes back. The scan mode
     * starts again, with its camera timeout.
     */
    fun nextCard() {
        if (leaving) return
        pollJob?.cancel()
        connectorRepository.pauseSong()
        stopping = false
        reset()
    }

    /**
     * Cancel on the sending screen: the request ends, the song is paused (only that), the volume is back, and the
     * camera comes back. On the unknown card screen nothing was sent, the camera comes back at once.
     */
    fun cancelSending() {
        val sendState = _uiState.value.sendState
        if (sendState == SendState.UNKNOWN) {
            connectorRepository.pauseSong()
            reset()
            return
        }
        if (sendState != SendState.SENDING) return
        played = true
        sendJob?.cancel()
        cancelOffer?.cancel()
        connectorRepository.pauseSong()
        connectorRepository.restoreVolume("cancel")
        stopping = false
        reset()
    }

    /** Cancel in a mode without a camera: the request ends and the screen is left like by Back. */
    fun cancelAndLeave() {
        if (_uiState.value.sendState != SendState.SENDING) return
        sendJob?.cancel()
        cancelOffer?.cancel()
        leave()
        reset()
    }

    /** Back to the camera, or out of the quiz. */
    fun reset() {
        pollJob?.cancel()
        cancelOffer?.cancel()
        _uiState.value = GameUiState()
    }
}
