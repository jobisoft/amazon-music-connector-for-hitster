package de.jobisoft.amster.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.theme.Dimens

/**
 * The frame of a screen: the gradient, a title row and the content in a column that is at most
 * [Dimens.ContentMaxWidth] wide, centered. Every screen uses it, so they look alike. Going back is the job of
 * the system (back gesture or button), there is no back button on the screens.
 */
@Composable
fun ScreenScaffold(
    title: String?,
    modifier: Modifier = Modifier,
    contentMaxWidth: Dp = Dimens.ContentMaxWidth,
    content: @Composable BoxScope.() -> Unit
) {
    AmsterBackground(modifier) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = Dimens.Space24, vertical = Dimens.Space16)
                )
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxSize(), content = content)
            }
        }
    }
}
