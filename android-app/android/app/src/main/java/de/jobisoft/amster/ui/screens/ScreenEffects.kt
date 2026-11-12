package de.jobisoft.amster.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.jobisoft.amster.util.HideStatusBarSetting

/** Keeps the screen on while a card is being handled. */
@Composable
internal fun KeepScreenOn(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val window = (context as? Activity)?.window
        if (active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Hides the status bar while a card song is on the screen ([songOnScreen]) and the switch "Hide the status bar while
 * playing" on the Options screen is on. Some phones show the title of the song in the status bar, which would give the card
 * away. A swipe from the top shows the bar for a moment. The bar comes back when the screen is left. The navigation bar
 * stays as it is.
 */
@Composable
internal fun HideStatusBar(songOnScreen: Boolean) {
    val context = LocalContext.current
    val enabled = remember { HideStatusBarSetting.isEnabled(context) }
    val hide = HideStatusBarSetting.shouldHide(enabled, songOnScreen)
    DisposableEffect(hide) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (hide) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
