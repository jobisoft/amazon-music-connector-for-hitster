package de.jobisoft.amster.reader

import android.graphics.Bitmap

/** Reads the text of a picture, line by line. The real reader (Google ML Kit) exists only in builds with OCR. */
interface TextReader {
    suspend fun read(bitmap: Bitmap): List<OcrLine>
}
