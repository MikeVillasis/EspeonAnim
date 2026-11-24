# EspeonAnim: agent brief

Read this before working on the repo. It covers what the project is, how the parts fit, how to build and test each one on this machine, and the traps already hit. For details, see the files named in each section rather than relying on memory.

## What it is

EspeonAnim turns a phone recording of a real person into a game-ready 3D animation in Unreal Engine 5.8.

- **Repo:** `MikeVillasis/EspeonAnim` on GitHub. It is public and MIT-licensed.
- **MVP flow:** an Android app guides a performer through recording a jump and a calibration clip. The app sends one `.espeon` package over Wi-Fi to a UE editor plugin. The plugin runs a Python processor, which turns the video into motion. The plugin then imports the motion as a 60 fps AnimSequence on Manny, the UE5 mannequin, with root motion, and exports an FBX.
- **Output scope:** body only. There are no fingers or face, and twist and corrective bones keep their reference pose.

Reference docs:
- `docs/plan.md`: goals, architecture and constraints.
- `docs/formats.md`: every data contract. It is the single source of truth.
- `docs/progress/2026-11-09.md`: the decisions made so far and why.

```
Phone app (KMP, Android now, iOS later)        PC (Windows + NVIDIA)
 camera + live MediaPipe pose -> guidance       UE 5.8 editor plugin "EspeonAnim"
 calibration clip + action takes                 ├─ QR pairing + HTTP receiver (port 47800)
 .espeon zip ────── Wi-Fi, Bearer token ───────► ├─ job queue, launches processor, shows progress
                                                 └─ imports motion.json -> AnimSequence + FBX
                                                processor (Python 3.12, own venv, GEM-X on CUDA)
                                                 video -> 3D body -> calibration -> trim -> retarget -> cleanup
```

## Repo layout

| Path | What lives there |
| --- | --- |
| `mobile/shared/` | KMP shared logic: `session/` (CaptureSession state machine, JumpStopDetector, VoiceCommands), `guidance/` (GuidanceEngine, GuidanceConfig, warning codes), `manifest/`, `packaging/`, `transport/` (Ktor client, pairing, send job). Tests are in `commonTest`. |
| `mobile/androidApp/` | Android UI and platform code. `CaptureViewModel.kt` ties everything together. `camera/` holds CameraX, PoseAnalyzer (MediaPipe tasks-vision), the IMU recorder and LensInfo. `transport/` holds PcLink, the QR analyzer and SendService (a foreground service). `voice/` holds the SpeechRecognizer wrapper. `ui/` holds the Compose screens. |
| `processor/espeon_processor/` | Python pipeline. `pipeline.py` is the main flow. `gem_stage.py` runs GEM-X. Each step has its own file: `analysis.py`, `framing.py`, `retarget.py`, `cleanup.py`, `solve.py`, `motion_io.py`, `package.py`, `validate.py`, `debug_plots.py`. Skeleton profiles are in `skeletons/`. |
| `processor/tools/setup_processor.ps1` | One-click install and repair. The plugin's "Install / Repair processor" button runs it. |
| `processor/tests/test_processor.py` | pytest suite. |
| `EspeonAnimUE/EspeonAnim/` | UE 5.8 host project used for testing. |
| `EspeonAnimUE/EspeonAnim/Plugins/EspeonAnim/Source/EspeonAnimEditor/` | Editor module. It contains the receiver, job manager, motion importer, processor setup, QR generator, the `SEspeonAnimTab` Slate tab, settings and commandlets. |
| `docs/agents/` | Issue-tracker, triage-label and domain-doc conventions, plus this brief. |
| `testdata/raw/` | Local test clips. They are not committed. |

## Data contracts (`docs/formats.md`)

1. **`.espeon` package:**
   - A zip holding `manifest.json` and `takes/*.mp4`, with optional `*.imu.csv` gravity files.
   - The manifest has `action` (`jump` or `other`), a `role` per take (`action`, or exactly one `calibration`), `camera.mirrored` (the app records unmirrored, so it is false), and a `guidance` verdict per take.
   - A bare `.mp4` is also accepted, as one action take with no calibration.
2. **`motion.json`:**
   - Unreal units (cm, Z up), 60 fps.
   - Root motion on `root` is ground-only, with Z always 0. The jump height is carried in `pelvis_translation`.
   - Rotations are local xyzw quaternions per bone.
   - **Axis caveat:** formats.md says "X forward, Y right", but `retarget.py` and the processor README say the mannequin faces +Y. Trust the code, and fix the doc if you touch this.
