package io.github.mikevillasis.espeonanim.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mikevillasis.espeonanim.CaptureViewModel
import io.github.mikevillasis.espeonanim.voice.VoiceState

/**
 * "Voice" switch plus a status line, for use over the camera (dark pill so it stays readable).
 * Turning it on asks for the microphone permission first; if refused, explains and links to Settings.
 */
@Composable
fun VoiceControls(vm: CaptureViewModel, capturing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val enabled by vm.voiceEnabled.collectAsStateWithLifecycle()
    val voiceState by vm.voiceState.collectAsStateWithLifecycle()
    val micGranted by vm.micGranted.collectAsStateWithLifecycle()
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.refreshMicPermission()
        refused = !ok
        if (ok) vm.setVoiceEnabled(true)
    }
    val needsPermission = !micGranted && (refused || enabled)

    Column(
        modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Voice", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    if (!on) { refused = false; vm.setVoiceEnabled(false) }
                    else {
                        vm.refreshMicPermission()
                        launcher.launch(Manifest.permission.RECORD_AUDIO) // returns at once with ok=true if already granted
                    }
                },
            )
            if (enabled && !needsPermission) {
                when (voiceState) {
                    VoiceState.Listening -> Text(
                        if (capturing) "Say \"Stop\"" else "Say \"Start\"",
                        color = Color.White, fontSize = 16.sp,
                    )
                    VoiceState.Unavailable -> Text("Voice commands unavailable on this phone", color = Color.White, fontSize = 14.sp)
                    else -> Unit
                }
            }
        }
        if (needsPermission) {
            Text("Voice commands need the microphone. Nothing is recorded or uploaded.", color = Color.White, fontSize = 14.sp)
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open settings") }
        }
    }
}
