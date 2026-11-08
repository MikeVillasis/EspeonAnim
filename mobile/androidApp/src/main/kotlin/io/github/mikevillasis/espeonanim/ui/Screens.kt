package io.github.mikevillasis.espeonanim.ui

import android.widget.VideoView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mikevillasis.espeonanim.CaptureViewModel
import io.github.mikevillasis.espeonanim.ExportState
import io.github.mikevillasis.espeonanim.shared.manifest.TakeRole
import io.github.mikevillasis.espeonanim.shared.manifest.Verdict
import io.github.mikevillasis.espeonanim.shared.session.CalibrationPhase
import io.github.mikevillasis.espeonanim.shared.session.SessionState
import io.github.mikevillasis.espeonanim.shared.session.Take
import io.github.mikevillasis.espeonanim.shared.session.TakePhase

private fun seconds(ms: Long) = ((ms + 999) / 1000).coerceAtLeast(0).toString()

@Composable
private fun ScreenScaffold(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ---------------------------------------------------------------- Setup

@Composable
fun SetupScreen(vm: CaptureViewModel, state: SessionState, cameraGranted: Boolean, denied: Boolean, onPair: () -> Unit, onContinue: () -> Unit, onOpenSettings: () -> Unit) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    var height by rememberSaveable { mutableStateOf(state.performerHeightCm?.toString() ?: "") }
    ScreenScaffold("EspeonAnim") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Record yourself jumping. The app checks each take, then makes one package for your PC.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Action", style = MaterialTheme.typography.titleMedium)
                FilterChip(selected = true, onClick = {}, label = { Text("Jump", fontSize = 18.sp) })
            }
            OutlinedTextField(
                value = height,
                onValueChange = { v -> height = v.filter(Char::isDigit).take(3); vm.setHeight(height) },
                label = { Text("Your height in cm (optional)") },
                supportingText = { Text("Helps the PC get your size right. Assumes 170 cm if empty.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Before you start", style = MaterialTheme.typography.titleLarge)
                    for (tip in listOf(
                        "Prop the phone up at about waist height. Don't hold it.",
                        "Stand 3-4 m away, with your whole body in view.",
                        "Use good light: face a window or turn on the lights.",
                        "Wear clothes that contrast with the wall behind you.",
                        "Only you should be in the picture.",
                    )) Text("•  $tip", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (denied && !cameraGranted) {
                Card(colors = CardDefaults.cardColors(containerColor = BadRed.copy(alpha = 0.18f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("EspeonAnim needs the camera to record you.", style = MaterialTheme.typography.titleMedium)
                        Text("Nothing is uploaded and no sound is recorded. You can allow the camera in Settings.")
                        TextButton(onClick = onOpenSettings) { Text("Open settings") }
                    }
                }
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(if (cameraGranted) "Continue" else "Allow camera and continue", fontSize = 20.sp)
            }
            OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(
                    pairing?.let { "Paired with ${it.pcName} - pair again" } ?: "Pair with PC (scan QR code)",
                    fontSize = 17.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Calibration

@Composable
fun CalibrationScreen(vm: CaptureViewModel, state: SessionState) {
    when (state.calibrationPhase) {
        CalibrationPhase.Review -> state.pending?.let {
            ClipReview(
                title = "Calibration clip",
                take = it,
                primaryLabel = "Use this clip",
                onPrimary = vm::acceptCalibration,
                secondaryLabel = "Redo",
                onSecondary = vm::redoCalibration,
            )
        }
        else -> CameraScreen(
            vm,
            allowFlip = state.calibrationPhase == CalibrationPhase.Ready,
            center = {
                when (state.calibrationPhase) {
                    CalibrationPhase.Ready -> InstructionCard(
                        "Calibration",
                        "First hold an A-pose for 3 seconds (arms slightly out, away from your body). " +
                            "Then turn slowly all the way around in place, taking about 10 seconds.",
                    )
                    CalibrationPhase.Countdown -> BigNumber(seconds(state.remainingMs), "Get ready. Stand in an A-pose.")
                    CalibrationPhase.APose -> BigNumber(seconds(state.remainingMs), "Hold the A-pose")
                    CalibrationPhase.Turn -> BigNumber(seconds(state.remainingMs), "Turn slowly in place")
                    CalibrationPhase.Finalizing -> CircularProgressIndicator(color = Color.White)
                    CalibrationPhase.Review -> Unit
                }
            },
            bottom = {
                state.error?.let { ErrorLine(it, vm::clearError) }
                when (state.calibrationPhase) {
                    CalibrationPhase.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = vm::backToSetup, modifier = Modifier.height(64.dp)) { Text("Back", color = Color.White) }
                        Button(onClick = vm::startCalibration, modifier = Modifier.height(64.dp)) { Text("Start calibration", fontSize = 20.sp) }
                    }
                    CalibrationPhase.Countdown, CalibrationPhase.APose, CalibrationPhase.Turn ->
                        OutlinedButton(onClick = vm::cancel, modifier = Modifier.height(56.dp)) { Text("Cancel", color = Color.White) }
                    else -> Unit
                }
            },
        )
    }
}

@Composable
private fun InstructionCard(title: String, body: String) {
    Card(
        Modifier.padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.65f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp)
        }
    }
}

@Composable
private fun ErrorLine(msg: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).background(BadRed, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg, color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK", color = Color.White) }
    }
}

