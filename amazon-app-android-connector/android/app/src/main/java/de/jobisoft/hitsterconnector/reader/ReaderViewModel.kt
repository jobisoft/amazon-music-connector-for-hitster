package de.jobisoft.hitsterconnector.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.hitsterconnector.BuildConfig
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.data.local.CSVFileManager
import de.jobisoft.hitsterconnector.data.local.CardOverrides
import de.jobisoft.hitsterconnector.data.local.CardSetNames
import de.jobisoft.hitsterconnector.data.repository.CSVRepository
import de.jobisoft.hitsterconnector.data.repository.ConnectorRepository
import de.jobisoft.hitsterconnector.util.CSVUrlParser
import de.jobisoft.hitsterconnector.util.CorrectionLogic
import de.jobisoft.hitsterconnector.util.CardOverride
import de.jobisoft.hitsterconnector.util.QRCodeParser
import kotlinx.coroutines.Job
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
    /** The cards with a correction on this phone. */
    val counters: Counters = Counters(0),
    /** The cards scanned since the screen was opened. */
    val scanned: Int = 0,
    /** A short line for the screen, for example when there is nothing to export. */
    val message: String? = null,
    /** This build can read the back of a card (OCR). The buttons and the overlay for unknown cards exist only then. */
    val ocrAvailable: Boolean = false,
    /** The OCR mode: the back of an unknown card (or of the card to be read again) is looked for. Scans are ignored meanwhile. */
    val ocr: OcrState? = null,
    /** The overlay that asks for the name of a card set the app does not know, the card that started it. */
    val askEdition: PendingCard? = null,
    /** An unknown card is on the screen: the user adds it by hand or by OCR. */
    val unknown: UnknownCard? = null,
    /** The overlay to add an unknown card by hand (artist, year, title ...). */
    val manualAdd: PendingCard? = null,
    /** In the analysis: the last good picture, shown blurred over the camera window (the camera is off). */
    val analysisFrame: Bitmap? = null
)

