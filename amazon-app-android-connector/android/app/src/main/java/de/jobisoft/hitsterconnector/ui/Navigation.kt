package de.jobisoft.hitsterconnector.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import de.jobisoft.hitsterconnector.ui.screens.AboutScreen
import de.jobisoft.hitsterconnector.ui.screens.OptionsScreen
import de.jobisoft.hitsterconnector.ui.screens.CardConfigScreen
import de.jobisoft.hitsterconnector.ui.screens.CSVPreviewScreen
import de.jobisoft.hitsterconnector.ui.screens.HelpScreen
import de.jobisoft.hitsterconnector.ui.screens.HomeScreen
import de.jobisoft.hitsterconnector.ui.screens.LicensesScreen
import de.jobisoft.hitsterconnector.ui.screens.QuizScreen
import de.jobisoft.hitsterconnector.ui.screens.QuizSetsScreen
import de.jobisoft.hitsterconnector.ui.screens.ScannerScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CardConfig : Screen("card_config")
    object CSVPreview : Screen("csv_preview/{fileName}") {
        fun createRoute(fileName: String) = "csv_preview/$fileName"
    }
    object Scanner : Screen("scanner")
    object QuizSets : Screen("quiz_sets")
    object Quiz : Screen("quiz/{files}") {
        /** The files comma-joined and Uri-encoded, or the value "all". */
        fun createRoute(files: List<String>) = "quiz/" + Uri.encode(files.joinToString(","))
    }
    object Options : Screen("options")
    object Help : Screen("help")
    object Licenses : Screen("licenses")
    object About : Screen("about")
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToCardConfig = { navController.navigate(Screen.CardConfig.route) },
                onNavigateToScanner = { navController.navigate(Screen.Scanner.route) },
                onNavigateToQuiz = { navController.navigate(Screen.QuizSets.route) },
                onNavigateToReader = { navController.navigate(READER_ROUTE) },
                onNavigateToOptions = { navController.navigate(Screen.Options.route) },
                onNavigateToHelp = { navController.navigate(Screen.Help.route) },
                onNavigateToLicenses = { navController.navigate(Screen.Licenses.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) }
            )
        }
    
        composable(Screen.CardConfig.route) {
            CardConfigScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPreview = { fileName ->
                    navController.navigate(Screen.CSVPreview.createRoute(fileName))
                }
            )
        }
        
        composable(
            route = Screen.CSVPreview.route,
            arguments = listOf(navArgument("fileName") { type = NavType.StringType })
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
            CSVPreviewScreen(
                fileName = fileName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Scanner.route) {
            ScannerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.QuizSets.route) {
            QuizSetsScreen(
                onStart = { files -> navController.navigate(Screen.Quiz.createRoute(files)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Quiz.route,
            arguments = listOf(navArgument("files") { type = NavType.StringType })
        ) { backStackEntry ->
            val files = (backStackEntry.arguments?.getString("files") ?: "")
                .split(",").filter { it.isNotBlank() }
            QuizScreen(
                files = files,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        readerRoute(navController) // the reader route, in every build

        composable(Screen.Options.route) {
            OptionsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Help.route) {
            HelpScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Licenses.route) {
            LicensesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