3. **`report.json`:** `quality` is `good`, `usable` or `poor`, plus warnings `{code, message, suggestion}`, stats and assumptions.
4. **Warning codes and framing thresholds:**
   - The codes are shared by the app and the processor.
   - The thresholds are mirrored in three places: `formats.md` §4, `GuidanceConfig.kt` and `processor/espeon_processor/framing.py`. Change all three together.
5. **Wi-Fi API:**
   - The pairing QR is `espeon://pair?v=1&h=&p=&t=&n=`.
   - Endpoints: `GET /api/v1/ping`, `POST /api/v1/packages` (up to 1 GiB), `GET /api/v1/packages/<id>`.
   - Job states run `received → processing → importing → done | failed`.
   - The phone accepts only private, link-local or loopback addresses.
6. **Processor CLI:**
   - Command: `python -m espeon_processor process --input <x> --out <dir> [--skeleton ue5_manny] [--device cuda|cpu]`.
   - stdout carries JSON lines only; logs go to stderr.
   - `doctor` prints an environment check as JSON.

## Key design decisions (do not undo without asking)

- **Licensing:**
  - Every model, weight file and dependency must allow commercial use.
  - The 3D model is **NVIDIA GEM-X with the SOMA body model**. GEM-X is pinned to commit `4aef1452e5df171deff4f293c08e643eb450981e`, and a runtime patch depends on that commit.
  - Person detection uses **MediaPipe**, not YOLOX.
  - Nothing based on SMPL, AMASS or Human3.6M is allowed.
- **Jump physics:**
  - The flight phase is replaced by a ballistic arc built from the measured airtime: apex = g·T²/8, capped at 80 cm. `--no-ballistic` turns it off.
  - Root motion follows the planted feet, not the hips.
- **Retargeting:** done in the processor (`retarget.py`), which solves rotations directly for Manny. The plugin writes them straight into an AnimSequence. UE's IK Retargeter is not used.
- **Take selection:** every action take is reconstructed (about 70 s of GPU per take on the RTX 5070) and the best one is kept.
  - `jump`: the best jump is trimmed out with its crouch, landing and settle. A take with several jumps gives an info warning, `multiple_jumps`.
  - `other`: only idle time is trimmed. No jump-specific warnings are produced.
- **Mannequin assets:** Epic's mannequin is never committed. Users copy it from their engine install.
- **App guidance:** it warns and coaches, and blocks a take only for `no_person`, `multiple_people`, `body_cropped` or `camera_moving`.
- **Capture flow:**
  - A 5 s countdown with beeps. Jump takes stop by themselves after landing, at most 12 s.
  - The **Start over** button resets the performer.
  - Optional **voice commands**: "Start" and "Stop", through on-device SpeechRecognizer.
  - Clips are always recorded without audio.

## Environment (Windows 11, this PC)

- **Hardware and tools:**
  - GPU: NVIDIA RTX 5070.
  - Shells: Git Bash and PowerShell 5.1.
  - `gh` CLI is authenticated.
- **MSIX virtualization trap:** the Claude desktop app is a packaged app. Files its shells write under `%APPDATA%` or `%LOCALAPPDATA%` are redirected into `%LOCALAPPDATA%\Packages\Claude_pzs8sxrjxfjjc\LocalCache\...`, where other processes such as the UE editor cannot see them. Keep anything the editor needs inside the repo; this is why the processor's Python lives in `processor\.python`.
- **Android:**
  - `JAVA_HOME="/c/Program Files/Android/openjdk/jdk-21.0.8"`.
  - Run Gradle through Git Bash (`./gradlew`) from `mobile/`.
  - adb is at `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`.
  - The test phone is a Pixel 10, serial `58201FDCR006Q0`.
  - minSdk 29, targetSdk 36. The app id is `io.github.mikevillasis.espeonanim`.
