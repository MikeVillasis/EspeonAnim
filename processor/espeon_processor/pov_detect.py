"""POV step 1: MediaPipe Hand Landmarker (Apache-2.0) over the frames of one take, with handedness stabilisation.

Slots: 0 = the performer's left hand, 1 = the right hand. MediaPipe's handedness label assumes a mirrored (selfie)
image; the processor always works on un-mirrored frames (manifest `camera.mirrored` is undone in video.prepare_clip),
so a label "Left" there means the performer's RIGHT hand and the labels are swapped here.
"""
from __future__ import annotations
import hashlib, json, time
from dataclasses import dataclass
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "models" / "hand_landmarker.task"
CACHE_VERSION = "pov-hands-v1"        # bump when anything affecting hands.npz changes
LEFT, RIGHT = 0, 1


@dataclass
class HandTrack:
    """Detections of one take. img: normalised x, y, z (image space); world: metres, hand centred, camera orientation
    (x right, y down, z away from the camera). NaN where the hand is absent."""
    fps: float
    width: int
    height: int
    img: np.ndarray       # (T,2,21,3)
    world: np.ndarray     # (T,2,21,3)
    score: np.ndarray     # (T,2) handedness confidence, NaN when absent
    mean_luma: float = float("nan")

    @property
    def T(self):
        return len(self.img)

    @property
    def present(self):
        return ~np.isnan(self.score)


# --- handedness stabilisation (pure, unit-tested) ------------------------------------------------------------

LABEL_MISMATCH = 1.0       # cost of putting a hand into the slot its (swapped) label does not name
UNKNOWN_SLOT = 1.3         # cost of a slot that has not been seen recently
DIST_WEIGHT, DIST_CAP = 10.0, 2.5
CROSS_PENALTY = 0.3        # left hand to the right of the right hand (image x), mild prior
RECENT_S = 0.4


def assign_slots(dets, fps):
    """dets[t] = list of (label, score, (wrist_x, wrist_y)) for frame t, label = MediaPipe's raw 'Left'/'Right'.
    Returns slot[t] = list with the slot (0 left / 1 right) of each detection of frame t, never using one slot twice.
    A lone hand keeps the slot it was tracked in (position continuity beats a flickering label); the two hands only
    swap slots when both are detected and the swapped assignment is clearly cheaper."""
    last = [None, None]            # (frame, xy) of the last detection per slot
    recent = max(1, int(round(RECENT_S * fps)))
    out = []

    def cost(d, s, t):
        label, score, xy = d
        phys = RIGHT if label == "Left" else LEFT        # un-mirrored image: swap the label
        c = LABEL_MISMATCH * float(score or 1.0) if phys != s else 0.0
        if last[s] is not None and t - last[s][0] <= recent:
            c += min(DIST_WEIGHT * float(np.hypot(xy[0] - last[s][1][0], xy[1] - last[s][1][1])), DIST_CAP)
        else:
            c += UNKNOWN_SLOT
        return c

    for t, ds in enumerate(dets):
        ds = list(ds)[:2]
        if len(ds) == 2 and np.hypot(ds[0][2][0] - ds[1][2][0], ds[0][2][1] - ds[1][2][1]) < 0.02:
            ds = [max(ds, key=lambda d: d[1] or 0.0)]      # the same hand detected twice
        if not ds:
            out.append([])
            continue
        if len(ds) == 1:
            s = min((LEFT, RIGHT), key=lambda s: cost(ds[0], s, t))
            slots = [s]
        else:
            a = cost(ds[0], LEFT, t) + cost(ds[1], RIGHT, t) + (CROSS_PENALTY if ds[0][2][0] > ds[1][2][0] else 0.0)
            b = cost(ds[0], RIGHT, t) + cost(ds[1], LEFT, t) + (CROSS_PENALTY if ds[1][2][0] > ds[0][2][0] else 0.0)
            slots = [LEFT, RIGHT] if a <= b else [RIGHT, LEFT]
        for d, s in zip(ds, slots):
            last[s] = (t, d[2])
        out.append(slots)
    return out


# --- detection --------------------------------------------------------------------------------------------------

