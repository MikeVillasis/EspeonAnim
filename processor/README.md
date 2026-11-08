# EspeonAnim processor

Turns a phone recording of a performer into a clean 60 fps animation for the UE5 mannequin (Manny):
`motion.json` + `report.json` (+ debug PNGs). Formats and the CLI contract are in `../docs/formats.md`
(sections 1, 2, 3, 4 and 6). GEM-X / Windows setup notes: `README_GEMX.md`.

## Install

Windows 10/11 + NVIDIA GPU first (CPU fallback exists but is slow). Prerequisites: [git](https://git-scm.com/download/win);
`ffmpeg`/`ffprobe` on `PATH` (the script tries `winget install Gyan.FFmpeg` if missing). No admin rights needed.

**Recommended: the setup script** (also behind the "Install / Repair processor" button in the UE plugin's EspeonAnim Capture tab):

```
powershell -ExecutionPolicy Bypass -File tools\setup_processor.ps1 -AcceptModelLicenses
powershell -ExecutionPolicy Bypass -File tools\setup_processor.ps1 -DryRun     # only print the plan
```

It finds or installs [uv](https://docs.astral.sh/uv/), creates `.venv` (Python 3.12), installs torch (`cu128` when `nvidia-smi`
finds an NVIDIA GPU, else `cpu`; override with `-Cuda cu128|cpu`), clones GEM-X at the pinned commit, installs the packages,
downloads the weights into `models/` (files already present with the right size are skipped), then runs `doctor`.
It is idempotent, so re-running repairs a broken install. Options: `-ProcessorDir`, `-Python`, `-Cuda`, `-DryRun`. Progress lines are
`ESPEON_SETUP {"step":...,"progress":...,"message":...}`; exit code 0 = ready (2 licenses not accepted, 3 git missing, 4 uv,
5 install, 6 download, 7 doctor failed). `-AcceptModelLicenses` confirms: GEM-X weights = NVIDIA Open Model License,
SOMA-X assets = Apache-2.0, MediaPipe pose model = Apache-2.0 (about 7.5 GB download in total).

**Manual fallback** (what the script does):

```
uv venv .venv --python 3.12
uv pip install --python .venv\Scripts\python.exe torch torchvision --index-url https://download.pytorch.org/whl/cu128
git clone --depth 1 https://github.com/NVlabs/gem-x third_party/gem-x
uv pip install --python .venv\Scripts\python.exe -e "third_party/gem-x[video]" --extra-index-url https://download.pytorch.org/whl/cu128
uv pip install --python .venv\Scripts\python.exe -e ".[test]"
```

(The script pins GEM-X to commit `4aef1452e5df171deff4f293c08e643eb450981e`.) Weights go in `models/` (download commands in
`README_GEMX.md`). Check everything with `python -m espeon_processor doctor`.
## Usage

```
python -m espeon_processor process --input take.espeon --out out\job1 [--skeleton ue5_manny] [--device cuda|cpu] [--keep-intermediate]
python -m espeon_processor process --input clip.mp4   --out out\job2        # bare video = one action take, no calibration
python -m espeon_processor doctor
```

stdout is JSON lines only (`progress` events, then `done` with the `motion`/`report` paths, or `error`);
logs and tracebacks go to stderr. Exit code 0 on success. Output folder:

```
motion.json            Manny animation, 60 fps, UE units (cm, Z up, forward = +Y), quaternions xyzw
report.json            chosen take, quality good/usable/poor, warnings, stats, assumptions, details
debug/contact_sheet.png   FK stick figures (front + side) rebuilt from motion.json
debug/height_contacts.png pelvis/sole heights and foot contacts
.cache/<hash>/         gem_world.npz + landmark stats per input video (re-runs skip GEM-X)
```

`--keep-intermediate` also keeps `prepared.mp4`, `motion.pt` and the unpacked package.

## Pipeline

1. **Input** (`package.py`): unzip `.espeon`, validate `manifest.json` (rejects a newer major version), or take a bare video.
2. **Reconstruct** (`gem_stage.py`): ffmpeg normalisation (`video.py`), MediaPipe person boxes (`boxes.py`) and GEM-X
   (`run_gem.py`, child process for progress/VRAM) -> SOMA 77-joint skeleton at 30 fps (`gem_export.py`); cached by file hash.
3. **Take selection** (`pipeline.score_take`): jump detected from foot contacts + hips, MediaPipe landmark visibility,
   tracked-frame fraction, cropping and the app's guidance verdict; best score wins, all scores go to the report.
4. **Calibration** (`analysis.measure_body`): leg length, height and arm span as medians over the stillest frames of the
   calibration clip (or of the action take, with an assumption in the report). `scale = Manny leg / performer leg`
   is applied to root/pelvis translation.
5. **Trim** (`analysis.action_window`): crouch onset - 0.5 s .. takeoff (all contacts off) .. landing .. stable standing + 0.5 s,
   cut where the performer starts walking away. No jump found -> whole clip + `no_jump_detected`.
6. **Retarget** (`retarget.py`): SOMA -> UE axes (reflection), then a *matched rest pose*: Manny's reference pose swung so
   every bone direction equals SOMA's T-pose direction; per frame
   `R_manny = R_soma(t) R_soma_rest^-1 R_matched_rest`, converted to parent-local. Spine1/2/Chest are distributed over
   spine_01..05. Pelvis is placed so Manny's hip joints follow the performer's.
7. **Ground and root** (`solve.py`): floor offset from confidently planted feet (a slowly varying curve, because GEM's floor drifts
   by several cm), root = support centre of the planted feet on the floor (Z stays 0, the jump height is in the pelvis); the performer's initial
   facing is rotated to +Y and the root starts at the origin.
8. **Cleanup** (`cleanup.py`): zero-phase Butterworth on quaternions (9 Hz, sign-continuous) and translations (7 Hz),
   foot locking per contact phase (ankle/ball pinned to the phase mean, two-bone IK, knee pole from the original pose, 4-frame
   blend), 30 -> 60 fps (slerp, PCHIP).
9. **Output** (`motion_io.py`, `debug_plots.py`, `validate.py`): schema validation, report, debug images, and the
   retarget check (mean angle between Manny FK bone directions and SOMA directions).

## Tests

```
python -m pytest            # uses out/Jump*/gem_world.npz when present (skips GEM-dependent tests otherwise)
python tools/make_test_package.py   # builds out/test_package.espeon from testdata/raw (Jump1-4 + APoseTurn calibration)
```

## Known limits

- GEM-X underestimates jump height (hips rise ~0.1-0.2 m); contacts decide flight time. The root never lifts; the pelvis carries the flight.
- Only a single person, static camera; fingers, twist and IK bones keep their reference pose.
- Camera intrinsics and IMU files in the package are not used yet.
