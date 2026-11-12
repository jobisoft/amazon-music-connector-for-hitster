package de.jobisoft.hitsterconnector.ui.viewmodel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import de.jobisoft.hitsterconnector.data.local.CardSetNames
import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import de.jobisoft.hitsterconnector.data.repository.CSVSong
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.data.repository.EndEvent
import de.jobisoft.hitsterconnector.data.repository.EndGuard
import de.jobisoft.hitsterconnector.domain.model.SongMapping
import de.jobisoft.hitsterconnector.reader.AdvanceGate
import de.jobisoft.hitsterconnector.reader.ReaderStore
import de.jobisoft.hitsterconnector.reader.ReportOverlayState
import de.jobisoft.hitsterconnector.util.QuizDeck
import de.jobisoft.hitsterconnector.util.QuizPicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quiz mode: no camera, a card of the selected card sets plays at once. "Play next random card",
 * or the end of the song, draws the next one. The cards come out of a shuffled deck, none twice
 * until the deck is used up. This view model, and with it the deck, survives a turn of the screen.
 */
@HiltViewModel
class QuizViewModel @Inject constructor(
    private val csvRepository: CSVRepository,
    private val cardSetNames: CardSetNames,
    private val csvFileManager: CSVFileManager,
    private val endGuard: EndGuard,
    private val overlay: ReportOverlayState,
    connectorRepository: ConnectorRepository,
    readerStore: ReaderStore
) : PlayerViewModel(connectorRepository, readerStore.saved) {

    private var selected: List<String> = emptyList()
    private var deck: QuizDeck? = null
    private var started = false
    private var requestedFiles: List<String> = emptyList()

    /** The song that plays now (title only for the comparison with the end event), and its number. */
    private var currentTitle: String? = null
    private var cardSerial = 0
    private var advancedSerial = 0

    companion object {
        /** In the route of the quiz screen: all card sets. */
        const val ALL = "all"
        const val DECK_USED_UP = "All cards played, starting again"
    }

    private var endJob: Job? = null
    private var overlayJob: Job? = null

    /** The song ended while the overlay of "Report as wrong" was open: the next card waits until it is closed. */
    private var advancePending = false

    init {
        // Plays the next card when the song of this quiz has ended. Lives as long as this view model:
        // leaving the quiz screen ends it ([leave]), clearing the view model ends it with its scope.
        endJob = viewModelScope.launch {
            endGuard.ended.collect { onSongEnded(it) }
        }
        overlayJob = viewModelScope.launch {
            overlay.open.collect { open ->
                if (AdvanceGate.runPending(open, advancePending)) {
                    advancePending = false
                    DebugLog.log("quiz", "quiz auto-next after the report overlay")
                    playNextRandom()
                }
            }
        }
    }

    /** Leaving the quiz: the auto-next listener goes first, no next card may start while the song is stopped. */
    override fun leave() {
        endJob?.cancel()
        overlayJob?.cancel()
        super.leave()
    }

    /**
     * Loads the selected sets and plays the first card. Does nothing if this quiz is running
     * already. The value [ALL] stands for every card set on the phone.
     */
    fun start(requested: List<String>) {
        if (started && requested == requestedFiles) return
        if (!markStopping()) return
        started = true
        requestedFiles = requested
        val files = if (requested == listOf(ALL)) {
            csvFileManager.getAllLocalFiles().map { it.name }.sorted()
        } else {
            requested
        }
        selected = files
        viewModelScope.launch {
            if (!stopThenBeginSending()) return@launch
            val loaded = LinkedHashMap<String, List<CSVSong>>()
            for (file in files) {
                loaded[file] = csvRepository.loadCSV(file).getOrNull()?.values?.toList().orEmpty()
            }
            val newDeck = QuizDeck(loaded, files)
            deck = newDeck
            DebugLog.log("quiz", "quiz start", mapOf("sets" to files, "songs" to loaded.values.sumOf { it.size }, "deckSize" to newDeck.deckSize))
            playRandom()
        }
    }

    /** Draws and plays another card. */
    fun playNextRandom() {
        if (state.sendState == SendState.SENDING || !markStopping()) return
        viewModelScope.launch {
            // the screen shows the song that plays until it is stopped, the button is disabled meanwhile
            if (!stopThenBeginSending()) return@launch
            playRandom()
        }
    }

    /**
     * The guard reports the end of the song of this quiz: the next card, as after a
     * tap on the button. The stop of the song is confirmed first. An end of another song, a second report of
     * the same end and one during a request do nothing.
     */
    private fun onSongEnded(event: EndEvent) {
        if (leaving || !started || busy || state.sendState != SendState.SUCCESS) return
        val title = currentTitle ?: return
        val reported = QuizPicker.normalize(event.song ?: return)
        val current = QuizPicker.normalize(title)
        // an end of another song (an old event) does nothing
        if (reported.isEmpty() || current.isEmpty() || !(reported.contains(current) || current.contains(reported))) return
        if (advancedSerial == cardSerial) return
        advancedSerial = cardSerial
        if (!AdvanceGate.advanceNow(overlay.open.value)) {
            // a report is being written for this card: the next card comes when the overlay is closed
            advancePending = true
            DebugLog.log("quiz", "quiz auto-next held back by the report overlay")
            return
        }
        DebugLog.log("quiz", "quiz auto-next (end)", mapOf("reason" to event.reason))
        playNextRandom()
    }

    private suspend fun playRandom() {
        connectorRepository.markScan()
        val draw = deck?.next()
        if (draw == null) {
            fail("The selected card sets have no songs. Go back and pick other sets.")
            return
        }
        val card = draw.card
        // the log gets the set and the card number, never the title
        DebugLog.log(
            "quiz", "quiz pick",
            mapOf("set" to card.setFile, "card" to card.song.index, "remaining" to draw.remaining, "deckSize" to draw.deckSize)
        )
        if (draw.reshuffled) DebugLog.log("quiz", "deck used up, shuffled again", mapOf("deckSize" to draw.deckSize))
        val song = card.song
        cardSerial++
        currentTitle = song.title
        send(
            SongMapping(url = card.setFile + "#" + song.index, title = song.title, artist = song.artist, year = song.year, amazonId = song.amazonId),
            setName = cardSetNames.displayName(card.setFile),
            isTestSong = false,
            warning = null,
            info = if (draw.reshuffled) DECK_USED_UP else null,
            cardRef = CardRef(card.setFile, song.index)
        )
    }
}
