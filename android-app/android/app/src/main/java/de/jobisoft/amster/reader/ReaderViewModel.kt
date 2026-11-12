package de.jobisoft.amster.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.jobisoft.amster.data.debug.DebugLog
import de.jobisoft.amster.data.local.CSVFileManager
import de.jobisoft.amster.data.local.CardOverrides
import de.jobisoft.amster.data.local.CardSetNames
import de.jobisoft.amster.data.repository.CSVRepository
import de.jobisoft.amster.data.repository.ConnectorRepository
import de.jobisoft.amster.util.CSVUrlParser
import de.jobisoft.amster.util.CorrectionLogic
import de.jobisoft.amster.util.CardOverride
import de.jobisoft.amster.util.QRCodeParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val corrected: Boolean,
    /** The Amazon track id from the card set, played first if there is one. */
    val amazonId: String? = null,
    /** The album from the card set, sent along with a search. */
    val album: String? = null
)

data class ReaderUiState(
    val card: ReaderCard? = null,
    /** Why the last card could not be shown (unknown card, missing set). The camera stays on. */
    val error: String? = null,
    /** The overlay that asks for the name of a card set the app does not know, the card that started it. */
    val askEdition: PendingCard? = null,
    /** An unknown card is on the screen: the user adds it with the edit overlay. */
    val unknown: UnknownCard? = null,
    /** The overlay to add an unknown card (artist, year, title ...). Its fields are the draft of the [EditDraftStore]. */
    val manualAdd: PendingCard? = null
)