def file_key(path, mirrored=False, chunk=1 << 22):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while b := f.read(chunk):
            h.update(b)
    return h.hexdigest()[:20] + "-" + CACHE_VERSION + ("-mirrored" if mirrored else "")


def detect_video(video, model=MODEL, progress=None, fps_hint=None):
    """Run the Hand Landmarker (VIDEO mode, 2 hands) over every frame of an upright, un-mirrored video."""
    import cv2
    import mediapipe as mp
    from mediapipe.tasks.python import vision, BaseOptions
    progress = progress or (lambda f, m: None)
    if not Path(model).exists():
        from .gem_stage import ProcessorError
        raise ProcessorError(f"hand model missing: {model} (run tools/setup_processor.ps1)")
    cap = cv2.VideoCapture(str(video))
    fps = cap.get(cv2.CAP_PROP_FPS) or fps_hint or 30.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
    w, h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    opts = vision.HandLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(model)), running_mode=vision.RunningMode.VIDEO, num_hands=2,
        min_hand_detection_confidence=0.4, min_hand_presence_confidence=0.4, min_tracking_confidence=0.4)
    frames_img, frames_world, dets, lumas = [], [], [], []
    last_ts = -1
    i = 0
    with vision.HandLandmarker.create_from_options(opts) as lm:
        while True:
            ok, bgr = cap.read()
            if not ok:
                break
            rgb = np.ascontiguousarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
            if i % 30 == 0:
                lumas.append(float(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).mean()))
            ts = max(last_ts + 1, int(round(i * 1000.0 / fps)))
            last_ts = ts
            res = lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), ts)
            d, im, wo = [], [], []
            for k, lms in enumerate(res.hand_landmarks):
                cat = res.handedness[k][0]
                a = np.array([[p.x, p.y, p.z] for p in lms], np.float64)
                b = np.array([[p.x, p.y, p.z] for p in res.hand_world_landmarks[k]], np.float64)
                d.append((cat.category_name, float(cat.score), (a[0, 0], a[0, 1])))
                im.append(a); wo.append(b)
            dets.append(d); frames_img.append(im); frames_world.append(wo)
            i += 1
            if total and i % 15 == 0:
                progress(min(1.0, i / total), "detecting hands")
    cap.release()
    T = len(dets)
    img = np.full((T, 2, 21, 3), np.nan); world = np.full((T, 2, 21, 3), np.nan); score = np.full((T, 2), np.nan)
    slots = assign_slots(dets, fps)
    for t in range(T):
        for k, s in enumerate(slots[t]):
            img[t, s], world[t, s], score[t, s] = frames_img[t][k], frames_world[t][k], dets[t][k][1]
    return HandTrack(float(fps), w, h, img, world, score, float(np.median(lumas)) if lumas else float("nan"))


def save_track(path, tr: HandTrack):
    np.savez_compressed(path, fps=tr.fps, size=np.array([tr.width, tr.height]), img=tr.img, world=tr.world,
                        score=tr.score, mean_luma=tr.mean_luma)


def load_track(path):
    d = np.load(path)
    return HandTrack(float(d["fps"]), int(d["size"][0]), int(d["size"][1]), d["img"], d["world"], d["score"], float(d["mean_luma"]))


def track_video(video, cache_root, mirrored=False, progress=None, keep_intermediate=False, label="", model=MODEL):
    """Prepare (rotation baked in, un-mirrored) and run detection for one take; cached by file hash. Returns HandTrack."""
    from .video import prepare_clip
    progress = progress or (lambda f, m: None)
    cdir = Path(cache_root) / file_key(video, mirrored)
    npz = cdir / "hands.npz"
    if npz.exists():
        progress(1.0, f"{label}: using cached hand detections")
        return load_track(npz)
    cdir.mkdir(parents=True, exist_ok=True)
    progress(0.0, f"{label}: preparing video")
    prepared = prepare_clip(video, cdir, mirrored=mirrored)
    tr = detect_video(prepared, model, lambda f, m: progress(0.05 + 0.95 * f, f"{label}: {m}"))
    save_track(npz, tr)
    if not keep_intermediate:
        prepared.unlink(missing_ok=True)
    progress(1.0, f"{label}: hands detected")
    return tr
