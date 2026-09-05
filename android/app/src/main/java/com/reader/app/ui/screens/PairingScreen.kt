package com.reader.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.ValidatedPairingRequest
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.colorsFor
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Pairing: QR scanner opens immediately; manual paste as fallback. */
@Composable
fun PairingScreen(
  settings: ReaderSettings,
  onScanned: (pairingJson: String) -> Unit,
  onCancel: () -> Unit,
  error: String?,
  status: String? = null,
) {
  BackHandler { onCancel() }
  val c = colorsFor(settings.background)
  val ctx = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current
  var manual by remember { mutableStateOf("") }
  var scanError by remember { mutableStateOf<String?>(null) }
  var reviewText by rememberSaveable { mutableStateOf<String?>(null) }
  var cameraPermissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  var cameraPermissionDenied by rememberSaveable { mutableStateOf(false) }
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    cameraPermissionGranted = granted
    cameraPermissionDenied = !granted
    if (granted) scanError = null
  }

  LaunchedEffect(Unit) {
    if (!cameraPermissionGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
  }

  DisposableEffect(lifecycle, ctx) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
          PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted = granted
        if (granted) scanError = null
      }
    }
    lifecycle.lifecycle.addObserver(observer)
    onDispose { lifecycle.lifecycle.removeObserver(observer) }
  }

  fun prepareReview(text: String) {
    val normalized = text.trim()
    runCatching {
      PairingProtocol.validateRequest(normalized, System.currentTimeMillis() / 1000)
    }.onSuccess {
      scanError = null
      reviewText = normalized
    }.onFailure {
      scanError = it.message?.take(200) ?: "That pairing code is invalid."
      reviewText = null
    }
  }

  val review = reviewText?.let { text ->
    runCatching { PairingProtocol.validateRequest(text, System.currentTimeMillis() / 1000) }.getOrNull()
  }

  Scaffold(containerColor = c.background) { pad ->
    Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("Pair Chrome", fontFamily = ReaderFonts.Ui, fontSize = 20.sp, color = c.text)
      Spacer(Modifier.height(4.dp))
      if (review != null && reviewText != null) {
        PairingReview(
          request = review,
          onConfirm = { onScanned(reviewText!!) },
          onReset = {
            reviewText = null
            manual = ""
          },
          modifier = Modifier.fillMaxWidth().weight(1f),
        )
      } else {
        Text("Point at the code in the Reader extension", fontFamily = ReaderFonts.Ui, fontSize = 14.sp, color = c.secondary)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
          if (cameraPermissionGranted) {
            CameraScanner(
              onScanned = ::prepareReview,
              onError = { scanError = it },
            )
          } else {
            CameraPermissionPrompt(
              denied = cameraPermissionDenied,
              onRequest = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
              onOpenSettings = {
                ctx.startActivity(
                  Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", ctx.packageName, null)
                  },
                )
              },
            )
          }
        }
        (error ?: scanError)?.let {
          Text(it, fontFamily = ReaderFonts.Ui, color = c.error, fontSize = 13.sp)
          Spacer(Modifier.height(8.dp))
        }
        status?.let {
          Text(it, fontFamily = ReaderFonts.Ui, color = c.secondary, fontSize = 13.sp)
          Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
          value = manual, onValueChange = { manual = it },
          label = { Text("Or paste pairing code", fontFamily = ReaderFonts.Ui) },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row {
          TextButton(onClick = onCancel) { Text("Cancel", fontFamily = ReaderFonts.Ui, color = c.text) }
          Spacer(Modifier.width(8.dp))
          Button(onClick = { prepareReview(manual) }) { Text("Review") }
        }
      }
    }
  }
}

@Composable
private fun PairingReview(
  request: ValidatedPairingRequest,
  onConfirm: () -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.padding(top = 20.dp)) {
    Text("Review connection", fontFamily = ReaderFonts.Ui, fontSize = 18.sp)
    Spacer(Modifier.height(8.dp))
    Text(
      "Chrome device ${request.chromeDevicePubkey.take(12)}… is asking to create a private Reader channel.",
      fontFamily = ReaderFonts.Ui,
      fontSize = 14.sp,
    )
    Spacer(Modifier.height(16.dp))
    Text("Relays (${request.relays.size})", fontFamily = ReaderFonts.Ui, fontSize = 15.sp)
    Spacer(Modifier.height(4.dp))
    request.relays.forEach { relay ->
      Text("• $relay", fontFamily = ReaderFonts.Ui, fontSize = 13.sp)
    }
    Spacer(Modifier.height(16.dp))
    Text(
      "Only encrypted pairing and article envelopes are sent. Tap Connect to resolve these relay addresses and begin.",
      fontFamily = ReaderFonts.Ui,
      fontSize = 13.sp,
    )
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      TextButton(onClick = onReset) { Text("Scan another", fontFamily = ReaderFonts.Ui) }
      Spacer(Modifier.width(8.dp))
      Button(onClick = onConfirm) { Text("Connect", fontFamily = ReaderFonts.Ui) }
    }
  }
}