/**
 * Reader mode: scan a card, see artist, year and title from our CSV at once. If something is wrong the user
 * reports it with "Report as wrong" (the same component as in the game, it goes through the [ReaderStore]:
 * `reader_results.csv`, the correction and the debug log), if not the next card is scanned. The mode plays
 * nothing and does not talk to Amazon Music.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val overrides: CardOverrides,
    private val csvFileManager: CSVFileManager,
    private val store: ReaderStore,
    private val overlay: ReportOverlayState,
    private val drafts: EditDraftStore,
    private val csvRepository: CSVRepository,
    private val cardSetNames: CardSetNames
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** The fields of the overlay of "Add Card" (the draft of the [EditDraftStore]). */
    val draft = drafts.draft

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private var lastCode: String? = null
    private var lastReadAt = 0L
    private var serial = 0

    init {
        // a report was saved for the card that is shown: its values and the mark "corrected" are current at once
        viewModelScope.launch {
            store.saved.collect { row ->
                val shown = _state.value.card ?: return@collect
                if (!ReportRefresh.isFor(shown.setFile, shown.number, row)) return@collect
                val v = ReportRefresh.values(shown.year, shown.title, shown.artist, shown.amazonId, shown.album, row)
                _state.value = _state.value.copy(
                    card = shown.copy(year = v.year, title = v.title, artist = v.artist, amazonId = v.amazonId, album = v.album, corrected = true)
                )
            }
        }
    }

    // ── a scan ────────────────────────────────────────────────────────────────────

    /** A code from the camera. The same code again within a few seconds is the same card in front of the lens. */
    fun onCode(raw: String) {
        val at = System.currentTimeMillis()
        // while the overlay of "Report as wrong" is open a new card must not replace the card under it: the scan is dropped
        // the overlays also drop scans: the card under them must not be replaced
        val s = _state.value
        if (ScanFilter.ignore(overlay.open.value || s.askEdition != null || s.manualAdd != null, raw, lastCode, at - lastReadAt)) {
            if (raw == lastCode) lastReadAt = at // the card in front of the lens stays the same card
            return
        }
        lastCode = raw
        lastReadAt = at
        val mine = ++serial
        viewModelScope.launch {
            val url = QRCodeParser.parseHitsterUrl(raw)
            val info = url?.let { CSVUrlParser.parseHitsterUrl(it) }
            if (url == null || info == null) {
                show(mine, null, "This app does not know this QR code: $raw")
                return@launch
            }
            resolve(mine, info.csvFileName, info.index, url)
        }
    }

    /**
     * Looks the card up and shows it: a known card with its values, an unknown card with "Add Card", a card
     * of a broken set file with the old error text. A set without a file counts as known only if its corrections have a name.
     */
    private suspend fun resolve(mine: Int, setFile: String, number: Int, url: String?) {
        val result = csvRepository.getSongFromCSV(setFile, number)
        val song = result.getOrNull()
        val fileExists = csvFileManager.exists(setFile)
        val setKnown = fileExists || overrides.editionOf(setFile) != null
        val kind = OcrFlow.kind(hasInfo = true, cardFound = song != null, setKnown = setKnown)
        when {
            song != null -> showSong(mine, setFile, number, song)
            // a set file that cannot be read stays an error
            !result.isSuccess && fileExists ->
                show(mine, null, "The card set $setFile is missing: ${result.exceptionOrNull()?.message ?: "unknown error"}. Card: ${url.orEmpty()}")
            else -> showUnknown(mine, PendingCard(setFile, number), setKnown)
        }
        DebugLog.log("reader", "reader scan", mapOf("set" to setFile, "card" to number, "kind" to (kind?.name ?: "known"), "setKnown" to setKnown))
    }

    /** The card with its values is shown, like a scan of a known card. */
    private fun showSong(mine: Int, setFile: String, number: Int, song: de.jobisoft.amster.data.repository.CSVSong) {
        val card = ReaderCard(
            mine, setFile, cardSetNames.displayName(setFile), number,
            song.year, song.title, song.artist, song.corrected, song.amazonId, song.album
        )
        show(mine, card, null)
        DebugLog.log(
            "reader", "reader card",
            mapOf("set" to card.setFile, "card" to card.number, "year" to card.year, "title" to card.title,
                "artist" to card.artist, "corrected" to card.corrected)
        )
    }

    // ── an unknown card ───────────────────────────────────────────────────────────

    /** An unknown card: the screen says so and offers "Add Card". */
    private fun showUnknown(mine: Int, pending: PendingCard, setKnown: Boolean) {
        if (mine != serial) return
        val name = if (setKnown) cardSetNames.displayName(pending.setFile) else pending.setFile.removeSuffix(".csv")
        _state.value = _state.value.copy(
            unknown = UnknownCard(pending, setKnown, name), card = null, error = null,
            askEdition = null, manualAdd = null
        )
    }

    /** "Add Card" on an unknown card: a set the app does not know is named first, then the overlay opens. */
    fun addUnknown() {
        val u = _state.value.unknown ?: return
        if (!u.setKnown) {
            _state.value = _state.value.copy(askEdition = u.pending)
        } else {
            proceedAdd(u.pending)
        }
    }

    private fun proceedAdd(pending: PendingCard) {
        drafts.open(EditDraft.of("Add Card", pending.setFile, pending.number, "", null, "", null, null))
        _state.value = _state.value.copy(manualAdd = pending, askEdition = null)
    }

    /** The user gave the name of the edition: the way chosen before goes on. */
    fun confirmEdition(name: String) {
        val pending = _state.value.askEdition ?: return
        val clean = ReaderLogic.collapse(name)
        if (clean.isEmpty()) return
        DebugLog.log("reader", "reader edition named", mapOf("set" to pending.setFile, "edition" to clean))
        // the set exists from now on, also if nothing is added: it is empty, listed in Card Config, and a scan of one of its cards does not ask again
        overrides.add(CardOverride(pending.setFile, CorrectionLogic.SET_ROW, null, "", "", timeFormat.format(Date()), "edition", edition = clean))
        // the set is known now: the unknown card on the screen says so, "Add Card" does not ask for the name again after a cancel
        _state.value = _state.value.copy(unknown = _state.value.unknown?.copy(setKnown = true, setName = clean))
        proceedAdd(pending)
    }

    /** No name: the card stays unknown, the screen offers "Add Card" again. */
    fun cancelEdition() {
        _state.value = _state.value.copy(askEdition = null)
    }

    /** The overlay to add a card was closed without saving. */
    fun cancelManual() {
        drafts.close()
        _state.value = _state.value.copy(manualAdd = null)
    }

    /** Saves what the user entered for an unknown card (source "ocr" when the fields were filled by the OCR). */
    fun saveManual(edit: Edit) {
        val pending = _state.value.manualAdd ?: return
        val source = if (drafts.draft.value?.ocrUsed == true) "ocr" else "manual"
        drafts.close()
        viewModelScope.launch {
            saveCard(pending, edit.year, edit.title, edit.artist, source, edit.amazonId, edit.album)
        }
    }

    /** The fields of the overlay changed. */
    fun updateDraft(draft: EditDraft) = drafts.update(draft)

    /** The screen is gone for good: no overlay stays open. */
    override fun onCleared() {
        drafts.close()
        super.onCleared()
    }

    /**
     * "Scan next card": the data of the card goes, the camera view comes back. The same card may be scanned again at once.
     */
    fun scanNext() {
        lastCode = null
        lastReadAt = 0L
        serial++
        _state.value = _state.value.copy(card = null, error = null, unknown = null)
    }

    /**
     * Saves a card as a correction ([CardOverrides]) and shows it. What an earlier correction said about the id and the album stays when
     * [amazonId] and [album] are null. The name of a new set goes into the first correction of the set.
     */
    private suspend fun saveCard(pending: PendingCard, year: Int, title: String, artist: String, source: String, amazonId: String?, album: String?) {
        val previous = overrides.forSet(pending.setFile)[pending.number]
        // a card that is known and reads the same again is not an update: nothing is stored, the card is just shown
        val known = csvRepository.getSongFromCSV(pending.setFile, pending.number).getOrNull()
        val unchanged = known != null && CorrectionLogic.isUnchanged(
            known.year, known.title, known.artist, known.amazonId, known.album, year, title, artist, amazonId, album
        )
        if (unchanged) {
            DebugLog.log("reader", "reader card unchanged", mapOf("source" to source, "set" to pending.setFile, "card" to pending.number))
        } else {
            overrides.add(
                CardOverride(
                    pending.setFile, pending.number, year, title, artist, timeFormat.format(Date()), source,
                    amazonId = amazonId ?: previous?.amazonId, album = album ?: previous?.album,
                    edition = pending.edition?.takeIf { overrides.editionOf(pending.setFile) == null } ?: previous?.edition
                )
            )
            DebugLog.log("reader", "reader card added", mapOf("source" to source, "set" to pending.setFile, "card" to pending.number, "year" to year, "title" to title, "artist" to artist))
        }
        val song = csvRepository.getSongFromCSV(pending.setFile, pending.number).getOrNull()
        val mine = ++serial
        _state.value = _state.value.copy(manualAdd = null, askEdition = null, unknown = null)
        if (song != null) showSong(mine, pending.setFile, pending.number, song)
        else show(mine, null, "The card was read but could not be saved.")
    }

    /** Only the newest scan is shown, a slow lookup of an older one is dropped. */
    private fun show(mine: Int, card: ReaderCard?, error: String?) {
        if (mine != serial) return
        _state.value = _state.value.copy(card = card, error = error)
    }
}
