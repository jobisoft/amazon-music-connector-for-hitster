package de.jobisoft.amster.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * True while a screen is shown with sample data (the screens gallery of the debug app): the edit overlay does not open and nothing is
 * written. False everywhere else.
 */
val LocalSampleMode = compositionLocalOf { false }
