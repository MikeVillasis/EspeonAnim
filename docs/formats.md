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
      "camera": { "focal_length_px": 1450.0, "lens_facing": "back" },
      "guidance": { "verdict": "ok", "warnings": ["low_light"] }
    },
    { "id": "calibration", "role": "calibration", "file": "takes/calibration.mp4", "fps": 59.94, "width": 1920, "height": 1080, "rotation_deg": 90, "duration_s": 16.0 }
  ]
}
```

- `role`: `action` (one or more; the processor uses the best) or `calibration` (exactly one, A-pose then a slow turn in place).
- `performer.height_cm`: optional; improves scale. When missing the processor assumes 170 cm and reports it.
- `rotation_deg`: display rotation of the video stream (0/90/180/270). Readers should still honor the container's rotation metadata.
- `camera.focal_length_px`: optional, focal length in pixels at the stored resolution (from Camera2 intrinsics / AVFoundation). When missing the processor assumes a 70° horizontal FOV.
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
| `camera_low` / `camera_high` | warn | Camera far below/above waist | Place the phone at about waist height |
| `camera_tilted` | warn | Strong pitch or roll | Keep the phone upright and level |
| `too_far` / `too_close` | warn | Person too small/large in frame | Move to fill about 60–80% of the frame height |
| `motion_blur` | warn | Fast motion blurred | More light lets the camera use a faster shutter |
| `low_fps` | warn | Under 30 fps | Use 60 fps if your phone supports it |

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