/**
 * Reader mode: scan a card, see artist, year and title from our CSV at once. If something is wrong the user
 * reports it with "Report as wrong" (the same component as in the game, it goes through the [ReaderStore]:
 * `reader_results.csv`, the correction and the debug log), if not the next card is scanned. The mode plays
 * nothing and does not talk to Amazon Music.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val overrides: CardOverrides,
    private val csvFileManager: CSVFileManager,
    private val store: ReaderStore,
    private val overlay: ReportOverlayState,
    private val csvRepository: CSVRepository,
    private val cardSetNames: CardSetNames
) : ViewModel() {

    private val _state = MutableStateFlow(
        ReaderUiState(
            ocrAvailable = BuildConfig.OCR_ENABLED,
            counters = store.counters.value
        )
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** The text reader of a build with OCR (Google ML Kit), null in a build without. Created when it is needed first. */
    private val reader: TextReader? by lazy { if (BuildConfig.OCR_ENABLED) MlKitEngine.create(context) else null }
    private val stability = OcrStability()
    private var ocrFramesSent = 0
    private var ocrJob: Job? = null
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private var lastCode: String? = null
    private var lastReadAt = 0L
    private var serial = 0

    init {
        viewModelScope.launch { store.counters.collect { c -> _state.value = _state.value.copy(counters = c) } }
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
        // the overlays and the OCR mode also drop scans: the card under them must not be replaced
        val s = _state.value
        if (ScanFilter.ignore(overlay.open.value || s.ocr != null || s.askEdition != null || s.manualAdd != null, raw, lastCode, at - lastReadAt)) {
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
     * Looks the card up and shows it: a known card with its values, an unknown card with the two ways to add it (by hand, by OCR), a card
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
    private fun showSong(mine: Int, setFile: String, number: Int, song: de.jobisoft.hitsterconnector.data.repository.CSVSong) {
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

    // ── the OCR mode ──────────────────────────────────────────────────────────────

    /** An unknown card: the screen says so and offers "Add manually" and "Add via OCR". */
    private fun showUnknown(mine: Int, pending: PendingCard, setKnown: Boolean) {
        if (mine != serial) return
        val name = if (setKnown) cardSetNames.displayName(pending.setFile) else pending.setFile.removeSuffix(".csv")
        _state.value = _state.value.copy(
            unknown = UnknownCard(pending, setKnown, name), card = null, error = null, message = null,
            ocr = null, askEdition = null, manualAdd = null
        )
    }

    private var addMode = AddMode.MANUAL

    /** "Add manually" / "Add via OCR" on an unknown card: a set the app does not know is named first. */
    fun addUnknown(mode: AddMode) {
        val u = _state.value.unknown ?: return
        if (mode == AddMode.OCR && !_state.value.ocrAvailable) return
        addMode = mode
        if (!u.setKnown) {
            _state.value = _state.value.copy(askEdition = u.pending)
        } else {
            proceedAdd(u.pending)
        }
    }

    private fun proceedAdd(pending: PendingCard) {
        if (addMode == AddMode.OCR) startOcr(serial, pending) else _state.value = _state.value.copy(manualAdd = pending, askEdition = null)
    }

    /** The user gave the name of the edition: the way chosen before goes on. */
    fun confirmEdition(name: String) {
        val pending = _state.value.askEdition ?: return
        val clean = ReaderLogic.collapse(name)
        if (clean.isEmpty()) return
        DebugLog.log("reader", "reader edition named", mapOf("set" to pending.setFile, "edition" to clean, "mode" to addMode.name))
        // the set exists from now on, also if nothing is added: it is empty, listed in Card Config, and a scan of one of its cards does not ask again
        overrides.add(CardOverride(pending.setFile, CorrectionLogic.SET_ROW, null, "", "", timeFormat.format(Date()), "edition", edition = clean))
        proceedAdd(pending)
    }

    /** No name: the card stays unknown, the screen offers the two ways again. */
    fun cancelEdition() {
        _state.value = _state.value.copy(askEdition = null)
    }

    /** The overlay to add by hand was closed without saving. */
    fun cancelManual() {
        _state.value = _state.value.copy(manualAdd = null)
    }

    /** Saves what the user entered for an unknown card. */
    fun saveManual(edit: Edit) {
        val pending = _state.value.manualAdd ?: return
        viewModelScope.launch {
            saveCard(pending, edit.year, edit.title, edit.artist, "manual", edit.amazonId, edit.album)
        }
    }

    /** "Update via OCR" under the shown card: read the back of this card again; year, title and artist are replaced. */
    fun startOcrForShownCard() {
        val card = _state.value.card ?: return
        if (!_state.value.ocrAvailable) return
        startOcr(++serial, PendingCard(card.setFile, card.number))
    }

    private fun startOcr(mine: Int, pending: PendingCard) {
        if (mine != serial) return
        stability.reset()
        captured.clear()
        ocrFramesSent = 0
        ocrJob?.cancel()
        ocrJob = null
        _state.value = _state.value.copy(
            ocr = OcrState(pending, OcrPhase.WAITING, System.currentTimeMillis()),
            askEdition = null, manualAdd = null, unknown = null, card = null, error = null, message = null, analysisFrame = null
        )
        DebugLog.log("reader", "reader ocr start", mapOf("set" to pending.setFile, "card" to pending.number, "edition" to pending.edition))
    }

    /** Leaves the OCR mode without a result: the screen shows the card again (known or unknown). */
    fun cancelOcr() {
        val pending = _state.value.ocr?.pending
        DebugLog.log("reader", "reader ocr cancel", mapOf("set" to pending?.setFile, "card" to pending?.number))
        ocrJob?.cancel()
        ocrJob = null
        stability.reset()
        captured.clear()
        _state.value = _state.value.copy(ocr = null, analysisFrame = null)
        if (pending != null) viewModelScope.launch { resolve(++serial, pending.setFile, pending.number, null) }
    }

    /** A good picture of the card: sharp, the whole back of the card on it. The lines are what the first reading found. */
    private class Captured(val bitmap: Bitmap, val parsed: OcrParsed)

    private val captured = ArrayList<Captured>()

    private fun updateOcr(change: (OcrState) -> OcrState) {
        val current = _state.value.ocr ?: return
        _state.value = _state.value.copy(ocr = change(current))
    }

    /**
     * A picture from the camera while the OCR mode is on, at most one per second. One picture is handled at a time, a picture that comes while
     * another is being read is dropped. A picture counts when it is sharp and the whole back of the card is on it. When [OcrCapture.NEEDED]
     * of them are collected, the analysis starts ([analyse]).
     */
    fun onOcrFrame(bitmap: Bitmap) {
        val o = _state.value.ocr ?: return
        val engine = reader ?: return
        if (o.phase == OcrPhase.ANALYSING || ocrJob?.isActive == true) return
        // the debug app sends the frames it looks at to the log sink, so that the developer sees what the OCR sees (at most 40 per mode)
        if (ocrFramesSent < 40) {
            ocrFramesSent++
            DebugLog.image("ocr-${o.pending.setFile.removeSuffix(".csv")}-${o.pending.number}-${ocrFramesSent}.jpg", bitmap)
        }
        ocrJob = viewModelScope.launch {
            val sharp = OcrQuality.sharpness(OcrImage.grayOf(bitmap), bitmap.width, bitmap.height)
            var parsed: OcrParsed? = null
            var complete = false
            val started = System.currentTimeMillis()
            var capturedBitmap: Bitmap = bitmap
            if (sharp >= OcrQuality.MIN_SHARPNESS) {
                // The card is held in any direction. ML Kit tells the direction of the text of every line: the picture is turned until the
                // text is upright, the readings that follow use the turned picture.
                val first = try { engine.read(bitmap) } catch (e: Exception) { emptyList() }
                val degrees = OcrOrientation.rotationFor(first.filter { it.text.length >= 3 }.map { it.angle })
                val upright = if (degrees != 0) OcrImage.rotated(bitmap, degrees) else bitmap
                if (degrees != 0) DebugLog.log("reader", "reader ocr rotated", mapOf("degrees" to degrees, "lines" to first.size))
                val lines = if (degrees != 0) {
                    try { engine.read(upright) } catch (e: Exception) { emptyList() }
                } else {
                    first
                }
                val p = OcrCardParser.parseDetailed(lines)
                val c = p != null && OcrCardParser.isComplete(p, upright.width, upright.height)
                DebugLog.log(
                    "reader", "reader ocr frame checked",
                    mapOf(
                        "sharpness" to sharp.toInt(), "complete" to c, "ms" to (System.currentTimeMillis() - started),
                        "lines" to lines.joinToString(" | ") { "${it.text} [${it.height}]" },
                        "boxes" to lines.joinToString(" | ") { "${it.left},${it.top}-${it.right},${it.bottom}" },
                        "parsed" to p?.card?.let { "${it.artist} / ${it.year} / ${it.title}" },
                        "captured" to captured.size, "set" to o.pending.setFile, "card" to o.pending.number
                    )
                )
                if (c) {
                    parsed = p
                    complete = true
                    capturedBitmap = upright
                }
            } else {
                DebugLog.log("reader", "reader ocr frame blurred", mapOf("sharpness" to sharp.toInt(), "min" to OcrQuality.MIN_SHARPNESS))
            }
            val current = _state.value.ocr ?: return@launch // cancelled meanwhile
            if (complete && parsed != null) {
                captured.add(Captured(capturedBitmap, parsed))
                updateOcr { it.copy(captured = captured.size, startedAt = System.currentTimeMillis(), phase = OcrPhase.WAITING) }
                if (captured.size >= OcrCapture.NEEDED) analyse(current.pending)
            } else {
                val waiting = current.copy(phase = OcrPhase.WAITING)
                val phase = OcrFlow.phaseAfter(waiting, System.currentTimeMillis())
                if (phase == OcrPhase.FAILED && current.phase != OcrPhase.FAILED) {
                    DebugLog.log("reader", "reader ocr failed", mapOf("set" to o.pending.setFile, "card" to o.pending.number, "captured" to captured.size))
                }
                if (phase != current.phase) updateOcr { it.copy(phase = phase) }
            }
        }
    }

    /**
     * The analysis: every collected picture is read with several methods (as it is is in the collection already): a local threshold
     * with two window sizes, and the area of the card cut out and enlarged, plain and thresholded. Every reading that finds a card is a vote,
     * the year, the artist and the title are decided one by one ([OcrVote]). All votes and the result go to the debug log.
     */
    private suspend fun analyse(pending: PendingCard) {
        val engine = reader ?: return
        val frames = captured.toList()
        val methods = 3
        updateOcr { it.copy(phase = OcrPhase.ANALYSING, analysed = 0, planned = frames.size * methods) }
        _state.value = _state.value.copy(analysisFrame = frames.last().bitmap)
        val votes = ArrayList<OcrCard>()
        frames.forEach { votes.add(it.parsed.card) }
        var done = 0
        for ((index, frame) in frames.withIndex()) {
            val area = OcrImage.cardArea(frame.parsed.allLines, frame.bitmap.width, frame.bitmap.height)
            val thr151 = OcrImage.binarized(frame.bitmap, 151, 0.85)
            val pictures = listOf(
                "mlkit-plain" to frame.bitmap,
                "mlkit-crop" to OcrImage.cropped(frame.bitmap, area),
                "mlkit-crop-threshold" to OcrImage.cropped(thr151, area)
            )
            for ((name, picture) in pictures) {
                if (_state.value.ocr == null) return // cancelled
                val started = System.currentTimeMillis()
                val lines = try {
                    engine.read(picture)
                } catch (e: Exception) {
                    DebugLog.log("reader", "reader ocr error", mapOf("error" to e))
                    emptyList()
                }
                val parsed = OcrCardParser.parse(lines)
                if (parsed != null) votes.add(parsed)
                done++
                updateOcr { it.copy(analysed = done) }
                DebugLog.log(
                    "reader", "reader ocr analysis",
                    mapOf(
                        "frame" to index + 1, "method" to name, "ms" to (System.currentTimeMillis() - started),
                        "lines" to lines.joinToString(" | ") { "${it.text} [${it.height}]" },
                        "parsed" to parsed?.let { "${it.artist} / ${it.year} / ${it.title}" }, "size" to "${picture.width}x${picture.height}"
                    )
                )
            }
        }
        val result = OcrVote.decide(votes)
        DebugLog.log(
            "reader", "reader ocr vote",
            mapOf(
                "votes" to votes.size,
                "years" to votes.groupingBy { it.year }.eachCount().toString(),
                "artists" to votes.groupingBy { it.artist }.eachCount().toString(),
                "titles" to votes.groupingBy { it.title }.eachCount().toString(),
                "result" to result?.let { "${it.artist} / ${it.year} / ${it.title}" }
            )
        )
        if (_state.value.ocr == null) return // cancelled
        if (result != null) {
            finishOcr(pending, result)
        } else {
            // no clear result: the collection starts again, the screen says so
            captured.clear()
            _state.value = _state.value.copy(analysisFrame = null)
            updateOcr { it.copy(phase = OcrPhase.FAILED, captured = 0, analysed = 0, planned = 0, startedAt = System.currentTimeMillis()) }
        }
    }

    /** The back of the card was read: the card is saved (source "ocr") and shown like a scanned card. */
    private suspend fun finishOcr(pending: PendingCard, card: OcrCard) {
        saveCard(pending, card.year, card.title, card.artist, "ocr", null, null)
        ocrJob = null
    }

    /**
     * Saves a card as a correction ([CardOverrides]) and shows it. What an earlier correction said about the id and the album stays when
     * [amazonId] and [album] are null. The name of a new set goes into the first correction of the set.
     */
    private suspend fun saveCard(pending: PendingCard, year: Int, title: String, artist: String, source: String, amazonId: String?, album: String?) {
        val previous = overrides.forSet(pending.setFile)[pending.number]
        overrides.add(
            CardOverride(
                pending.setFile, pending.number, year, title, artist, timeFormat.format(Date()), source,
                amazonId = amazonId ?: previous?.amazonId, album = album ?: previous?.album,
                edition = pending.edition?.takeIf { overrides.editionOf(pending.setFile) == null } ?: previous?.edition
            )
        )
        DebugLog.log("reader", "reader card added", mapOf("source" to source, "set" to pending.setFile, "card" to pending.number, "year" to year, "title" to title, "artist" to artist))
        val song = csvRepository.getSongFromCSV(pending.setFile, pending.number).getOrNull()
        val mine = ++serial
        _state.value = _state.value.copy(ocr = null, manualAdd = null, askEdition = null, unknown = null, analysisFrame = null)
        if (song != null) showSong(mine, pending.setFile, pending.number, song)
        else show(mine, null, "The card was read but could not be saved.")
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
