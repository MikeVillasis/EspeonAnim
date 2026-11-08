# EspeonAnim plan

EspeonAnim turns a phone recording of a real person into a clean, game-ready 3D animation in Unreal Engine.

## MVP

A performer records themselves jumping on an Android phone. The app guides them until it has a usable take plus a short calibration clip, bundles everything into one package and sends it to the PC. The Unreal plugin hands the package to the processor, imports the result and retargets it so the UE5 mannequin (Manny) performs the jump.

Output: body-only AnimSequence on Manny, **60 fps**, root motion, feet locked during contact, no jitter, plus an FBX export. Light enough for a small team to drop into any game.

## Architecture

```
Phone app (Kotlin Multiplatform)          PC
 ├─ native camera (CameraX / AVFoundation)  ├─ EspeonAnim UE editor plugin (UE 5.8)
 ├─ live light pose → take guidance         │   ├─ QR pairing + Wi-Fi receiver (manual file import fallback)
 ├─ calibration clip (A-pose turn)          │   ├─ launches processor, shows progress
 └─ .espeon package ──── Wi-Fi ───────────► │   └─ imports result, IK-retargets to Manny, exports FBX
                                            └─ processor (Python 3.12, own venv, NVIDIA GPU, CPU fallback)
                                                video → 3D pose → body proportions from calibration →
                                                cleanup (filtering, foot locking, root motion) → 60 fps animation
```

- Single phone, single camera. "Another take" means a retake; no multi-angle fusion in the MVP.
- Guidance warns and coaches (light, contrast, camera height/tilt) and says when footage is likely to give a bad result. It blocks only unusable takes (head/feet out of frame, moving camera, no person). Portrait and landscape both allowed.
- The calibration clip measures the performer's proportions (bone lengths, height). No per-person model training in the MVP.
- Blender is a fallback only, used if a step can't be done well in Python or Unreal.

## Constraints

- **Licenses:** every model, weight file and dependency must allow commercial use. Project is MIT.
- **Phones:** Android 10+ (minSdk 29); must run well on a Pixel 7. App id `io.github.mikevillasis.espeonanim`. iOS later, so shared logic lives in KMP and only the camera layer is per-platform.
- **Unreal:** built and tested on 5.8. Avoid newer-than-5.4 APIs where it's easy.
- **Processor:** Windows + NVIDIA first; the plugin sets up its environment and downloads weights on first use. Mac and a standalone build come later.

## Repo layout

| Path | Contents |
| --- | --- |
| `EspeonAnimUE/EspeonAnim/` | UE 5.8 test host project |
| `EspeonAnimUE/EspeonAnim/Plugins/EspeonAnim/` | the plugin studios install |
| `processor/` | Python pipeline |
| `mobile/` | Kotlin Multiplatform app |
| `docs/` | plan, decisions, progress reports |
| `testdata/raw/` | local test clips (not committed) |

## Build order

1. Find and license-check pose models.
2. Processor: test clips → clean 60 fps animation file.
3. Plugin: import and retarget onto Manny.
4. Android app: camera, guidance, packaging.
5. Wi-Fi transfer and QR pairing.
