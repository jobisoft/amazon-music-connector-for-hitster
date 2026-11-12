package de.jobisoft.amster.ui.screens

import de.jobisoft.amster.ui.components.SecondaryButton
import de.jobisoft.amster.ui.components.Section
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jobisoft.amster.ui.components.ScreenScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    ScreenScaffold(title = "About") {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // App Name and Version
                val context = LocalContext.current
                val versionName = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: PackageManager.NameNotFoundException) {
                    "Unknown"
                }
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(de.jobisoft.amster.R.string.app_name),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Play Hitster with an Amazon Music account",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Version $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                HorizontalDivider()
                
                // Source Section
                Section(
                    title = "Source code",
                    content = "This app is open source. It is based on SubSonicSter."
                )

                SecondaryButton(
                    text = "View on GitHub",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/jobisoft/amster".toUri())
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                SecondaryButton(
                    text = "SubSonicSter on Codeberg",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://codeberg.org/langhaarschneider/SubSonicSter".toUri())
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Section(
                    title = "Card data",
                    content = "The card data is based in part on the SongSeeker Hitster playlists by Andreas Gruber (MIT License, Copyright 2024 Andreas Gruber)."
                )

                SecondaryButton(
                    text = "SongSeeker playlists on GitHub",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/andygruber/songseeker-hitster-playlists".toUri())
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Legal Disclaimer Section
                Section(
                    title = "Legal Disclaimer",
                    content = "Amster is an independent, unofficial project. It is not affiliated with, endorsed by, sponsored by or associated with Jumbo Spiele GmbH or the Hitster card game, or with Amazon. Hitster and Amazon Music are trademarks of their respective owners. They are named only to say which cards and which music service this app works with. You need your own cards and your own Amazon Music account. This project claims no trademark rights."
                )
                
                HorizontalDivider()
                
                // License Section
                Section(
                    title = "License",
                    content = "This app is based on SubSonicSter by langhaarschneider, which is dual-licensed under the Beerware License (Revision 42) and the MIT License. " +
                            "This app is published under the MIT License.\n\n" +
                            "Permission is granted to use, modify, distribute, and sell copies of the software, provided the copyright notice and permission notice are included in all copies."
                )
            }
            
        }
    }
}
