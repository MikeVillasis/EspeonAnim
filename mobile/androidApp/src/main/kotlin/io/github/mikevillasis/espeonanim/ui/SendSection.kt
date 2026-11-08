package io.github.mikevillasis.espeonanim.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mikevillasis.espeonanim.CaptureViewModel
import io.github.mikevillasis.espeonanim.shared.transport.JobWarning
import io.github.mikevillasis.espeonanim.shared.transport.SendState

/** "Send to PC (Wi-Fi)": pairing prompt, upload progress, processing progress and outcome. */
@Composable
fun SendToPcSection(vm: CaptureViewModel, onPair: () -> Unit, standalone: Boolean = false) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val online by vm.pcOnline.collectAsStateWithLifecycle()
    val send by vm.sendState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val ctx = LocalContext.current

    // Android 13+: ask once for notification permission so progress/outcome show up; send either way.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.sendToPc() }
    val startSend = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.sendToPc()
        }
    }

    // The screen must stay on while a (possibly minutes-long) upload runs.
    val active = send.active
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(pairing) { vm.refreshPcOnline() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val p = pairing
        if (p == null) {
            Text(
                "To send over Wi-Fi, scan the QR code shown in Unreal Editor (EspeonAnim window). " +
                    "Phone and PC need the same Wi-Fi network.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Pair with PC", fontSize = 20.sp)
            }
            return@Column
        }
        when (val s = send) {
            SendState.Idle, SendState.Cancelled -> {
                if (standalone) return@Column
                Text(
                    when (online) {
                        true -> "Paired with ${p.pcName} - ready"
                        false -> "Paired with ${p.pcName}, but it is not answering right now. Is Unreal open and on the same Wi-Fi?"
                        null -> "Paired with ${p.pcName}"
                    },
                    color = if (online == false) WarnAmber else MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (s == SendState.Cancelled) Text("Sending was cancelled.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = startSend, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("Send to PC (Wi-Fi)", fontSize = 20.sp)
                }
                OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) { Text("Pair with a different PC") }
            }
            is SendState.Uploading -> {
                Text("Sending to ${p.pcName}... ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                Text("You can lock the phone; progress and the result appear in a notification.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = vm::cancelSend, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            is SendState.Processing -> {
                val st = s.status
                Text("${p.pcName} is working on it", style = MaterialTheme.typography.titleMedium)
                if (st != null && st.progress > 0f) {
                    LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    st?.message?.ifBlank { null } ?: "Received. Waiting for the PC to start...",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = vm::cancelSend, modifier = Modifier.fillMaxWidth()) { Text("Stop waiting") }
                Text("The PC keeps working even if you stop waiting.", style = MaterialTheme.typography.bodyMedium)
            }
            is SendState.Done -> {
                Text("Done - the animation is in Unreal", color = OkGreen, style = MaterialTheme.typography.titleLarge)
                OutcomeDetails(s.status.quality, s.status.warnings)
                OutlinedButton(onClick = vm::resetSend, modifier = Modifier.fillMaxWidth()) { Text("OK") }
            }
            is SendState.Failed -> {
                Text(s.message, color = BadRed, style = MaterialTheme.typography.bodyLarge)
                s.status?.let { OutcomeDetails(it.quality, it.warnings) }
                if (s.needsRescan) {
                    Button(onClick = onPair, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Text("Scan the QR code again", fontSize = 18.sp)
                    }
                } else if (!standalone) {
                    Button(onClick = startSend, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Text("Try again", fontSize = 20.sp)
                    }
                }
                OutlinedButton(onClick = vm::resetSend, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun OutcomeDetails(quality: String, warnings: List<JobWarning>) {
    when (quality) {
        "good" -> Text("Quality: good", color = OkGreen, style = MaterialTheme.typography.titleMedium)
        "usable" -> Text("Quality: usable - check the result in Unreal", color = WarnAmber, style = MaterialTheme.typography.titleMedium)
        "poor" -> Text("Quality: poor - consider recording again", color = BadRed, style = MaterialTheme.typography.titleMedium)
    }
    for (w in warnings) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("•  ${w.message.ifBlank { w.code }}", style = MaterialTheme.typography.bodyLarge)
            if (w.suggestion.isNotBlank()) {
                Text("Tip: ${w.suggestion}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
