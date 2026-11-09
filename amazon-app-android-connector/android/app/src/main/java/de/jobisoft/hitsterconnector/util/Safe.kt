package de.jobisoft.hitsterconnector.util

import android.util.Log
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * For the scopes of the app that live as long as the process (the end guard, the mute timer, the
 * stop on leaving, the debug log): an exception in one of their coroutines is logged and does not
 * take the app down. Work of a screen has its own handling: the play path reports an error box.
 */
object Safe {
    val handler = CoroutineExceptionHandler { _, e ->
        Log.e("HitsterConnector", "uncaught exception in a background coroutine", e)
        DebugLog.log("error", "uncaught exception in a background coroutine", mapOf("error" to e))
    }
}
