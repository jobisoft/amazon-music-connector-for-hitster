package de.jobisoft.hitsterconnector.reader

import android.content.Context

/** The build has no OCR (production build without the build flag `ocr`): there is no text reader, the reader mode keeps its old behavior. */
object MlKitEngine {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): TextReader? = null
}