// ---------------------------------------------------------------- Takes

@Composable
fun TakesScreen(vm: CaptureViewModel, state: SessionState) {
    when (state.takePhase) {
        TakePhase.List -> TakeList(vm, state)
        TakePhase.Review -> state.pending?.let {
            ClipReview(
                title = "Review ${it.id}",
                take = it,
                primaryLabel = "Keep",
                onPrimary = vm::keepTake,
                secondaryLabel = "Retake",
                onSecondary = vm::retakeTake,
                tertiaryLabel = "Discard",
                onTertiary = vm::discardPending,
            )
        }
        else -> CameraScreen(
            vm,
            allowFlip = false,
            center = {
                when (state.takePhase) {
                    TakePhase.Countdown -> BigNumber(seconds(state.remainingMs), "Get ready to jump")
                    TakePhase.Recording -> BigNumber(seconds(state.remainingMs), "Jump now!")
                    TakePhase.Finalizing -> CircularProgressIndicator(color = Color.White)
                    else -> Unit
                }
            },
            bottom = {
                when (state.takePhase) {
                    TakePhase.Countdown -> OutlinedButton(onClick = vm::cancel, modifier = Modifier.height(56.dp)) { Text("Cancel", color = Color.White) }
                    TakePhase.Recording -> Button(
                        onClick = vm::stopTakeEarly,
                        colors = ButtonDefaults.buttonColors(containerColor = BadRed),
                        modifier = Modifier.height(64.dp),
                    ) { Text("Stop", fontSize = 20.sp) }
                    else -> Unit
                }
            },
        )
    }
}

@Composable
private fun TakeList(vm: CaptureViewModel, state: SessionState) {
    ScreenScaffold("Takes") {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Calibration clip saved. Now record your jumps: a 3 second countdown, then up to 8 seconds.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            state.error?.let { ErrorLine(it, vm::clearError) }
            if (state.takes.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No takes yet", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.takes, key = { it.id }) { take -> TakeRow(take, onOpen = { vm.reviewTake(take.id) }, onDelete = { vm.deleteTake(take.id) }) }
                }
            }
            state.takeAdvice?.let {
                Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = vm::startTake, modifier = Modifier.weight(1f).height(64.dp)) { Text("Record take", fontSize = 20.sp) }
                Button(
                    onClick = vm::goToPackage,
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OkGreen),
                ) { Text("Finish", fontSize = 20.sp) }
            }
        }
    }
}

