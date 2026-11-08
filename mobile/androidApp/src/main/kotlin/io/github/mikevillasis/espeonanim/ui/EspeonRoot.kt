package io.github.mikevillasis.espeonanim.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mikevillasis.espeonanim.CaptureViewModel
import io.github.mikevillasis.espeonanim.shared.session.CalibrationPhase
import io.github.mikevillasis.espeonanim.shared.session.Step
import io.github.mikevillasis.espeonanim.shared.session.TakePhase

@Composable
fun EspeonRoot(vm: CaptureViewModel = viewModel()) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
        if (ok) vm.continueFromSetup()
    }

    // Back button: step out of the current screen instead of closing the app mid-session.
    BackHandler(enabled = state.step != Step.Setup) {
        when {
            state.capturing -> vm.cancel()
            state.step == Step.Calibration && state.calibrationPhase == CalibrationPhase.Ready -> vm.backToSetup()
            state.step == Step.Takes && state.takePhase == TakePhase.Review -> vm.discardPending()
            state.step == Step.Package -> vm.backToTakes()
            else -> Unit
        }
    }

    var scanning by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = scanning) { scanning = false }

    EspeonTheme {
        if (scanning) {
            PairingScreen(vm, onClose = { scanning = false })
            return@EspeonTheme
        }
        when (state.step) {
            Step.Setup -> SetupScreen(
                vm, state, cameraGranted = granted, denied = denied, onPair = { scanning = true },
                onContinue = { if (granted) vm.continueFromSetup() else launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            Step.Calibration -> CalibrationScreen(vm, state)
            Step.Takes -> TakesScreen(vm, state)
            Step.Package -> ExportScreen(vm, state, onPair = { scanning = true })
        }
    }
}
