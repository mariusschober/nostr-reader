package com.reader.app.ui.screens

import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.colorsFor
import kotlinx.serialization.json.*
import java.util.concurrent.Executors

/** Pairing: QR scanner opens immediately; manual paste as fallback. */
@Composable
fun PairingScreen(
  settings: ReaderSettings,
  onScanned: (pairingJson: String) -> Unit,
  onCancel: () -> Unit,
  error: String?,
) {
  BackHandler { onCancel() }
  val c = colorsFor(settings.background)
  val ctx = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current
  var manual by remember { mutableStateOf("") }
  var scanError by remember { mutableStateOf<String?>(null) }

  Scaffold(containerColor = c.background) { pad ->
    Column(Modifier.padding(pad).fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("Pair Chrome", fontFamily = ReaderFonts.Ui, fontSize = 20.sp, color = c.text)
      Spacer(Modifier.height(4.dp))
      Text("Point at the code in the Reader extension", fontFamily = ReaderFonts.Ui, fontSize = 14.sp, color = c.secondary)
      Spacer(Modifier.height(12.dp))
      Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
          factory = { context ->
            PreviewView(context).also { pv ->
              val provider = ProcessCameraProvider.getInstance(context).get()
              val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
              val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
              analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                try {
                  val buffer = image.planes[0].buffer
                  val bytes = ByteArray(buffer.remaining())
                  buffer.get(bytes)
                  val source = PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
                  val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
                  val text = result.text
                  if (text.contains("reader-pair/1")) {
                    image.close()
                    onScanned(text)
                    return@setAnalyzer
                  }
                } catch (e: Exception) {
                  // no QR in frame: keep scanning
                } finally {
                  try {
                    image.close()
                  } catch (e: Exception) {
                  }
                }
              }
              provider.unbindAll()
              provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
          },
          modifier = Modifier.fillMaxSize(),
        )
      }
      (error ?: scanError)?.let {
        Text(it, fontFamily = ReaderFonts.Ui, color = c.error, fontSize = 13.sp)
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
        Button(
          onClick = {
            if (manual.contains("reader-pair/1")) onScanned(manual.trim())
            else scanError = "That does not look like a Reader pairing code."
          },
        ) { Text("Pair") }
      }
    }
  }
}

/** Validate + parse QR payload (protocol/version/expiry checked by caller too). */
fun parsePairingQr(text: String, nowSecs: Long): JsonObject {
  val o = Json.parseToJsonElement(text).jsonObject
  require(o["protocol"]?.jsonPrimitive?.content == "reader-pair/1") { "unsupported pairing protocol" }
  val exp = o["expiresAt"]?.jsonPrimitive?.long ?: 0L
  require(exp > nowSecs) { "pairing code expired; refresh it in Chrome" }
  o["pairingPubkey"]!!.jsonPrimitive.content
  o["chromeDevicePubkey"]!!.jsonPrimitive.content
  o["nonce"]!!.jsonPrimitive.content
  return o
}

fun b64decode(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
