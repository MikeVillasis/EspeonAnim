# EspeonAnim

Record a person with your phone, get a clean, game-ready animation on the Unreal Engine mannequin.

EspeonAnim is an open-source (MIT) capture pipeline for small game teams. The phone app guides the performer through a calibration clip and a few takes, then sends one package over Wi-Fi to an Unreal Editor plugin. A local processor reconstructs the 3D motion from the video, cleans it up (foot locking, smoothing, physically plausible flight), and the plugin creates a 60 fps AnimSequence with root motion on the UE5 mannequin plus an FBX.

> Status: early MVP. The first supported action is a jump. Android first, iOS later.

## How it fits together

```
Android app ──Wi-Fi (.espeon package)──► Unreal plugin ──► processor (Python, NVIDIA GPU) ──► motion.json
  live pose guidance                     QR pairing         GEM-X 3D reconstruction            │
  calibration + takes                    job queue          retarget to Manny, cleanup ◄───────┘
                                         AnimSequence + FBX
```

| Folder | What it is |
| --- | --- |
| [`mobile/`](mobile/) | Kotlin Multiplatform app. Shared logic (guidance, package format, transfer) plus the Android camera app. |
| [`EspeonAnimUE/EspeonAnim/Plugins/EspeonAnim/`](EspeonAnimUE/EspeonAnim/Plugins/EspeonAnim/) | The UE 5.8 editor plugin studios install. The surrounding project is the test host. |
| [`processor/`](processor/) | Python pipeline: video → 3D motion → Manny animation. See [`processor/README.md`](processor/README.md). |
| [`docs/`](docs/) | [Plan](docs/plan.md), [data formats and APIs](docs/formats.md), progress reports. |

## Requirements

- **PC:** Windows 10/11, NVIDIA GPU with ≥ 8 GB VRAM (tested on an RTX 5070 12 GB), Unreal Engine 5.8, Python 3.12, ffmpeg.
- **Phone:** Android 10 or newer. Pixel 7 class or better recommended.
- **Unreal project:** the UE5 mannequin (Manny/Quinn from the Third Person template). In this repo's test project, copy it from your engine install: `Templates/TemplateResources/High/Characters/Content/Mannequins` → `Content/Characters/Mannequins` (Epic content is not redistributed here).

## Quick start (development)

1. Set up the processor: follow [`processor/README.md`](processor/README.md), then run `python -m espeon_processor doctor`.
2. Open `EspeonAnimUE/EspeonAnim/EspeonAnim.uproject` (builds the plugin), then **Tools → EspeonAnim → EspeonAnim Capture**. The tab shows a QR code.
3. Build and install the app: `cd mobile && gradlew :androidApp:installDebug` (Android SDK required).
4. In the app: scan the QR code, record the calibration clip and a few jump takes, then **Send to PC**.
5. The animation appears under `/Game/EspeonAnim/Animations`, and an FBX under `Saved/EspeonAnim/Exports`.

Without a phone you can process any video directly: **Import package/video file…** in the tab, or
`python -m espeon_processor process --input clip.mp4 --out out\clip`.

## Models and licenses

EspeonAnim's own code is MIT. Every model it runs was chosen because its license allows commercial use. Review them before shipping:

- **NVIDIA GEM-X** (code Apache-2.0, weights under the NVIDIA Open Model License) with the **SOMA** body model (Apache-2.0) for 3D reconstruction.
- **Google MediaPipe Pose Landmarker** (Apache-2.0) for person boxes on the PC and live guidance on the phone.
- **QR Code generator** by Nayuki (MIT), vendored in the plugin. **ZXing** (Apache-2.0) for QR scanning on the phone.

Models trained on SMPL/AMASS/Human3.6M (non-commercial) are deliberately not used. See [`processor/README_GEMX.md`](processor/README_GEMX.md) for weight sources and license notes.

## License

[MIT](LICENSE)
