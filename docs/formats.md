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
- Root motion lives on `root` (ground-projected travel plus vertical lift for jumps). `pelvis_translation` carries the remaining offset.
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
