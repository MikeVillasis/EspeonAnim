# EspeonAnim data formats

Three contracts connect the parts. Every component must accept the version it was written for and reject (with a clear message) a newer major version.

## 1. Capture package (`.espeon`)

Phone → PC. A zip file with extension `.espeon`.

```
manifest.json
takes/action_01.mp4
takes/action_01.imu.csv        (optional)
takes/calibration.mp4
takes/calibration.imu.csv      (optional)
```

`manifest.json`:

```json
{
  "format": "espeon-package",
  "version": "1.0",
  "created_utc": "2026-11-09T03:12:00Z",
  "app": { "name": "EspeonAnim", "version": "0.1.0", "platform": "android" },
  "device": { "manufacturer": "Google", "model": "Pixel 7", "os_version": "13" },
  "performer": { "height_cm": 175 },
  "action": "jump",
  "takes": [
    {
      "id": "action_01",
      "role": "action",
      "file": "takes/action_01.mp4",
      "imu_file": "takes/action_01.imu.csv",
      "fps": 59.94,
      "width": 1920, "height": 1080,
      "rotation_deg": 90,
      "duration_s": 6.9,
      "camera": { "focal_length_px": 1450.0, "lens_facing": "back", "mirrored": false },
      "guidance": { "verdict": "ok", "warnings": ["low_light"] }
    },
    { "id": "calibration", "role": "calibration", "file": "takes/calibration.mp4", "fps": 59.94, "width": 1920, "height": 1080, "rotation_deg": 90, "duration_s": 16.0 }
  ]
}
```

- `action`: what the performer does, chosen on the app's Setup screen. `jump` (default; also assumed when missing and for bare videos) or `other` (any free movement). `jump` makes the processor look for crouch, flight and landing, trim around them and apply the ballistic height correction; if a take holds several jumps only the best one is kept. `other` skips every jump-specific step and only trims idle time at the start and end. Unknown values are processed like `other`. The UE plugin names the asset `AS_<action>_<timestamp>`.
- `role`: `action` (one or more; the processor uses the best) or `calibration` (exactly one, A-pose then a slow turn in place).
- `performer.height_cm`: optional; improves scale. When missing the processor assumes 170 cm and reports it.
- `rotation_deg`: display rotation of the video stream (0/90/180/270). Readers should still honor the container's rotation metadata.
- `camera.focal_length_px`: optional, focal length in pixels at the stored resolution (from Camera2 intrinsics / AVFoundation). When missing the processor assumes a 70° horizontal FOV.
- `camera.mirrored`: boolean, whether the stored video is horizontally mirrored (a selfie-style flip). The app records un-mirrored for both cameras (`MIRROR_MODE_OFF`), so it writes `false`. Optional; when missing (older packages) readers treat it as `false`, which is the CameraX VideoCapture default. When `true` the processor flips every frame horizontally before analysis, so everything downstream sees an un-mirrored video.
- `guidance.verdict`: `ok` | `warn` | `poor`. `warnings` is a list of codes from §4.
- IMU CSV columns: `t_s,gx,gy,gz` — gravity vector in the camera/device frame (m/s²), timestamped on the video clock. Used to recover camera tilt.

A bare video file is also accepted by the processor (treated as one action take, no calibration).

## 2. Processor output (`motion.json`)

Processor → Unreal plugin. Written into an output folder together with `report.json` and optional debug media.

Units and axes are **Unreal's**: centimeters, Z up, X forward, Y right (left-handed). Rotations are quaternions `[x, y, z, w]`.

```json
{
  "format": "espeon-motion",
  "version": "1.0",
  "skeleton": "ue5_manny",
  "fps": 60,
  "frame_count": 132,
  "bones": ["root", "pelvis", "spine_01", "..."],
  "root_translation": [[0, 0, 0], "... one per frame"],
  "pelvis_translation": [[0, 0, 95.2], "... one per frame (local, parent = root)"],
  "local_rotations": [[[0, 0, 0, 1], "... one per bone"], "... one per frame"]
}
```

- `skeleton` names a skeleton profile in `processor/espeon_processor/skeletons/<name>.json` (bone names, parents and reference local transforms dumped from Unreal).
- `local_rotations[f][b]` is bone `b`'s rotation relative to its parent, in the same space as Unreal's reference pose, so it can be keyed directly onto the skeleton.
- Root motion lives on `root`: ground-projected travel only, Z always 0. The jump height is in `pelvis_translation`, because level playback and CharacterMovement in Walking mode drop root-motion Z.
- Bones not driven by the processor (fingers, twist bones, IK bones) are omitted from `bones` and keep their reference pose.

