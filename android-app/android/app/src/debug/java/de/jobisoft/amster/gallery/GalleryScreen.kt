package de.jobisoft.amster.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import de.jobisoft.amster.ui.LocalSampleMode
import de.jobisoft.amster.ui.components.GlassCardFull
import de.jobisoft.amster.ui.components.ScreenScaffold
import de.jobisoft.amster.ui.theme.AmsterTheme

private const val LIST_ROUTE = "gallery"
private const val PAGE_ROUTE = "gallery/{id}"

/** The gallery of the debug app: the list at `gallery` and a page per entry at `gallery/<id>`. */
fun NavGraphBuilder.galleryRoutes(navController: NavHostController) {
    composable(LIST_ROUTE) {
        GalleryList(onOpen = { navController.navigate("gallery/${it.id}") })
    }
    composable(PAGE_ROUTE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        val sample = galleryEntries.firstOrNull { it.id == entry.arguments?.getString("id") }
        if (sample != null) GalleryPage(sample, onBack = { navController.popBackStack() })
    }
}

/** The function that opens the gallery (the tile of the main screen). */
fun galleryEntry(navController: NavHostController): (() -> Unit)? = { navController.navigate(LIST_ROUTE) }

/** The list: one panel like the file list of Card Config, the groups as small headings, a magnifier at the end of every line. */
@Composable
private fun GalleryList(onOpen: (GalleryEntry) -> Unit) {
    val context = LocalContext.current
    ScreenScaffold(title = "Screens (debug)") {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Every screen and state of the app with sample data. Nothing is sent, played or saved here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlassCardFull(modifier = Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 4.dp)) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    var group: String? = null
                    val rows = galleryEntries.map { entry ->
                        val header = if (entry.group != group) entry.group else null
                        group = entry.group
                        entry to header
                    }
                    items(rows, key = { it.first.id }) { (entry, header) ->
                        if (header != null) {
                            Text(
                                header,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        GalleryRow(entry) {
                            val action = entry.action
                            if (action != null) action(context) else onOpen(entry)
                        }
                        HorizontalDivider(color = AmsterTheme.extras.glassBorder)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GalleryRow(entry: GalleryEntry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onOpen) {
            Icon(Icons.Default.Search, contentDescription = "Show ${entry.name}")
        }
    }
}

/** One entry as a page: the real composable with sample data, in the frame of the screen it imitates. Nothing is written. */
@Composable
private fun GalleryPage(entry: GalleryEntry, onBack: () -> Unit) {
    CompositionLocalProvider(LocalSampleMode provides true) {
        val title = entry.title
        if (title != null) {
            ScreenScaffold(title = title) { entry.page(onBack) }
        } else {
            entry.page(onBack)
        }
    }
}
