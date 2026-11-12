package de.jobisoft.amster.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.jobisoft.amster.BuildConfig
import de.jobisoft.amster.data.debug.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the OCR page shows: the collection and the analysis ([ocr]), the picture under the analysis, and [done] when the page is to close. */
data class OcrUiState(
    val ocr: OcrState? = null,
    /** In the analysis: the last good picture, shown blurred over the camera window (the camera is off). */
    val analysisFrame: Bitmap? = null,
    val done: Boolean = false
)

/**
 * The OCR page: reads the back of a card for the open edit overlay ([EditDraftStore]). The camera collects sharp pictures with the whole
 * card on them, then every picture is read several times and the readings vote ([analyse]). A clear result goes into the fields of
 * the draft ([EditDraftStore.applyOcr]), nothing is saved here. Then, or when the user cancels, [OcrUiState.done] is set and the
 * page closes, the overlay is shown again.
 */
@HiltViewModel
class OcrViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val drafts: EditDraftStore
) : ViewModel() {

    private val _state = MutableStateFlow(OcrUiState())
    val state: StateFlow<OcrUiState> = _state.asStateFlow()

    /** The text reader of a build with OCR (Google ML Kit), null in a build without. Created when it is needed first. */
    private val reader: TextReader? by lazy { if (BuildConfig.OCR_ENABLED) MlKitEngine.create(context) else null }
    private var ocrFramesSent = 0
    private var ocrJob: Job? = null

    init {
        val draft = drafts.draft.value
        if (draft == null || reader == null) {
            // no overlay to fill, or a build without OCR: nothing to do here
            _state.value = OcrUiState(done = true)
        } else {
            val pending = PendingCard(draft.setFile, draft.number)
            _state.value = OcrUiState(ocr = OcrState(pending, OcrPhase.WAITING, System.currentTimeMillis()))
            DebugLog.log("reader", "reader ocr start", mapOf("set" to pending.setFile, "card" to pending.number))
        }
    }

    /** The camera time ran out, or Cancel: back to the overlay with its fields as they are. */
    fun cancel() {
        val pending = _state.value.ocr?.pending
        DebugLog.log("reader", "reader ocr cancel", mapOf("set" to pending?.setFile, "card" to pending?.number))
        ocrJob?.cancel()
        ocrJob = null
        captured.clear()
        _state.value = OcrUiState(done = true)
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
    fun onFrame(bitmap: Bitmap) {
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
            if (sharp >= OcrQuality.MIN_SHARPNESS) updateOcr { it.copy(scans = it.scans + 1) }
            if (complete && parsed != null) {
                captured.add(Captured(capturedBitmap, parsed))
                updateOcr { it.copy(captured = captured.size, startedAt = System.currentTimeMillis(), phase = OcrPhase.WAITING) }
                if (captured.size >= OcrCapture.NEEDED) analyse(current.pending)
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

    /** The back of the card was read: its values go into the fields of the overlay, the page closes. */
    private fun finishOcr(pending: PendingCard, card: OcrCard) {
        drafts.applyOcr(card)
        DebugLog.log("reader", "reader ocr used", mapOf("set" to pending.setFile, "card" to pending.number, "year" to card.year, "title" to card.title, "artist" to card.artist))
        ocrJob = null
        _state.value = OcrUiState(done = true)
    }
}