@Composable
private fun TakeRow(take: Take, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(take.id, style = MaterialTheme.typography.titleMedium)
                Text("%.1f s, %.0f fps".format(take.clip.durationS, take.clip.fps), style = MaterialTheme.typography.bodyMedium)
            }
            VerdictChip(take.clip.summary?.verdict)
            TextButton(onClick = onOpen) { Text("Review") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
fun VerdictChip(verdict: Verdict?) {
    val (label, color) = when (verdict) {
        Verdict.Ok -> "OK" to OkGreen
        Verdict.Warn -> "Warnings" to WarnAmber
        Verdict.Poor -> "Poor" to BadRed
        null -> "-" to Color.Gray
    }
    Text(
        label, color = if (verdict == Verdict.Warn) Color.Black else Color.White, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------- Review

@Composable
fun ClipReview(
    title: String,
    take: Take,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    tertiaryLabel: String? = null,
    onTertiary: () -> Unit = {},
) {
    val summary = take.clip.summary
    ScreenScaffold(title) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoPlayer(take.clip.videoPath, Modifier.fillMaxWidth().heightIn(max = 360.dp))
                if (summary != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VerdictChip(summary.verdict)
                        Text("${summary.analyzedFrames} frames checked", style = MaterialTheme.typography.bodyMedium)
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (summary.mayGiveBadResults) BadRed.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(summary.headline, style = MaterialTheme.typography.titleMedium)
                            for (w in summary.warnings) {
                                Text(
                                    "• ${w.message} (${(w.fraction * 100).toInt()}% of the take)\n   ${w.suggestion}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                } else if (take.role == TakeRole.Calibration) {
                    Text(
                        "Check that you held the A-pose, then turned fully around and stayed in frame.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f).height(60.dp)) { Text(secondaryLabel, fontSize = 18.sp) }
                Button(onClick = onPrimary, modifier = Modifier.weight(1f).height(60.dp)) { Text(primaryLabel, fontSize = 18.sp) }
            }
            if (tertiaryLabel != null) {
                TextButton(onClick = onTertiary, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(tertiaryLabel) }
            }
        }
    }
}

@Composable
private fun VideoPlayer(path: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(path)
                setOnPreparedListener { mp -> mp.isLooping = true; start() }
                setOnClickListener { if (isPlaying) pause() else start() }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

// ---------------------------------------------------------------- Export

@Composable
fun ExportScreen(vm: CaptureViewModel, state: SessionState, onPair: () -> Unit) {
    val ctx = LocalContext.current
    val export by vm.export.collectAsStateWithLifecycle()
    ScreenScaffold("Send to your PC") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val kept = state.takes.filter { it.kept }
            Text(
                "${kept.size} take${if (kept.size == 1) "" else "s"} and the calibration clip are bundled into one .espeon file.",
                style = MaterialTheme.typography.bodyLarge,
            )
            for (t in kept) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.id, style = MaterialTheme.typography.titleMedium)
                VerdictChip(t.clip.summary?.verdict)
            }
            if (kept.any { it.clip.summary?.verdict == Verdict.Poor }) {
                Text(
                    "Some takes may give bad results; the PC picks the best take automatically.",
                    color = WarnAmber, style = MaterialTheme.typography.bodyLarge,
                )
            }
            when (val e = export) {
                ExportState.Idle, ExportState.Building -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text("Building package…", style = MaterialTheme.typography.titleMedium)
                }
                is ExportState.Failed -> {
                    Text(e.message, color = BadRed, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = vm::retryExport) { Text("Try again") }
                }
                is ExportState.Ready -> {
                    Text("${e.file.name}  (%.1f MB)".format(e.sizeBytes / 1_048_576.0), style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { vm.shareIntent()?.let { ctx.startActivity(it) } },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text("Share…", fontSize = 20.sp) }
                    OutlinedButton(
                        onClick = vm::saveToDownloads,
                        enabled = e.savedTo == null,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text(if (e.savedTo == null) "Save to Downloads" else "Saved", fontSize = 20.sp) }
                    e.savedTo?.let { Text("Saved to $it", style = MaterialTheme.typography.bodyLarge, color = OkGreen) }
                    e.note?.let { Text(it, color = BadRed) }
                    SendToPcSection(vm, onPair)
                }
            }
            TextButton(onClick = vm::backToTakes) { Text("Back to takes") }
        }
    }
}
