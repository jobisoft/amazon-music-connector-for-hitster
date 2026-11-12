package de.jobisoft.amster

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.jobisoft.amster.data.debug.DebugLog
import de.jobisoft.amster.util.CrashReporter
import de.jobisoft.amster.util.VolumeDuck

@HiltAndroidApp
class AmsterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // first of all: a crash from here on leaves its stack trace in last_crash.txt
        CrashReporter.install(this)
        DebugLog.init(this)
        DebugLog.logStart(this)
        // the app was killed while the volume level was 0: put it back
        VolumeDuck.restoreAtStart(this)
        // the crash of the last run, once, to the debug log (the Home screen shows it in a dialog in every build)
        CrashReporter.unsent(this)?.let { DebugLog.log("crash", "last crash", mapOf("text" to it)) }
    }
}
