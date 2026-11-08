"""Per-frame person boxes from MediaPipe Pose Landmarker (Apache-2.0), replacing YOLOX HumanArt.

person_boxes(frames_rgb) -> (T,4) float32 xyxy in pixels, padded, gap-filled and smoothed.
`frames_rgb` may be any sequence of HxWx3 uint8 RGB arrays (e.g. gem VideoFrames).
"""
from __future__ import annotations
from pathlib import Path
import numpy as np

MODEL = Path(__file__).resolve().parents[1] / "models" / "pose_landmarker_heavy.task"


def _detect(frames, fps=30.0, model=MODEL, landmarks_out=None):
    import mediapipe as mp
    from mediapipe.tasks.python import vision, BaseOptions
    opts = vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(model)),
        running_mode=vision.RunningMode.VIDEO, num_poses=1,
        min_pose_detection_confidence=0.3, min_pose_presence_confidence=0.3, min_tracking_confidence=0.3)
    boxes = np.full((len(frames), 4), np.nan, np.float32)
    if landmarks_out is not None:
        landmarks_out[:] = [np.full((33, 4), np.nan, np.float32) for _ in range(len(frames))]
    with vision.PoseLandmarker.create_from_options(opts) as lm:
        for i in range(len(frames)):
            f = np.ascontiguousarray(frames[i])
            h, w = f.shape[:2]
            res = lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=f), int(i * 1000 / fps))
            if not res.pose_landmarks:
                continue
            pts = np.array([[p.x * w, p.y * h] for p in res.pose_landmarks[0]], np.float32)
            if landmarks_out is not None:  # normalised x, y, visibility, presence
                landmarks_out[i] = np.array([[p.x, p.y, p.visibility if p.visibility is not None else np.nan,
                                              p.presence if p.presence is not None else np.nan]
                                             for p in res.pose_landmarks[0]], np.float32)
            vis = np.array([min(p.visibility, p.presence) if p.presence is not None else p.visibility
                            for p in res.pose_landmarks[0]])
            ok = vis > 0.2
            if ok.sum() < 8:
                continue
            pts = pts[ok]
            boxes[i] = [*pts.min(0), *pts.max(0)]
    return boxes


def _fill(boxes):
    t = np.arange(len(boxes))
    ok = ~np.isnan(boxes).any(1)
    if not ok.any():
        raise ValueError("MediaPipe found no person in any frame")
    out = np.stack([np.interp(t, t[ok], boxes[ok, k]) for k in range(4)], 1)
    return out.astype(np.float32), ok


def _smooth(b, win=9):
    pad = np.pad(b, ((win // 2, win // 2), (0, 0)), mode="edge")
    k = np.ones(win) / win
    return np.stack([np.convolve(pad[:, c], k, mode="valid") for c in range(4)], 1).astype(np.float32)


def person_boxes(frames_rgb, fps=30.0, pad=0.15, return_observed=False, landmarks_out=None):
    h, w = frames_rgb[0].shape[:2]
    raw = _detect(frames_rgb, fps, landmarks_out=landmarks_out)
    filled, observed = _fill(raw)
    filled = _smooth(filled)
    cx, cy = (filled[:, 0] + filled[:, 2]) / 2, (filled[:, 1] + filled[:, 3]) / 2
    bw, bh = (filled[:, 2] - filled[:, 0]) * (1 + 2 * pad), (filled[:, 3] - filled[:, 1]) * (1 + 2 * pad)
    out = np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], 1)
    out[:, [0, 2]] = np.clip(out[:, [0, 2]], 0, w - 1)
    out[:, [1, 3]] = np.clip(out[:, [1, 3]], 0, h - 1)
    out = out.astype(np.float32)
    return (out, observed) if return_observed else out
