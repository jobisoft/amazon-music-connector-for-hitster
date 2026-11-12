package de.jobisoft.amster.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.SecondaryButton
import de.jobisoft.amster.ui.theme.AmsterTheme

/** A red box "Error" with a cross, the message in a box of its own below it. */
@Composable
internal fun ErrorBoxes(message: String) {
    StatusCard(title = "Error", isError = true)
    Spacer(modifier = Modifier.height(8.dp))
    GlassCardFull(
        tint = MaterialTheme.colorScheme.errorContainer,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
    ) {
        Text(
            text = linkify(message, MaterialTheme.colorScheme.secondary),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
    trustedUrl(message)?.let { url ->
        val context = LocalContext.current
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryButton(
            text = "Open the card page",
            onClick = {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** A coloured glass box. Green: the title and, below it, the card set. Red: the title with a cross behind it. */
@Composable
internal fun StatusCard(title: String, isError: Boolean, subtitle: String? = null) {
    if (isError) {
        // red box: the word and a cross behind it
        val tint = MaterialTheme.colorScheme.onErrorContainer
        GlassCardFull(
            tint = MaterialTheme.colorScheme.errorContainer,
            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(imageVector = Icons.Filled.Close, contentDescription = null, tint = tint)
            }
        }
    } else {
        // green box: our text on the first line, the card set on the second, no check mark
        val extras = AmsterTheme.extras
        GlassCardFull(
            tint = extras.successContainer,
            borderColor = extras.success.copy(alpha = 0.4f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = if (subtitle == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = extras.success,
                    textAlign = TextAlign.Center
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = extras.success,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * A card that cannot be played or shown, as plain text without a box: the [title] ("Unknown card 12") bigger, the [lines] under it.
 * Play Mode and Reader Mode both use it, so the screens do not drift apart.
 */
@Composable
internal fun UnknownCardView(title: String, lines: List<String>) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(24.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
