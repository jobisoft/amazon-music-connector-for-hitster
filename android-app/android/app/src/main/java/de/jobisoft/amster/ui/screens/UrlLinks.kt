package de.jobisoft.amster.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * The first web address in the text, if it is one of Hitster or YouTube. The text comes
 * from a QR code. Anyone can print one, so an address of another site is not offered.
 */
internal fun trustedUrl(text: String): String? {
    val url = Regex("https?://\\S+").find(text)?.value ?: return null
    val host = try {
        java.net.URI(url).host?.lowercase().orEmpty()
    } catch (_: Exception) {
        ""
    }
    val trusted = listOf("hitstergame.com", "hitster.com", "youtube.com", "youtu.be")
        .any { host == it || host.endsWith(".$it") }
    return if (trusted) url else null
}

/** Makes that address tappable inside the text. */
internal fun linkify(text: String, linkColor: Color): AnnotatedString {
    val url = trustedUrl(text) ?: return AnnotatedString(text)
    val start = text.indexOf(url)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            )
        ) {
            append(url)
        }
        append(text.substring(start + url.length))
    }
}
