package io.github.mikevillasis.espeonanim.ui

import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mikevillasis.espeonanim.CaptureViewModel
import io.github.mikevillasis.espeonanim.OverlayState
import io.github.mikevillasis.espeonanim.shared.guidance.FrameGuide
import io.github.mikevillasis.espeonanim.shared.guidance.Lm
import io.github.mikevillasis.espeonanim.shared.guidance.Severity
import io.github.mikevillasis.espeonanim.shared.guidance.WarningCode

/**
 * Live camera with frame guide, skeleton overlay and the guidance banner.
 * [center] draws on top (countdown, instructions); [bottom] holds the buttons.
 */
@Composable
fun CameraScreen(
    vm: CaptureViewModel,
    modifier: Modifier = Modifier,
    allowFlip: Boolean = true,
    center: @Composable BoxScope.() -> Unit = {},
    bottom: @Composable () -> Unit = {},
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val config = LocalConfiguration.current
    val front by vm.front.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val cameraError by vm.cameraError.collectAsStateWithLifecycle()
    val fps by vm.fps.collectAsStateWithLifecycle()

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val rotation = view.display?.rotation ?: Surface.ROTATION_0

    LaunchedEffect(front) { vm.bindCamera(owner, previewView, rotation) }
    LaunchedEffect(config.orientation, rotation) { vm.setRotation(rotation) }
    DisposableEffect(Unit) { onDispose { vm.releaseCamera() } }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        SkeletonOverlay(overlay, cropped = live.has(WarningCode.BodyCropped), modifier = Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            GuidanceBanner(vm)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { center() }
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (fps > 0) {
                    Text(
                        "${fps} fps", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                bottom()
            }
        }

        if (allowFlip) {
            OutlinedButton(
                onClick = vm::toggleFacing,
                modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(12.dp),
            ) { Text(if (front) "Front camera" else "Back camera", color = Color.White) }
        }

        cameraError?.let { msg ->
            Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera problem", style = MaterialTheme.typography.titleLarge)
                    Text(msg, Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center)
                    Button(onClick = { vm.bindCamera(owner, previewView, rotation) }) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun GuidanceBanner(vm: CaptureViewModel) {
    val live by vm.live.collectAsStateWithLifecycle()
    val top = live.top
    val (bg, fg) = when (top?.severity) {
        Severity.Block -> BadRed to Color.White
        Severity.Warn -> WarnAmber to Color.Black
        null -> OkGreen to Color.White
    }
    Column(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(top?.message ?: "Looking good", color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
        Text(top?.suggestion ?: "Whole body in view. You're ready.", color = fg, fontSize = 20.sp, lineHeight = 24.sp)
        if (live.active.size > 1) {
            Text("+${live.active.size - 1} more", color = fg.copy(alpha = 0.85f), fontSize = 16.sp)
        }
    }
}

@Composable
private fun SkeletonOverlay(overlay: OverlayState?, cropped: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val aspect = overlay?.frameAspect
            ?: if (size.height >= size.width) 9f / 16f else 16f / 9f
        // Rectangle the (FIT_CENTER) preview occupies.
        val viewAspect = size.width / size.height
        val w: Float
        val h: Float
        if (aspect > viewAspect) { w = size.width; h = size.width / aspect } else { h = size.height; w = size.height * aspect }
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f

        // Frame guide: where the whole body should fit.
        val guideColor = if (cropped) BadRed else Color.White.copy(alpha = 0.75f)
        drawRoundRect(
            color = guideColor,
            topLeft = Offset(left + w * FrameGuide.LEFT, top + h * FrameGuide.TOP),
            size = Size(w * (FrameGuide.RIGHT - FrameGuide.LEFT), h * (FrameGuide.BOTTOM - FrameGuide.TOP)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(28f, 18f))),
        )

        val lms = overlay?.landmarks.orEmpty()
        if (lms.size >= Lm.COUNT) {
            val mirrored = overlay?.mirrored == true
            fun pt(i: Int): Offset {
                val x = if (mirrored) 1f - lms[i].x else lms[i].x
                return Offset(left + x * w, top + lms[i].y * h)
            }
            val bone = if (cropped) BadRed else Color(0xFF63F2A0)
            for ((a, b) in Lm.BONES) {
                if (lms[a].visibility < 0.3f || lms[b].visibility < 0.3f) continue
                drawLine(bone, pt(a), pt(b), strokeWidth = 5.dp.toPx())
            }
            for (i in 0 until Lm.COUNT) {
                if (lms[i].visibility < 0.3f) continue
                drawCircle(Color.White, radius = 4.dp.toPx(), center = pt(i))
            }
        }
    }
}

/** Giant countdown / timer number used in the middle of the camera. */
@Composable
fun BigNumber(text: String, caption: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text, color = Color.White, fontSize = 140.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(horizontal = 32.dp),
        )
        if (caption != null) {
            Text(
                caption, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(16.dp),
            )
        }
    }
}