## 3. Processing report (`report.json`)

```json
{
  "format": "espeon-report",
  "version": "1.0",
  "chosen_take": "action_01",
  "quality": "good | usable | poor",
  "warnings": [{ "code": "low_contrast", "message": "...", "suggestion": "..." }],
  "stats": { "frames_in": 413, "frames_out": 132, "mean_keypoint_confidence": 0.81, "foot_contact_frames": 70 },
  "assumptions": ["performer height assumed 170 cm"]
}
```

Warning severities (`block`, `warn`, `info`) are not written to the report; `info` codes (such as `multiple_jumps`) never lower `quality`. Action `other` never produces jump-specific warnings (`no_jump_detected`, `trim_fallback`, `weak_jump`, `multiple_jumps`); its `details.trim` records the idle-trim window (`mode: "idle_trim"`), and `details.trim.jumps_found` lists every jump found in a multi-jump take.

## 4. Guidance and warning codes

Shared by the phone app and processor.

| Code | Severity | Meaning | Suggestion shown to the user |
| --- | --- | --- | --- |
| `no_person` | block | Nobody detected | Step into frame |
| `multiple_people` | block | More than one person | Only the performer should be visible |
| `body_cropped` | block | Head or feet leave the frame | Step back until there's space above your head and below your feet |
| `camera_moving` | block | Camera not static | Prop the phone up; don't hold it |
| `low_light` | warn | Scene is dark | Turn on more lights or face a window |
| `low_contrast` | warn | Clothes blend into background | Wear clothes that contrast with the wall behind you |
| `camera_low` / `camera_high` | warn | Camera far below/above waist (hip offset from the camera axis > 0.30 body heights, see below) | Place the phone at about waist height |
| `camera_tilted` | warn | Strong pitch or roll | Keep the phone upright and level |
| `too_far` / `too_close` | warn | Standing person fills under 40% / over 70% of the frame height | Fill about half the frame height, with room above your head to jump |
| `motion_blur` | warn | Fast motion blurred | More light lets the camera use a faster shutter |
| `low_fps` | warn | Under 30 fps | Use 60 fps if your phone supports it |
| `multiple_jumps` | info | Processor only, action `jump`: the take contains more than one jump (each at least 0.25 s of airtime and 5 cm of hip rise); the message reads "Found N jumps; kept the best one" and the best one (longest airtime, penalised for an unstable landing) is trimmed out with its crouch, landing and settle | Do one jump per take |

Framing thresholds (single source of truth; mirrored in `GuidanceConfig.kt` and `processor/espeon_processor/framing.py`, change all three together):