- **Processor:**
  - The venv is `processor\.venv`, and its Python is in `processor\.python` (uv-managed 3.12).
  - Weights are in `processor\models\`; GEM-X is in `processor\third_party\gem-x`.
  - ffmpeg comes from WinGet and is on PATH.
- **Unreal:**
  - UE 5.8.
  - The editor log is `EspeonAnimUE/EspeonAnim/Saved/Logs/EspeonAnim.log`, and jobs are in `Saved/EspeonAnim/Jobs`.
  - The Capture tab is under **Tools → EspeonAnim → EspeonAnim Capture**. It runs the processor `doctor` check when it opens.
- **Waiting:** foreground `sleep` is blocked. Use `timeout N tail -f /dev/null`, or run the job in the background.

## Build and test

```bash
# Mobile: shared tests + R8 release APK
cd /c/GameDev/Espeon/mobile && export JAVA_HOME="/c/Program Files/Android/openjdk/jdk-21.0.8"
./gradlew :shared:testDebugUnitTest :androidApp:assembleRelease
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```

```bash
# Processor
cd /c/GameDev/Espeon/processor
.venv/Scripts/python.exe -m pytest -q          # 37 passed, 1 skipped at last run
.venv/Scripts/python.exe -m espeon_processor doctor
.venv/Scripts/python.exe -m espeon_processor process --input ../testdata/raw/<clip> --out out/<name>
```

```bash
# Unreal headless (from EspeonAnimUE/EspeonAnim)
UnrealEditor-Cmd.exe EspeonAnim.uproject -run=EspeonProcess -Input=<package or video>
UnrealEditor-Cmd.exe EspeonAnim.uproject -run=EspeonRenderPreview -Anim=/Game/EspeonAnim/Animations/<name> -Out=<dir> -AllowCommandletRendering -RenderOffscreen
# Other commandlets: EspeonDumpSkeleton, EspeonImportMotion, EspeonServeTest, EspeonSelfTest, EspeonMakePreviewLevel
```

**Release builds:** R8 minifies them, so a release-only crash or silent failure usually means a missing keep rule in `mobile/androidApp/proguard-rules.pro`. One rule already exists for this reason. MediaPipe's Graph uses Flogger, which finds its caller from class names on the stack, so `com.google.common.flogger.**` must be kept or pose detection never starts and every take has zero frames. Always test the release APK on the phone, not only the debug build.

## Workflow rules

- **Issues:** they are in GitHub Issues and use the triage labels in `docs/agents/triage-labels.md`.
- **Branches and PRs:**
  - Use one branch and one PR per issue or feature, opened against `main`.
  - `main` is protected. **Never merge a PR without the owner's explicit go-ahead.**
  - End commit messages with the `Co-Authored-By` line, and PR bodies with the Claude Code line, as instructed.
- **Stacked PRs:** retarget dependents to `main` before deleting a merged base branch. Otherwise GitHub closes them.
- **Unrelated local edits:** don't commit `EspeonAnimUE/EspeonAnim/Config/DefaultEditor.ini` or `DefaultEngine.ini`. They hold the owner's local editor settings and are intentionally uncommitted.
- **Verification:**
  - Verify on the real device or editor where it matters, and report what was and wasn't checked.
  - Phone tests need the owner to perform the take, so prepare the build and say exactly what to try.
- **Writing style:** user-facing app text is short and plain. For an example, see the guidance suggestions in formats.md §4.

## Current state (2026-11-16)

- **Merged to `main`:**
  - The MVP pipeline, working end to end.
  - Hands-free start and stop: countdown, beeps and the jump auto-stop.
  - Guidance framing fixes.
  - Unmirrored front-camera recording.
  - APK size reduction (arm64 only, R8).
  - Start over.
  - The `other` action and the best-jump trim.
  - The processor Python moved into the repo folder.
- **Open:**
  - **PR #15 (`voice-commands`):** "Start" and "Stop" voice commands. It is installed on the Pixel, and its on-device test is pending. Things to check:
    - The system listening chime when the recognizer restarts.
    - The latency of "Stop".
    - The first-time permission flow.
- **Not yet verified on the device:** the multi-jump take and Start over.
- **Deferred:** authoring an Unreal Agent Skill. Don't raise it until the owner asks.

## Known limitations and likely next work

- Guidance thresholds were tuned on one Pixel 10 session. Check them on more rooms and phones.
- **Speed:**
  - Pre-screen takes with MediaPipe and run GEM-X only on the best one or two. This would cut about 6 min to about 2 min per package.
- **Camera data:**
  - The IMU gravity vector is used for camera pitch in the framing checks.
  - It could also set the floor plane.
- The animation doesn't blend back to an idle pose at the end, so it doesn't loop cleanly yet.
- **Platforms and modes:**
  - iOS has no camera layer yet. It needs Swift and AVFoundation on a Mac; the shared KMP code is already iOS-ready.
  - The CPU fallback (`--device cpu`) is untested.
- More action types, such as a run-up jump and landing variants.
- **Licenses:** the GEM-X weights are under the NVIDIA Open Model License, and the GEM-X checkpoint embeds DINOv3 under Meta's license. Both need human legal review before anyone ships.
