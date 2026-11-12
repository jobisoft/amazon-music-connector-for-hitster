package de.jobisoft.amster.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import de.jobisoft.amster.reader.ReaderScreen

/** The route of the reader mode. It is in every build: users report wrong years, titles and artists. */
const val READER_ROUTE = "reader"

fun NavGraphBuilder.readerRoute(navController: NavHostController) {
    composable(READER_ROUTE) { ReaderScreen(onNavigateBack = { navController.popBackStack() }) }
}