- Height fraction = (lowest foot landmark - estimated head top) / frame height, head top = highest head landmark minus 5% of body height. The *standing* value is the max over a trailing 3 s window, so crouching or jumping does not count as "far". Target for a jump is about 40-65%, which leaves headroom; `too_far` below 0.40 (app hold 0.43), `too_close` above 0.70 (hold 0.67). Not evaluated while the body is cropped.
- Camera height: offset of the hips from the camera axis in body heights = tan(elevation) / (height fraction x 2 tan(vfov/2)), elevation = camera pitch + angle of the hips above the image centre. Median over the same 3 s window. `camera_low` above +0.30 (hold 0.24), `camera_high` below -0.30. Camera pitch comes from the IMU gravity vector (`t_s,gx,gy,gz`): sin(pitch) = -gz/|g| for the back camera, +gz/|g| for the front camera (it looks along +z). Without IMU data the processor assumes pitch 0.
- Measured on a well-framed Pixel 10 front-camera jump take: standing 50-60%, pitch +6 degrees (looking slightly up), offset median -0.02 (range -0.37..+0.18 during the jump), no framing warnings.
- Over a whole take the processor reports a framing warning once it holds in at least 20% of frames (same as the app's take summary).

`warn` takes are accepted; when warnings are likely to hurt the result the app says so plainly before the user sends.

## 5. Wi-Fi transfer (phone → Unreal plugin)

The plugin runs a small HTTP server on the PC's LAN address (default port **47800**, configurable). Plain HTTP on the local network, protected by a pairing token.

**Pairing.** The plugin shows a QR code encoding:

```
espeon://pair?v=1&h=<pc-lan-ip>&p=<port>&t=<token>&n=<url-encoded pc name>
```

`t` is a random 128-bit token (hex), regenerated when the user clicks "New pairing code". The phone stores the last pairing and checks it with `GET /api/v1/ping`.

All requests except `ping` send `Authorization: Bearer <token>`; wrong or missing token → `401`.

| Method & path | Body | Response |
| --- | --- | --- |
| `GET /api/v1/ping` | – | `{"name":"<pc name>","app":"EspeonAnim","version":"0.1.0","api":1}` |
| `POST /api/v1/packages` | raw `.espeon` bytes, `Content-Type: application/zip`, max 1 GiB | `201 {"id":"<job id>"}` |
| `GET /api/v1/packages/<id>` | – | `{"id","state","progress","message","quality","warnings":[...]}` |

`state`: `received` → `processing` → `importing` → `done` | `failed`. `progress` is 0–1 across the whole job. On `done`, `quality` and `warnings` come from `report.json` so the phone can show the outcome.

## 6. Processor command line

The plugin (or a user) runs the processor as a separate process:

```
<venv python> -m espeon_processor process --input <file.espeon | video.mp4> --out <dir> [--skeleton ue5_manny] [--device cuda|cpu]
```

- Writes `motion.json`, `report.json` and debug media into `<dir>`.
- Progress: one JSON object per line on stdout, e.g. `{"event":"progress","stage":"reconstruct","progress":0.42,"message":"Reconstructing 3D motion"}`; finishes with `{"event":"done","motion":"<path>","report":"<path>"}` or `{"event":"error","message":"..."}`. Other output goes to stderr.
- Exit code 0 on success, non-zero on failure.
- `python -m espeon_processor doctor` checks the environment (GPU, weights present) and prints a JSON summary.

### §5 details (as implemented)

- `warnings` entries are objects `{"code","message","suggestion"}`; `quality` is `""` until the job is `done`.
- Errors return `{"error":"..."}` with `400` (empty/bad body), `401` (token), `404` (unknown job), `413` (> 1 GiB) or `500`.
- Processing takes minutes (≈1 min per take on an RTX 5070), so clients poll status with no overall timeout shorter than ~15 min.
- The phone accepts only private, link-local or loopback IPv4 addresses and local hostnames (`*.local`, `*.lan`, …) from a pairing code.

## 7. POV mode (first-person hands)

Action `pov` captures **arms, hands and fingers** for first-person rigs. The phone is worn (head strap or chest mount) and looks down at the performer's hands. Everything in §1–§6 still applies; this section lists what is different.

### Package (§1)

- `action: "pov"`.
- New top-level object `pov`: `{ "mount": "head" | "chest" }`. Chosen on the Setup screen (default `head`). Missing means `head`.
- The `calibration` take is a **hands calibration** clip (about 6 s), not the A-pose turn:
  - Both hands are held out in front at chest height for 3 s with fingers spread and palms facing the camera.
  - Then the hands turn over (backs to the camera) for 3 s.
- `performer.height_cm` is used to estimate arm length. When it is missing, 170 cm is assumed.
- `camera.focal_length_px` is strongly recommended. Hand depth is computed from it.
- IMU CSV is required in POV mode, because it gives the mount tilt. If it is missing, the processor assumes the camera points 45° down (`head`) or 30° down (`chest`) and reports an assumption.
- Clips stay silent.

### App flow

- **Setup:** the action choice gains **POV (hands)**. Choosing it shows a Mount choice (Head / Chest) and a line explaining how to wear the phone.
- **Voice commands:** they default to on in POV mode. The microphone permission is asked for on entering Calibration. The toggle still works.
- **Takes:**
  - The countdown is 5 s with beeps, as today.
  - There is no auto-stop. "Stop" (or the button) ends the take, and so does a **30 s** cap (`maxTakeMs` for POV).
- **Calibration:** a fixed 6 s clip with spoken-style beeps at 0 s, 3 s (turn hands over) and 6 s (done). It reuses the existing countdown and phase-tone cues. The screen text for the phases is "Palms to the camera, fingers spread" and then "Turn your hands over".
- **Live analysis:**
  - MediaPipe **Hand Landmarker** (`hand_landmarker.task`, Apache-2.0, up to 2 hands) replaces the pose landmarker while in POV.
  - The overlay draws the 21 landmarks and bones per hand.
  - Pose detection is not run in POV.

### Guidance codes (§4 additions, POV only)

| Code | Severity | Meaning | Suggestion shown to the user |
| --- | --- | --- | --- |
| `no_hands` | block | No hand detected for the last 1 s | Hold your hands in front of the camera |
| `hand_cropped` | warn | A detected hand touches the frame edge (any landmark within 3% of an edge) | Keep your hands inside the picture; tilt the phone down more |
| `hands_too_close` | warn | Hand bounding box taller than 60% of the frame | Hold your hands a bit farther from the camera |
| `low_light`, `motion_blur`, `low_fps` | as §4 | as §4 | as §4 |

- `body_cropped`, `camera_moving`, `camera_low/high`, `camera_tilted`, `too_far/close`, `multiple_people` and `no_person` are **not** evaluated in POV, because the worn camera always moves.
- The take summary verdict uses the same 20%-of-frames rule as §4.
- In the processor, the per-take quality also reports `hands_lost`:
  - It is a warn when either hand is missing in more than 25% of frames.
  - The message reads: "The left hand was out of view N% of the time".

### Processor pipeline for `pov`

1. **Detect:** MediaPipe Hand Landmarker in VIDEO mode, run on every frame. It gives up to 2 hands, each with:
   - Image landmarks (normalized).
   - World landmarks (metres, hand-centred).
   - Handedness.

   Handedness is stabilised over time, and a hand may not swap sides between neighbouring frames unless both are re-detected. The front camera is never used for POV; if `camera.mirrored` is true, frames are flipped first as today.
2. **Hand size from calibration:**
   - Measure each hand's wrist→middle_mcp length and the finger segment lengths from the calibration clip's world landmarks, taking the median over frames with a high presence score.
   - Without calibration, scale MediaPipe's world landmarks to a hand length of 0.108 × height.
3. **Hand position in camera space:**
   - Depth comes from the pinhole model, comparing the known wrist→middle_mcp length with its image length (taking the hand orientation from the world landmarks into account).
   - The wrist position in the camera frame comes from back-projecting the image wrist landmark at that depth.
4. **Camera to head frame:**
   - Take the **median** IMU gravity over the take to get the mount pitch and roll. Rotate camera-space positions into a level head frame: X right, Y forward, Z up, with the origin at the eyes.
   - Per-frame head motion is **not** applied, so the hands are expressed relative to the head, which is what an FP camera rig needs.
   - Chest mount: the origin is shifted to the eyes by a fixed offset of (0, +0.05, +0.30) × (height / 1.70) m.
5. **Clean-up:**
   - Fill gaps of up to 0.5 s by interpolation. In longer gaps, ease the arm to a rest pose (hand at the hip, below the view) over 0.3 s.
   - Smooth positions and finger angles with a One-Euro filter tuned for 60 fps output.
   - Resample to 60 fps.
6. **Retarget to Manny:**
   - The shoulders come from Manny's reference pose, after placing Manny's eyes at the head-frame origin (scaled by Manny height ÷ performer height).
   - Wrist targets are scaled by Manny arm length ÷ performer arm length. Performer arm length = 0.44 × height, unless calibration gives better.
   - **Two-bone IK** on `upperarm`/`lowerarm`:
     - The elbow pole points down and out, (±0.3, 0, −1) in the head frame.
     - If the target is out of reach, the arm straightens towards it.
     - `clavicle` gets up to 15° of elevation and protraction towards far targets.
   - `hand_*` rotation comes from the palm frame (wrist, index_mcp, pinky_mcp) of the world landmarks.
   - **Fingers:**
     - `*_metacarpal_*` stay at the reference pose.
     - Each finger's three joints are solved by aligning bone directions to landmark segments: thumb = CMC→MCP→IP→TIP onto `thumb_01..03`, and the other fingers = MCP→PIP→DIP→TIP onto `*_01..03`.
     - Rotations are clamped to anatomical ranges (flexion −10°..110°, abduction ±25°).
7. **Output (`motion.json`):**
   - `bones` lists only `clavicle_*`, `upperarm_*`, `lowerarm_*`, `hand_*` and the 15 finger bones per side.
   - `root_translation` is all zeros. `pelvis_translation` is Manny's reference pelvis translation every frame, so the body stays still in the reference pose.
   - The take is not trimmed, except that idle time at the start and end, where no hand is visible, is cut.
   - `report.json` `details.pov` records the mount, the mount pitch and roll, the hand lengths, the assumptions and the `hands_lost` fractions.

The best take is chosen the same way as `other`: by the take with the highest `mean_keypoint_confidence` and fewest lost-hand frames. The plugin needs no change; the asset is named `AS_pov_<timestamp>`.
