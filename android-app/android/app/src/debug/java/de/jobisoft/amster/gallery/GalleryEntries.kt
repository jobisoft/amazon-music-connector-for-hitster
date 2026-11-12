package de.jobisoft.amster.gallery

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import de.jobisoft.amster.reader.EditDraft
import de.jobisoft.amster.reader.EditionDialog
import de.jobisoft.amster.reader.OcrPhase
import de.jobisoft.amster.reader.OcrState
import de.jobisoft.amster.reader.PendingCard
import de.jobisoft.amster.reader.ReaderCard
import de.jobisoft.amster.reader.ReaderDataView
import de.jobisoft.amster.reader.UnknownCard
import de.jobisoft.amster.reader.WrongDialog
import de.jobisoft.amster.reader.OcrPageContent
import de.jobisoft.amster.ui.components.CameraPermission
import de.jobisoft.amster.ui.components.ScanCameraView
import de.jobisoft.amster.ui.screens.AboutScreen
import de.jobisoft.amster.ui.screens.CSVPreviewContent
import de.jobisoft.amster.ui.screens.CardConfigContent
import de.jobisoft.amster.ui.screens.CrashDialog
import de.jobisoft.amster.ui.screens.HelpScreen
import de.jobisoft.amster.ui.screens.HomeContent
import de.jobisoft.amster.ui.screens.LicensesScreen
import de.jobisoft.amster.ui.screens.OptionsScreen
import de.jobisoft.amster.ui.screens.QuizSetsContent
import de.jobisoft.amster.ui.screens.ResultView
import de.jobisoft.amster.ui.viewmodel.CardConfigUiState
import de.jobisoft.amster.ui.viewmodel.CardRef
import de.jobisoft.amster.ui.viewmodel.GameUiState
import de.jobisoft.amster.ui.viewmodel.QuizSet
import de.jobisoft.amster.ui.viewmodel.QuizSetsState
import de.jobisoft.amster.ui.viewmodel.SendState
import de.jobisoft.amster.ui.viewmodel.SetupState
import de.jobisoft.amster.ui.viewmodel.SongInfo
import de.jobisoft.amster.ui.viewmodel.UnknownCardTexts
import de.jobisoft.amster.ui.viewmodel.UnknownScan

private const val SET_FILE = "hitster-de-aaaa0075.csv"
private const val SET_NAME = "Hitster Deutschland"

private val sampleCard = ReaderCard(1, SET_FILE, SET_NAME, 82, 2007, "Vom selben Stern", "Ich + Ich", corrected = false)

/** A card of the game that is on the screen: the song is hidden, the player runs. The other samples are copies of it. */
private val playing = GameUiState(
    sendState = SendState.SUCCESS,
    playing = true,
    position = 42f,
    duration = 210f,
    song = SongInfo("Vom selben Stern", "Ich + Ich", 2007),
    setName = SET_NAME,
    cardRef = CardRef(SET_FILE, 82),
    cardSerial = 1
)

private val sending = GameUiState(sendState = SendState.SENDING)

/** The two header lines of the group of a Play Mode and Quiz Mode result entry. */
private const val PLAY = "Play Mode and Quiz Mode, result"

private fun failed(message: String?) = GameUiState(sendState = SendState.FAILED, message = message)

private fun unknown(scan: UnknownScan) = GameUiState(sendState = SendState.UNKNOWN, unknown = scan)

/** The result screen with the reveal box that toggles, like the real one. [quiz]: the quiz variant (its button, no OCR). */
@Composable
private fun ResultPreview(state: GameUiState, quiz: Boolean = false) {
    var revealed by remember { mutableStateOf(state.revealed) }
    ResultView(
        state = state.copy(revealed = revealed),
        nextLabel = if (quiz) "Play next random card" else "Scan next card",
        reportSource = if (quiz) "quiz" else "scan",
        onControl = { _, _ -> },
        onToggleReveal = { revealed = !revealed },
        onNext = {},
        onCancel = {},
        onReadOcr = if (quiz) null else ({})
    )
}

