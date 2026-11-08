package io.github.mikevillasis.espeonanim.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mikevillasis.espeonanim.CaptureViewModel
import io.github.mikevillasis.espeonanim.transport.PairUi
import io.github.mikevillasis.espeonanim.transport.QrAnalyzer
import java.util.concurrent.Executors

/** Full-screen QR scanner for pairing with the PC. [onClose] returns to the previous screen. */
@Composable
fun PairingScreen(vm: CaptureViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.pairUi.collectAsStateWithLifecycle()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok
    }
    LaunchedEffect(Unit) {
        vm.startScanning()
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Pair with your PC", style = MaterialTheme.typography.headlineLarge)
            when (val s = ui) {
                is PairUi.Paired -> {
                    Card(colors = CardDefaults.cardColors(containerColor = OkGreen.copy(alpha = 0.18f))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Paired with ${s.pcName}", style = MaterialTheme.typography.titleLarge)
                            Text("You can now send takes to this PC over Wi-Fi.", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Done", fontSize = 20.sp) }
                }
                else -> {
                    Text(
                        "In Unreal Editor, open the EspeonAnim window and point the camera at the QR code. " +
                            "Your phone and PC must be on the same Wi-Fi network.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!granted) {
                        Text(
                            if (denied) "The camera is needed to scan the code. Allow it in the phone's app settings."
                            else "Allow the camera to scan the code.",
                            color = if (denied) BadRed else MaterialTheme.colorScheme.onBackground,
                        )
                        OutlinedButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    } else {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))) {
                            QrCameraPreview(onText = vm::onQrText)
                            if (s is PairUi.Checking) {
                                Row(
                                    Modifier.align(Alignment.Center).padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                    Text("Contacting your PC…", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                    if (s is PairUi.Error) {
                        Text(s.message, color = BadRed, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = vm::startScanning) { Text("Scan again") }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel", textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun QrCameraPreview(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    val latest by androidx.compose.runtime.rememberUpdatedState(onText)

    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        val useCases = ArrayList<androidx.camera.core.UseCase>()
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(executor, QrAnalyzer { text -> latest(text) }) }
                useCases += preview; useCases += analysis
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // Camera unavailable (in use elsewhere); the preview just stays empty.
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            provider?.unbind(*useCases.toTypedArray())
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
