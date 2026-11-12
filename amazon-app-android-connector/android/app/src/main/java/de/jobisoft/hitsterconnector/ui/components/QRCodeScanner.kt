package de.jobisoft.hitsterconnector.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CenterCropStrategy
import de.jobisoft.hitsterconnector.data.debug.DebugLog
import de.jobisoft.hitsterconnector.reader.OcrFlow
import kotlinx.coroutines.delay
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultPoint

private const val TAG = "QRCodeScanner"

/**
 * Composable QR code scanner using ZXing (F-Droid compatible)
 */
@Composable
fun QRCodeScanner(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    /** The reader looks for the back of a card: a picture of the square frame goes to [onFrame] about once a second. */
    frameRequests: Boolean = false,
    onFrame: ((Bitmap) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    // Use a state to track if we should be scanning (to avoid capturing isActive in callback)
    val shouldScan = remember { mutableStateOf(isActive) }
    
    // Remember the view to control it from effects
    val barcodeView = remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    // the decoder thread hands a frame to the OCR when this is set (see the effect below)
    val frameWanted = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val currentOnFrame by rememberUpdatedState(onFrame)
    
    // Track if app is in foreground
    var isAppInForeground by remember { mutableStateOf(true) }
    
    // Update shouldScan when isActive changes
    LaunchedEffect(isActive) {
        Log.d(TAG, "isActive changed to: $isActive")
        shouldScan.value = isActive
    }
    
    // Observe lifecycle to pause camera when app goes to background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "Lifecycle: ON_RESUME")
                    isAppInForeground = true
                    if (isActive && hasCameraPermission) {
                        barcodeView.value?.resume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d(TAG, "Lifecycle: ON_PAUSE")
                    isAppInForeground = false
                    barcodeView.value?.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Pictures for the OCR: about once a second the decoder thread is asked to hand over the frame it is just reading. The OCR
    // must not ask the camera for a frame itself: the camera has one slot for the frame callback, a second request would take
    // the place of the one of the QR decoder, and the decoder would never get a frame again.
    LaunchedEffect(frameRequests, onFrame != null) {
        while (frameRequests && onFrame != null) {
            frameWanted.set(true)
            delay(OcrFlow.FRAME_EVERY_MS)
        }
        frameWanted.set(false)
    }

    // Control camera state separately from AndroidView recomposition
    LaunchedEffect(isActive, hasCameraPermission, isAppInForeground) {
        val view = barcodeView.value
        if (view != null) {
            if (isActive && hasCameraPermission && isAppInForeground) {
                Log.d(TAG, "LaunchedEffect: Resuming camera")
                view.resume()
            } else {
                Log.d(TAG, "LaunchedEffect: Pausing camera (isActive=$isActive, hasPerm=$hasCameraPermission, inForeground=$isAppInForeground)")
                view.pause()
            }
        }
    }
    
    AndroidView(
        factory = { ctx ->
            Log.d(TAG, "Creating DecoratedBarcodeView")
            val view = DecoratedBarcodeView(ctx)
            
            // Hide status text completely for cleaner UI
            view.statusView.visibility = View.GONE
            
            // Support all formats used by different card sets.
            val hints: Map<DecodeHintType, Any> = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.AZTEC,
                    BarcodeFormat.DATA_MATRIX
                )
            )
            val formats = listOf(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.AZTEC,
                BarcodeFormat.DATA_MATRIX
            )
            view.barcodeView.decoderFactory = FrameTapDecoderFactory(
                DefaultDecoderFactory(formats, hints, null, 2)
            ) { source ->
                if (frameWanted.compareAndSet(true, false)) {
                    val frame = grayBitmap(source)
                    DebugLog.log("reader", "ocr frame", mapOf("width" to frame.width, "height" to frame.height))
                    view.post { currentOnFrame?.invoke(frame) }
                }
            }
            // the view is square (see SquareScanWindow): the camera image fills it, cropped in the middle, not stretched
            view.barcodeView.previewScalingStrategy = CenterCropStrategy()
            // the frame fills the square view, the card fills the frame
            view.barcodeView.marginFraction = SCAN_FRAME_MARGIN
            view.barcodeView.cameraSettings = CameraSettings().apply {
                isAutoFocusEnabled = true
                focusMode = CameraSettings.FocusMode.CONTINUOUS
                isMeteringEnabled = true
                isExposureEnabled = true
            }
            
            // Set callback for barcode detection
            val callback = object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult?) {
                    Log.d(TAG, ">>> barcodeResult CALLED! result=$result")
                    if (result == null) {
                        Log.w(TAG, ">>> barcodeResult: result is NULL")
                        return
                    }
                    
                    val qrCode = result.text?.trim()
                    if (qrCode == null || qrCode.isBlank()) {
                        Log.w(TAG, ">>> barcodeResult: text is NULL or BLANK")
                        return
                    }
                    
                    Log.i(TAG, ">>> CODE SUCCESSFULLY DECODED (${result.barcodeFormat}): '$qrCode'")
                    Log.d(TAG, ">>> shouldScan=${shouldScan.value}")
                    
                    if (shouldScan.value) {
                        Log.i(TAG, ">>> Calling onQRCodeScanned callback")
                        // Call the callback on main thread
                        view.post {
                            onQRCodeScanned(qrCode)
                        }
                    } else {
                        Log.w(TAG, ">>> Scanner is NOT active, ignoring scan")
                    }
                }
                
                override fun possibleResultPoints(resultPoints: List<ResultPoint>?) {
                    if (resultPoints != null && resultPoints.isNotEmpty()) {
                        Log.d(TAG, "possibleResultPoints: ${resultPoints.size} points detected at ${resultPoints.map { "(${it.x}, ${it.y})" }}")
                    }
                }
            }
            
            Log.d(TAG, "Setting up decodeContinuous with callback")
            view.decodeContinuous(callback)
            
            // Store view reference for LaunchedEffect
            barcodeView.value = view
            
            Log.d(TAG, "DecoratedBarcodeView setup complete")
            view
        },
        // Empty update block - we control the camera state via LaunchedEffect instead
        update = { },
        modifier = modifier.fillMaxSize()
    )
    
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "Disposing scanner")
            barcodeView.value?.pause()
            barcodeView.value = null
        }
    }
}

