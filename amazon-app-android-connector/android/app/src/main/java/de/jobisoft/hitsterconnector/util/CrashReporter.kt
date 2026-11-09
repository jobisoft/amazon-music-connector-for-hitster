package de.jobisoft.hitsterconnector.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The text of a crash report, without Android: easy to test. */
object CrashReport {
    /** A crash text is cut to this size, the beginning with the cause is what matters. */
    const val MAX_CHARS = 20_000

    fun stackTrace(t: Throwable): String {
        val writer = StringWriter()
        t.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    /** What went wrong, where and in which app: time, version, device, thread, then the stack trace with all causes. */
    fun format(time: String, app: String, device: String, thread: String, t: Throwable): String {
        val text = "Crash at $time\nApp: $app\nDevice: $device\nThread: $thread\n\n" + stackTrace(t)
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n... (cut)"
    }
}

/**
 * Crash handler for every build: the stack trace of an uncaught exception is written to
 * `filesDir/last_crash.txt` before the process dies. The next start shows it in a dialog on the Home
 * screen (copy or share, also in the production build) and sends it to the debug log (debug build).
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_SENT = "sent_modified"

    private fun file(context: Context) = File(context.filesDir, FILE)

    /**
     * Writes the report of [throwable] to `last_crash.txt`: used by the handler of uncaught exceptions,
     * and for an exception the app catches itself but that should not pass unseen (see [record]).
     */
    fun record(context: Context, threadName: String, throwable: Throwable) {
        try {
            val app = context.applicationContext
            val version = try {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
            } catch (_: Exception) {
                "?"
            }
            val text = CrashReport.format(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                version,
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                threadName,
                throwable
            )
            file(app).writeText(text)
        } catch (_: Throwable) {
            // nothing more can be done here
        }
    }

    /** Installs the handler. The handler that was there before (the system's) still runs afterwards. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(app, thread.name, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The text of the last crash, null if there was none (or the user closed it). */
    fun read(context: Context): String? = try {
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }

    /** The text of a crash that was not sent to the debug log yet, once. */
    fun unsent(context: Context): String? {
        val f = file(context)
        val text = read(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modified = f.lastModified()
        if (prefs.getLong(KEY_SENT, 0L) == modified) return null
        prefs.edit().putLong(KEY_SENT, modified).apply()
        return text
    }
}
