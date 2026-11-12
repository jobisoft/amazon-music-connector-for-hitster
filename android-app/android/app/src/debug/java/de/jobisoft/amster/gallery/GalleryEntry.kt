package de.jobisoft.amster.gallery

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * One entry of the screens gallery. [page] is shown when the magnifier is pressed ([onBack] closes it again), [title] is the title of the
 * screen it imitates (null: the page has its own frame, like the real Options screen). An entry with an [action] runs it instead (a toast).
 */
class GalleryEntry(
    val id: String,
    val group: String,
    val name: String,
    val title: String? = null,
    val action: ((Context) -> Unit)? = null,
    val page: @Composable (onBack: () -> Unit) -> Unit = {}
)