/** What the camera shows in a preview: no camera is opened. */
@Composable
private fun CameraPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B2233)), contentAlignment = Alignment.Center) {
        Text("Camera")
    }
}

@Composable
private fun CameraView(granted: Boolean) {
    ScanCameraView(permission = CameraPermission(granted) {}, secondsLeft = 12, camera = { CameraPlaceholder() })
}

@Composable
private fun OcrPage(phase: OcrPhase, granted: Boolean = true) {
    OcrPageContent(
        permission = CameraPermission(granted) {},
        secondsLeft = 12,
        ocr = OcrState(PendingCard(SET_FILE, 82), phase, scans = 7, captured = 1),
        analysisFrame = null,
        camera = { CameraPlaceholder() }
    )
}

private fun editDraft(title: String, amazonId: String? = null) =
    EditDraft.of(title, SET_FILE, 82, "Ich + Ich", 2007, "Vom selben Stern", amazonId, null)

/** An edit overlay with fields that can be typed in. Save and Cancel close the page. */
@Composable
private fun EditPreview(title: String, withOcr: Boolean, amazonId: String? = null, empty: Boolean = false, onBack: () -> Unit) {
    var draft by remember {
        mutableStateOf(if (empty) EditDraft.of(title, SET_FILE, 83, "", null, "", null, null) else editDraft(title, amazonId))
    }
    WrongDialog(
        draft = draft, onDraft = { draft = it },
        ourYear = if (empty) null else 2007, ourTitle = if (empty) "" else "Vom selben Stern",
        ourArtist = if (empty) "" else "Ich + Ich", ourAmazonId = null, ourAlbum = null,
        onReadOcr = if (withOcr) ({}) else null,
        onSave = { onBack() }, onCancel = onBack
    )
}

@Composable
private fun QuizSetsPreview(sets: List<QuizSet>, selected: Set<String>) {
    var ticked by remember { mutableStateOf(selected) }
    val state = QuizSetsState(sets, ticked)
    QuizSetsContent(
        state = state,
        onToggleAll = { ticked = if (state.allSelected) emptySet() else sets.map { it.file }.toSet() },
        onToggle = { file -> ticked = if (file in ticked) ticked - file else ticked + file },
        onStart = {}
    )
}

private val someSets = listOf(
    QuizSet(SET_FILE, SET_NAME),
    QuizSet("hitster-de-aaaa0039.csv", "Hitster Deutschland 2"),
    QuizSet("hitster-nl-aaaa0010.csv", "Hitster Nederland")
)

private val files = listOf("hitster-de-aaaa0075.csv", "hitster-de-aaaa0039.csv", "hitster-nl-aaaa0010.csv")

private const val SAMPLE_CSV = "Card#,Title,Artist,Year\n1,Vom selben Stern,Ich + Ich,2007\n2,In deinen Armen,Ayliva,2023\n3,Wonderwall,Oasis,1995"

private const val SAMPLE_TRACE = "java.lang.IllegalStateException: sample crash for the gallery\n" +
    "\tat de.jobisoft.amster.gallery.Sample.crash(Sample.kt:1)\n" +
    "\tat de.jobisoft.amster.ui.screens.HomeScreen(HomeScreen.kt:1)\n" +
    "\tat android.os.Handler.handleCallback(Handler.java:958)\n" +
    "\tat android.os.Looper.loop(Looper.java:205)"

private fun home(state: SetupState): @Composable (() -> Unit) -> Unit = {
    HomeContent(
        state = state, exportCount = 3,
        onNavigateToCardConfig = {}, onNavigateToScanner = {}, onNavigateToQuiz = {}, onNavigateToReader = {},
        onNavigateToOptions = {}, onNavigateToHelp = {}, onNavigateToLicenses = {}, onNavigateToAbout = {},
        onNavigateToGallery = null, onExport = {}
    )
}

