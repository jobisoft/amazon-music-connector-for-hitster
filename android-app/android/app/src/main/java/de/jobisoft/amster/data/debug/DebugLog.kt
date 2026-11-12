package de.jobisoft.amster.data.debug

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import de.jobisoft.amster.BuildConfig
import de.jobisoft.amster.util.JsonSafe
import de.jobisoft.amster.util.Safe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Debug channel of the app. Every event goes to logcat and, fire and forget, to the log
 * sink on the developer's PC (tools/debug_sink.py). The app finds the sink by a UDP broadcast
 * ([SinkDiscovery]). Without an answer nothing is sent. Nothing here ever blocks the user interface.
 *
 * Event: {"t": epoch ms, "tag": "...", "msg": "...", "data": {...}}
 */
object DebugLog {
    /** False in the production build: then every function here does nothing. Folded away by R8. */
    const val ENABLED = BuildConfig.DEBUG_CHANNEL

    private const val LOGCAT_TAG = "Amster"
    private const val MAX_BATCH = 40
    private const val RETRY_DISCOVERY_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Safe.handler)
    private val queue by lazy { Channel<JSONObject>(capacity = 3000, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .writeTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .build()
    }
    private val json = "application/json".toMediaType()

    @Volatile
    private var started = false

    /** Address of the sink once found ("http://host:port"), null while there is none. */
    @Volatile
    var sinkUrl: String? = null
        private set

    @Volatile
    private var lastSearch = 0L

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (!ENABLED || started) return
        started = true
        scope.launch {
            while (true) {
                val batch = JSONArray()
                batch.put(queue.receive())
                var count = 1
                while (count < MAX_BATCH) {
                    val more = queue.tryReceive().getOrNull() ?: break
                    batch.put(more)
                    count++
                }
                deliver(batch)
            }
        }
    }

    private fun deliver(batch: JSONArray) {
        try {
            if (sinkUrl == null && System.currentTimeMillis() - lastSearch >= RETRY_DISCOVERY_MS) {
                sinkUrl = SinkDiscovery.find()
                lastSearch = System.currentTimeMillis()
            }
            val url = sinkUrl
            if (url == null) return
            val request = Request.Builder()
                .url(url.trimEnd('/') + "/log")
                .post(batch.toString().toRequestBody(json))
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            sinkUrl = null // the PC may have a new address: search again
        }
    }

    fun log(tag: String, msg: String, data: Map<String, Any?>? = null) {
        if (!ENABLED) return
        // a debug log must never crash the app: whatever goes wrong here is dropped
        try {
            val event = JSONObject()
            event.put("t", System.currentTimeMillis())
            event.put("tag", tag)
            event.put("msg", msg)
            if (data != null) event.put("data", toJson(data))
            Log.d(LOGCAT_TAG, "[$tag] $msg" + (data?.let { " $it" } ?: ""))
            if (started) queue.trySend(event)
        } catch (e: Throwable) {
            Log.w(LOGCAT_TAG, "log event dropped: [$tag] $msg", e)
        }
    }

    /**
     * A picture for the log sink (it saves it in tools/debug_images): the frame that the OCR reads, for example. Fire and forget,
     * dropped when there is no sink. [name] ends in .jpg.
     */
    fun image(name: String, bitmap: android.graphics.Bitmap) {
        if (!ENABLED) return
        val url = sinkUrl ?: return
        try {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            val bytes = out.toByteArray()
            scope.launch {
                try {
                    val request = Request.Builder()
                        .url(url.trimEnd('/') + "/image?name=" + java.net.URLEncoder.encode(name, "UTF-8"))
                        .post(bytes.toRequestBody("image/jpeg".toMediaType()))
                        .build()
                    client.newCall(request).execute().close()
                } catch (_: Exception) {
                    // the next event searches the sink again
                }
            }
        } catch (_: Throwable) {
            // a debug picture must never crash the app
        }
    }

    private fun toJson(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Number -> JsonSafe.number(value)
        is Boolean, is String -> value
        is Map<*, *> -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k.toString(), toJson(v)) } }
        is Iterable<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
        is Array<*> -> JSONArray().also { a -> value.forEach { a.put(toJson(it)) } }
        is Throwable -> JSONObject().also {
            it.put("class", value.javaClass.name)
            it.put("message", value.message)
            it.put("stack", Log.getStackTraceString(value))
        }
        else -> value.toString()
    }

    /** First event of every start of the app: what device, what Amazon Music, what access. */
    fun logStart(context: Context) {
        if (!ENABLED) return
        val amazon: Map<String, Any?> = try {
            val info = context.packageManager.getPackageInfo("com.amazon.mp3", 0)
            mapOf("installed" to true, "versionName" to info.versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            mapOf("installed" to false)
        }
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        log(
            "start", "app started",
            mapOf(
                "appVersion" to app.versionName,
                "applicationId" to context.packageName,
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "amazonMusic" to amazon,
                "notificationAccess" to NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName),
                "sink" to sinkUrl
            )
        )
    }
}