/** The picture of a frame that the QR decoder is reading (already cut to the square frame and turned upright), in grey. */
private fun grayBitmap(source: LuminanceSource): Bitmap {
    val width = source.width
    val height = source.height
    val luminance = source.matrix
    val pixels = IntArray(width * height) { i ->
        val g = luminance[i].toInt() and 0xFF
        0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * The decoder factory of the scanner, with a look at every frame: [onSource] gets the frame that is about to be decoded. The decoding itself
 * is the one of [delegate], so the camera has one user of the frames only, the QR decoder.
 */
private class FrameTapDecoderFactory(
    private val delegate: DecoderFactory,
    private val onSource: (LuminanceSource) -> Unit
) : DecoderFactory {
    override fun createDecoder(baseHints: Map<DecodeHintType, *>): Decoder = Tap(delegate.createDecoder(baseHints), onSource)

    private class Tap(private val inner: Decoder, private val onSource: (LuminanceSource) -> Unit) : Decoder(MultiFormatReader()) {
        override fun decode(source: LuminanceSource): Result? {
            try {
                onSource(source)
            } catch (e: Exception) {
                Log.w(TAG, "frame tap failed: $e")
            }
            return inner.decode(source)
        }

        override fun getPossibleResultPoints(): List<ResultPoint> = inner.possibleResultPoints
        override fun foundPossibleResultPoint(point: ResultPoint) = inner.foundPossibleResultPoint(point)
    }
}
