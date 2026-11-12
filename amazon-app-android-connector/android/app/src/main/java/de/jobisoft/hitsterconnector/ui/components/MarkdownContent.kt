package de.jobisoft.hitsterconnector.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

/** Renders a parsed Markdown document. Taken from the upstream changelog screen. */
@Composable
fun MarkdownContent(node: Node) {
    var currentNode: Node? = node.firstChild
    
    while (currentNode != null) {
        when (currentNode) {
            is Heading -> {
                val text = extractText(currentNode)
                val style = when (currentNode.level) {
                    1 -> MaterialTheme.typography.headlineLarge
                    2 -> MaterialTheme.typography.headlineMedium
                    3 -> MaterialTheme.typography.headlineSmall
                    else -> MaterialTheme.typography.titleMedium
                }
                Text(
                    text = text,
                    style = style,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            is Paragraph -> {
                Text(
                    text = extractStyledText(currentNode),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            is BulletList -> {
                RenderBulletList(currentNode)
            }
            is FencedCodeBlock -> {
                val codeBlock = currentNode
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = codeBlock.literal,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            else -> {
                // Handle other node types if needed
            }
        }
        currentNode = currentNode.next
    }
}

@Composable
fun RenderBulletList(bulletList: BulletList, indentLevel: Int = 0) {
    var item = bulletList.firstChild
    val indentDp = (16 + indentLevel * 16).dp
    
    while (item != null) {
        if (item is ListItem) {
            Column(
                modifier = Modifier.padding(start = indentDp, bottom = 4.dp)
            ) {
                // Render the list item content recursively
                RenderListItemContent(item, indentLevel)
            }
        }
        item = item.next
    }
}

@Composable
fun RenderListItemContent(listItem: ListItem, indentLevel: Int) {
    var child = listItem.firstChild
    
    // First, collect all non-list children for the main text
    val textChildren = mutableListOf<Node>()
    var nestedBulletList: BulletList? = null
    
    while (child != null) {
        when (child) {
            is BulletList -> {
                nestedBulletList = child
            }
            else -> {
                textChildren.add(child)
            }
        }
        child = child.next
    }
    
    // Render the main item text
    if (textChildren.isNotEmpty()) {
        Row {
            Text(
                text = "• ",
                style = MaterialTheme.typography.bodyMedium
            )
            Column(modifier = Modifier.weight(1f)) {
                textChildren.forEach { childNode ->
                    when (childNode) {
                        is Paragraph -> {
                            Text(
                                text = extractStyledText(childNode),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        is Text -> {
                            Text(
                                text = childNode.literal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        else -> {
                            // Handle other inline nodes recursively
                            MarkdownContent(childNode)
                        }
                    }
                }
            }
        }
    }
    
    // Render nested list if present (indented further)
    nestedBulletList?.let { nested ->
        RenderBulletList(nested, indentLevel + 1)
    }
}

fun extractText(node: Node): String {
    val sb = StringBuilder()
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> sb.append(child.literal)
            is Code -> sb.append(child.literal)
            is Emphasis -> sb.append(extractText(child))
            is StrongEmphasis -> sb.append(extractText(child))
            else -> sb.append(extractText(child))
        }
        child = child.next
    }
    return sb.toString()
}

@Composable
fun extractStyledText(node: Node) = buildAnnotatedString {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> append(child.literal)
            is Code -> {
                withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                    append(child.literal)
                }
            }
            is Emphasis -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(extractText(child))
                }
            }
            is StrongEmphasis -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(extractText(child))
                }
            }
            else -> append(extractText(child))
        }
        child = child.next
    }
}
