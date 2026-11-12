package de.jobisoft.amster.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.jobisoft.amster.util.ScanTimeout
import kotlinx.coroutines.delay

/** The camera permission: [granted] now, [request] asks again. */
class CameraPermission(val granted: Boolean, val request: () -> Unit)

/** The state of the camera permission. It is asked for once when the screen opens. */
@Composable
fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    return CameraPermission(granted) { launcher.launch(Manifest.permission.CAMERA) }
}

/**
 * The camera timeout of the camera view (see [ScanTimeout]): it starts when [active] turns on and counts the seconds
 * down. When the time is up [onExpired] is called once. Returns the seconds left.
 */
@Composable
fun rememberScanCountdown(active: Boolean, onExpired: () -> Unit): Int {
    var secondsLeft by remember { mutableIntStateOf(ScanTimeout.SECONDS) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            secondsLeft = ScanTimeout.remainingSeconds(startedAt, now)
            if (ScanTimeout.expired(startedAt, now)) {
                onExpired()
                return@LaunchedEffect
            }
            delay(250)
        }
    }
    return secondsLeft
}

/**
 * The camera view of Play Mode and Reader Mode: the square window with the camera and under it, always the same distance
 * away, the line "Scan card (Ns)" (plain text, not shown with [secondsLeft] null, while the OCR analysis runs). The [below] slot
 * follows under it (the OCR page has its banner there). Without the permission the window asks for it and nothing is shown under it.
 */
@Composable
fun ScanCameraView(
    permission: CameraPermission,
    secondsLeft: Int?,
    camera: @Composable BoxScope.() -> Unit,
    below: (@Composable () -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SquareScanWindow(modifier = Modifier.fillMaxWidth()) {
            if (permission.granted) {
                camera()
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Camera permission is required to scan QR codes")
                    Spacer(modifier = Modifier.height(16.dp))
                    PrimaryButton(text = "Grant Permission", onClick = permission.request)
                }
            }
        }
        if (permission.granted) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (secondsLeft != null) {
                    Text(
                        "Scan card (${secondsLeft}s)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (below != null) Spacer(modifier = Modifier.height(20.dp))
                }
                below?.invoke()
            }
        }
    }
}