@Composable
private fun CameraPermissionPrompt(
  denied: Boolean,
  onRequest: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      if (denied) "Camera access is off" else "Camera access is needed",
      fontFamily = ReaderFonts.Ui,
      fontSize = 18.sp,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "Allow camera access to scan the pairing code. You can still paste the code below.",
      fontFamily = ReaderFonts.Ui,
      fontSize = 14.sp,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRequest) { Text("Allow camera", fontFamily = ReaderFonts.Ui) }
    if (denied) {
      TextButton(onClick = onOpenSettings) {
        Text("Open app settings", fontFamily = ReaderFonts.Ui)
      }
    }
  }
}

@Composable
private fun CameraScanner(
  onScanned: (String) -> Unit,
  onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current
  val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  val previewView = remember(context) {
    PreviewView(context).apply {
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
  }
  val currentOnScanned by rememberUpdatedState(onScanned)
  val currentOnError by rememberUpdatedState(onError)

  AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

  DisposableEffect(context, lifecycle, previewView) {
    val active = AtomicBoolean(true)
    val delivered = AtomicBoolean(false)
    val analyzerFailed = AtomicBoolean(false)
    val reader = MultiFormatReader().apply {
      setHints(
        mapOf(
          DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
          DecodeHintType.TRY_HARDER to true,
        ),
      )
    }
    val analysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
    var cameraProvider: ProcessCameraProvider? = null

    analysis.setAnalyzer(analysisExecutor) { image ->
      try {
        val source = image.toLuminanceSource()
        val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        if (result.text.contains("reader-pair/2") && delivered.compareAndSet(false, true)) {
          mainExecutor.execute {
            if (active.get()) currentOnScanned(result.text)
          }
        }
      } catch (_: ReaderException) {
        // No decodable QR in this frame; keep scanning.
      } catch (e: Exception) {
        if (analyzerFailed.compareAndSet(false, true)) {
          mainExecutor.execute {
            if (active.get()) currentOnError("The camera started, but frames could not be read: ${e.message ?: "unknown error"}")
          }
        }
      } finally {
        reader.reset()
        image.close()
      }
    }

    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
      {
        if (!active.get()) return@addListener
        try {
          val provider = providerFuture.get()
          cameraProvider = provider
          require(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            "No rear camera is available."
          }
          val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
          }
          provider.unbindAll()
          provider.bindToLifecycle(
            lifecycle,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
          )
        } catch (e: Exception) {
          analysis.clearAnalyzer()
          if (active.get()) {
            currentOnError("Camera could not start: ${(e.cause ?: e).message ?: "unknown error"}")
          }
        }
      },
      mainExecutor,
    )

    onDispose {
      active.set(false)
      analysis.clearAnalyzer()
      cameraProvider?.unbindAll()
      analysisExecutor.shutdownNow()
    }
  }
}

private fun ImageProxy.toLuminanceSource(): PlanarYUVLuminanceSource {
  val plane = planes.firstOrNull() ?: error("Camera frame has no luminance plane")
  val bytes = copyLuminancePlane(
    buffer = plane.buffer,
    width = width,
    height = height,
    rowStride = plane.rowStride,
    pixelStride = plane.pixelStride,
  )
  return PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
}

internal fun copyLuminancePlane(
  buffer: ByteBuffer,
  width: Int,
  height: Int,
  rowStride: Int,
  pixelStride: Int,
): ByteArray {
  require(width > 0 && height > 0) { "Camera frame has invalid dimensions" }
  require(rowStride > 0 && pixelStride > 0) { "Camera frame has invalid strides" }
  val source = buffer.duplicate()
  val start = source.position()
  val limit = source.limit()
  return ByteArray(width * height).also { output ->
    var outputIndex = 0
    for (row in 0 until height) {
      var sourceIndex = start + row * rowStride
      repeat(width) {
        require(sourceIndex < limit) { "Camera luminance plane is truncated" }
        output[outputIndex++] = source.get(sourceIndex)
        sourceIndex += pixelStride
      }
    }
  }
}

/** Validate + parse QR payload (protocol/version/expiry checked by caller too). */
fun parsePairingQr(text: String, nowSecs: Long): JsonObject {
  return PairingProtocol.validateRequest(text, nowSecs).json
}

fun b64decode(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