private fun cardConfig(state: CardConfigUiState): @Composable (() -> Unit) -> Unit = {
    CardConfigContent(uiState = state, onImport = {}, onCheckOnline = {}, onPreview = {}, onDelete = {})
}

private fun reader(card: ReaderCard? = null, unknown: UnknownCard? = null, error: String? = null): @Composable (() -> Unit) -> Unit = {
    ReaderDataView(card = card, unknown = unknown, error = error, onScanNext = {}, onAdd = {}, onReadOcr = {})
}

private fun toast(text: String): (android.content.Context) -> Unit = { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }

/** All entries of the gallery, in the order of the list. */
val galleryEntries: List<GalleryEntry> = listOf(
    // ── Home ──
    GalleryEntry("home-ready", "Home", "Setup ready", title = null) { home(SetupState(true, true))({}) },
    GalleryEntry("home-no-amazon", "Home", "Amazon Music not installed", title = null) { home(SetupState(false, true))({}) },
    GalleryEntry("home-no-access", "Home", "Notification access missing", title = null) { home(SetupState(true, false))({}) },
    GalleryEntry("home-nothing", "Home", "Both missing", title = null) { home(SetupState(false, false))({}) },
    GalleryEntry("home-crash", "Home", "Crash dialog", title = "Preview") { onBack -> CrashDialog(SAMPLE_TRACE, onClose = onBack) },
    GalleryEntry("toast-export-empty", "Home", "Toast: nothing to export", action = toast("Nothing to export yet. Use \"Edit Card\" on a card first.")),
    GalleryEntry("toast-export-failed", "Home", "Toast: export did not work", action = toast("The export did not work.")),

    // ── Play Mode, camera ──
    GalleryEntry("camera", "Play Mode, camera", "Camera view", title = "Play Mode") { CameraView(granted = true) },
    GalleryEntry("camera-no-permission", "Play Mode, camera", "Camera without permission", title = "Play Mode") { CameraView(granted = false) },

    // ── Play and Quiz result ──
    GalleryEntry("sending", PLAY, "Sending", title = "Play Mode") { ResultPreview(sending) },
    GalleryEntry("sending-status", PLAY, "Sending with status line", title = "Play Mode") { ResultPreview(sending.copy(status = "Loading the song ...")) },
    GalleryEntry("sending-cancel", PLAY, "Sending with Cancel", title = "Play Mode") { ResultPreview(sending.copy(status = "Searching ...", canCancel = true)) },
    GalleryEntry("unknown-known-set", PLAY, "Unknown card, set on the phone", title = "Play Mode") { ResultPreview(unknown(UnknownCardTexts.card(12, SET_NAME, true))) },
    GalleryEntry("unknown-unknown-set", PLAY, "Unknown card, set not on the phone", title = "Play Mode") { ResultPreview(unknown(UnknownCardTexts.card(12, "hitster-xx-aaaa0001", false))) },
    GalleryEntry("unknown-qr", PLAY, "Unknown QR code", title = "Play Mode") { ResultPreview(unknown(UnknownCardTexts.notHitster())) },
    GalleryEntry("success-hidden", PLAY, "Song hidden", title = "Play Mode") { ResultPreview(playing) },
    GalleryEntry("success-revealed", PLAY, "Song revealed", title = "Play Mode") { ResultPreview(playing.copy(revealed = true)) },
    GalleryEntry("success-quiz", PLAY, "Song revealed, quiz (no OCR)", title = "Quiz Mode") { ResultPreview(playing.copy(revealed = true), quiz = true) },
    GalleryEntry("success-info", PLAY, "With info line", title = "Quiz Mode") { ResultPreview(playing.copy(info = "All cards played, starting again"), quiz = true) },
    GalleryEntry("success-ended", PLAY, "Song ended", title = "Play Mode") { ResultPreview(playing.copy(songEnded = true, position = 210f)) },
    GalleryEntry("success-stopped", PLAY, "Stopped", title = "Play Mode") { ResultPreview(playing.copy(playing = false, stopped = true, position = 0f)) },
    GalleryEntry("success-control-error", PLAY, "Control error", title = "Play Mode") { ResultPreview(playing.copy(controlError = "Amazon Music does not react: timeout")) },
    GalleryEntry("success-stopping", PLAY, "Stopping the song", title = "Play Mode") { ResultPreview(playing.copy(stopping = true)) },
    GalleryEntry("failed-not-installed", PLAY, "Failed: Amazon Music not installed", title = "Play Mode") { ResultPreview(failed("The Amazon Music app is not installed on this phone.")) },
    GalleryEntry("failed-no-access", PLAY, "Failed: notification access", title = "Play Mode") { ResultPreview(failed("Please give this app notification access (Home screen, Setup).")) },
    GalleryEntry("failed-not-played", PLAY, "Failed: did not play", title = "Play Mode") { ResultPreview(failed("Amazon Music did not play the song.")) },
    GalleryEntry("failed-nothing", PLAY, "Failed: nothing to play", title = "Play Mode") { ResultPreview(failed("Nothing to play.")) },
    GalleryEntry("failed-unexpected", PLAY, "Failed: unexpected error", title = "Play Mode") { ResultPreview(failed("Unexpected error in the play path: IllegalStateException: sample")) },
    GalleryEntry("failed-quiz-empty", PLAY, "Failed: quiz without songs", title = "Quiz Mode") { ResultPreview(failed("The selected card sets have no songs. Go back and pick other sets."), quiz = true) },
    GalleryEntry("failed-default", PLAY, "Failed: no message", title = "Play Mode") { ResultPreview(failed(null)) },

    // ── Quiz sets ──
    GalleryEntry("quiz-sets", "Quiz Mode, sets", "Some sets ticked", title = "Quiz Mode") { QuizSetsPreview(someSets, setOf(someSets[0].file)) },
    GalleryEntry("quiz-sets-all", "Quiz Mode, sets", "All sets ticked", title = "Quiz Mode") { QuizSetsPreview(someSets, someSets.map { it.file }.toSet()) },
    GalleryEntry("quiz-sets-none", "Quiz Mode, sets", "Nothing ticked (Start off)", title = "Quiz Mode") { QuizSetsPreview(someSets, emptySet()) },
    GalleryEntry("quiz-sets-empty", "Quiz Mode, sets", "No sets", title = "Quiz Mode") { QuizSetsPreview(emptyList(), emptySet()) },

    // ── Reader ──
    GalleryEntry("reader-camera", "Reader Mode", "Camera view", title = "Reader Mode") { CameraView(granted = true) },
    GalleryEntry("reader-camera-no-permission", "Reader Mode", "Camera without permission", title = "Reader Mode") { CameraView(granted = false) },
    GalleryEntry("reader-card", "Reader Mode", "Card", title = "Reader Mode") { reader(card = sampleCard)({}) },
    GalleryEntry("reader-card-modified", "Reader Mode", "Card with a modification", title = "Reader Mode") { reader(card = sampleCard.copy(corrected = true))({}) },
    GalleryEntry("reader-unknown-known-set", "Reader Mode", "Unknown card, set on the phone", title = "Reader Mode") {
        reader(unknown = UnknownCard(PendingCard(SET_FILE, 400), true, SET_NAME))({})
    },
    GalleryEntry("reader-unknown-unknown-set", "Reader Mode", "Unknown card, set not on the phone", title = "Reader Mode") {
        reader(unknown = UnknownCard(PendingCard("hitster-xx-aaaa0001.csv", 5), false, "hitster-xx-aaaa0001"))({})
    },
    GalleryEntry("reader-error-qr", "Reader Mode", "Error: foreign QR code", title = "Reader Mode") {
        reader(error = "This app does not know this QR code: https://example.org/x")({})
    },
    GalleryEntry("reader-error-set", "Reader Mode", "Error: set file not readable", title = "Reader Mode") {
        reader(error = "The card set $SET_FILE is missing: sample error. Card: https://hitstergame.com/de/00075/82")({})
    },

    // ── OCR ──
    GalleryEntry("ocr-waiting", "OCR page", "Waiting for pictures", title = "Scan card") { OcrPage(OcrPhase.WAITING) },
    GalleryEntry("ocr-analysing", "OCR page", "Analysing", title = "Scan card") { OcrPage(OcrPhase.ANALYSING) },
    GalleryEntry("ocr-failed", "OCR page", "Could not read", title = "Scan card") { OcrPage(OcrPhase.FAILED) },
    GalleryEntry("ocr-no-permission", "OCR page", "Without permission", title = "Scan card") { OcrPage(OcrPhase.WAITING, granted = false) },

    // ── Dialogs ──
    GalleryEntry("dialog-edit", "Dialogs", "Edit Card", title = "Preview") { onBack -> EditPreview("Edit Card", withOcr = true, onBack = onBack) },
    GalleryEntry("dialog-edit-quiz", "Dialogs", "Edit Card in the quiz (no OCR)", title = "Preview") { onBack -> EditPreview("Edit Card", withOcr = false, onBack = onBack) },
    GalleryEntry("dialog-edit-invalid", "Dialogs", "Edit Card, invalid Amazon ID", title = "Preview") { onBack -> EditPreview("Edit Card", withOcr = true, amazonId = "not an id", onBack = onBack) },
    GalleryEntry("dialog-add", "Dialogs", "Add Card", title = "Preview") { onBack -> EditPreview("Add Card", withOcr = true, empty = true, onBack = onBack) },
    GalleryEntry("dialog-edition", "Dialogs", "Name of a new card set", title = "Preview") { onBack -> EditionDialog(key = "sample", onContinue = { onBack() }, onCancel = onBack) },

    // ── Card Config ──
    GalleryEntry("config-list", "Card Config", "List of sets", title = "Card Config") { cardConfig(CardConfigUiState(csvFiles = files))({}) },
    GalleryEntry("config-empty", "Card Config", "No sets", title = "Card Config") { cardConfig(CardConfigUiState())({}) },
    GalleryEntry("config-importing", "Card Config", "Import running", title = "Card Config") { cardConfig(CardConfigUiState(isAddingFile = true, csvFiles = files))({}) },
    GalleryEntry("config-message", "Card Config", "Import message", title = "Card Config") { cardConfig(CardConfigUiState(importMessage = "Imported 2 files", csvFiles = files))({}) },
    GalleryEntry("config-error", "Card Config", "Error message", title = "Card Config") { cardConfig(CardConfigUiState(error = "Error removing file: sample", csvFiles = files))({}) },

    // ── CSV preview ──
    GalleryEntry("csv-content", "CSV preview", "File content", title = "Preview: $SET_FILE") { CSVPreviewContent(isLoading = false, error = null, content = SAMPLE_CSV) },
    GalleryEntry("csv-loading", "CSV preview", "Loading", title = "Preview: $SET_FILE") { CSVPreviewContent(isLoading = true, error = null, content = null) },
    GalleryEntry("csv-missing", "CSV preview", "File not found", title = "Preview: $SET_FILE") { CSVPreviewContent(isLoading = false, error = null, content = "File not found") },
    GalleryEntry("csv-error", "CSV preview", "Error", title = "Preview: $SET_FILE") { CSVPreviewContent(isLoading = false, error = "Failed to load file", content = null) },

    // ── the real screens (nothing to fake there) ──
    GalleryEntry("options", "Real screens", "Options") { onBack -> OptionsScreen(onNavigateBack = onBack) },
    GalleryEntry("help", "Real screens", "Help") { onBack -> HelpScreen(onNavigateBack = onBack) },
    GalleryEntry("about", "Real screens", "About") { onBack -> AboutScreen(onNavigateBack = onBack) },
    GalleryEntry("licenses", "Real screens", "Open Source Licenses") { onBack -> LicensesScreen(onNavigateBack = onBack) }
)
