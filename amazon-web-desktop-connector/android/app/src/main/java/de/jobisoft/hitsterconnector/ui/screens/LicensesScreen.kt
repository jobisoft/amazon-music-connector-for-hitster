package de.jobisoft.hitsterconnector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.jobisoft.hitsterconnector.ui.components.BackButton
import de.jobisoft.hitsterconnector.ui.components.MarkdownContent
import org.commonmark.parser.Parser
import org.commonmark.node.Node
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var markdownNodes by remember { mutableStateOf<Node?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(Unit) {
        try {
            val inputStream = context.assets.open("LICENSES_THIRD_PARTY.md")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val markdownText = reader.use { it.readText() }
            
            val parser = Parser.builder().build()
            markdownNodes = parser.parse(markdownText)
        } catch (e: Exception) {
            errorMessage = "Error loading licenses: ${e.message}"
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open Source Licenses") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                when {
                    errorMessage != null -> {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    markdownNodes != null -> {
                        MarkdownContent(markdownNodes!!)
                    }
                    else -> {
                        Text("Loading...")
                    }
                }
            }
            
            // Centered Back button at the bottom
            BackButton(
                onClick = onNavigateBack,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
