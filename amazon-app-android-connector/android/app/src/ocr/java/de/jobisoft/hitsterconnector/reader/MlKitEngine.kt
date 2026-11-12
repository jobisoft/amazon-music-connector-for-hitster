package de.jobisoft.hitsterconnector.reader

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The OCR engine: Google ML Kit text recognition for the Latin alphabet (the model is in the app, no Play Services needed, no network).
 * It is not free software (the terms of Google). It is in the debug build, and in a production build made with the flag `ocr`.
 */
object MlKitEngine {
    fun create(context: Context): TextReader? = MlKitReader()
}

private class MlKitReader : TextReader {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The Latin model knows umlauts, accents and the letters of all our languages. */
    override suspend fun read(bitmap: Bitmap): List<OcrLine> {
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox
                val clean = line.text.trim()
                if (clean.isEmpty()) continue
                // the corners of the line in the direction of its own text: the first to the second corner is the direction of reading
                val corners = line.cornerPoints
                val angle = if (corners != null && corners.size >= 2) {
                    Math.toDegrees(Math.atan2((corners[1].y - corners[0].y).toDouble(), (corners[1].x - corners[0].x).toDouble()))
                } else {
                    0.0
                }
                lines.add(OcrLine(clean, box?.height() ?: 0, box?.left ?: 0, box?.top ?: 0, box?.right ?: 0, box?.bottom ?: 0, angle))
            }
        }
        // the lines of ML Kit come block by block, the parser wants them from the top to the bottom
        val sorted = lines.sortedBy { it.centerY }
        DebugLog.log("ocr", "mlkit read", mapOf("lines" to sorted.size, "image" to "${bitmap.width}x${bitmap.height}"))
        return sorted
    }
}
