package de.jobisoft.amster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.theme.AmsterTheme
import de.jobisoft.amster.ui.theme.Dimens
import de.jobisoft.amster.ui.theme.GlassShape

/**
 * A translucent card: a light fill over the gradient, a faint sheen from top to bottom and a hairline border.
 * [tint] and [borderColor] give it a meaning (success, error). With [onClick] the whole card is a button, with [onLongClick] as well
 * it also reacts to a long press.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    tint: Color? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(Dimens.Space16),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    val extras = AmsterTheme.extras
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.0f)))
    var card = modifier
        .clip(GlassShape)
        .background(tint ?: extras.glassFill)
        .background(sheen)
        .border(1.dp, borderColor ?: extras.glassBorder, GlassShape)
    if (onLongClick != null) {
        card = card.combinedClickable(
            onClickLabel = onClickLabel,
            onLongClickLabel = onLongClickLabel,
            role = Role.Button,
            onLongClick = onLongClick,
            onClick = onClick ?: {}
        )
    } else if (onClick != null) {
        card = card.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    }
    Column(
        modifier = card.padding(contentPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content
    )
}

/** A card as wide as its parent. */
@Composable
fun GlassCardFull(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    tint: Color? = null,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(Dimens.Space16),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) = GlassCard(
    modifier = modifier.fillMaxWidth(),
    onClick = onClick,
    onClickLabel = onClickLabel,
    onLongClick = onLongClick,
    onLongClickLabel = onLongClickLabel,
    tint = tint,
    borderColor = borderColor,
    contentPadding = contentPadding,
    verticalArrangement = verticalArrangement,
    horizontalAlignment = horizontalAlignment,
    content = content
)
