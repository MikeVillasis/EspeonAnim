"""Framing / camera-height checks on MediaPipe landmarks, mirroring the app's GuidanceEngine.

Single source of truth for the numbers: docs/formats.md section 4 ("Framing thresholds"). The app side is
mobile/shared/.../guidance/GuidanceConfig.kt + GuidanceEngine.kt; change all three together.
"""
from __future__ import annotations
import math
from pathlib import Path
import numpy as np

# --- thresholds (keep equal to GuidanceConfig.kt) ---------------------------------------------------------
TOO_FAR_ENTER = 0.40       # standing performer under 40% of the frame height; jump target is ~40-65%
TOO_CLOSE_ENTER = 0.70
CAMERA_HEIGHT_ENTER = 0.30  # hip offset from the camera axis, in body heights (~50 cm)
HEAD_TOP_EXTRA = 0.05      # head top = highest head landmark - 5% of body height
CROP_MARGIN = 0.02
WINDOW_S = 3.0             # standing height = rolling max, camera offset = rolling median
REPORT_FRACTION = 0.20     # a warning is reported once it holds in this fraction of frames
DEFAULT_VFOV_DEG = 50.0

HEAD, FEET, HIPS = [0, 2, 5, 7, 8], [27, 28, 29, 30, 31, 32], [23, 24]


def gravity_from_csv(path):
    """Median gravity vector (device frame, points toward the sky) from a `t_s,gx,gy,gz` csv, or None."""
    try:
        a = np.loadtxt(path, delimiter=",", skiprows=1, ndmin=2)
    except (OSError, ValueError):
        return None
    if a.shape[0] == 0 or a.shape[1] < 4:
        return None
    return np.median(a[:, 1:4], axis=0)


def camera_pitch_deg(gravity, front):
    """Elevation of the camera axis above horizontal. Back camera looks along -z, front camera along +z."""
    g = np.asarray(gravity, float)
    n = np.linalg.norm(g)
    if n < 1.0:
        return 0.0
    s = (g[2] if front else -g[2]) / n
    return math.degrees(math.asin(max(-1.0, min(1.0, s))))


def vertical_fov_deg(focal_length_px, upright_height_px):
    if not focal_length_px or not upright_height_px:
        return DEFAULT_VFOV_DEG
    return math.degrees(2 * math.atan(upright_height_px / (2.0 * focal_length_px)))


def _rolling(vals, win, fn):
    out = np.full(len(vals), np.nan)
    for i in range(len(vals)):
        w = vals[max(0, i - win + 1):i + 1]
        w = w[~np.isnan(w)]
        if len(w):
            out[i] = fn(w)
    return out


def analyze(landmarks, fps, pitch_deg=0.0, vfov_deg=DEFAULT_VFOV_DEG, frame_range=None):
    """landmarks: (T,33,>=3) normalized x,y,visibility (NaN where nobody was found). Returns the standing
    height / camera offset medians and the fractions of frames that trip each warning (restricted to
    frame_range=(a,b) when given; the rolling windows still see the whole clip)."""
    L = np.asarray(landmarks, float)
    T = len(L)
    det = ~np.isnan(L[:, :, 0]).all(1)
    if not det.any():
        return {"detected_frames": 0, "cropped_fraction": 0.0, "standing_height_median": float("nan"),
                "camera_offset_median": float("nan"), "too_far_fraction": 0.0, "too_close_fraction": 0.0,
                "camera_low_fraction": 0.0, "camera_high_fraction": 0.0,
                "pitch_deg": float(pitch_deg), "vfov_deg": float(vfov_deg)}
    Ld = np.where(det[:, None, None], L, 0.5)    # placeholder for undetected frames, masked out below
    hy = Ld[:, HEAD, 1].min(1)
    fy = Ld[:, FEET, 1].max(1)
    hip = Ld[:, HIPS, 1].mean(1)
    body0 = np.maximum(fy - hy, 0.05)
    top = hy - HEAD_TOP_EXTRA * body0
    cur = np.maximum(fy - top, 0.01)
    feet_vis = np.nan_to_num(Ld[:, FEET, 2], nan=1.0).mean(1)
    hips_vis = np.nan_to_num(Ld[:, HIPS, 2], nan=1.0).mean(1)
    feet_hidden = (feet_vis < 0.35) & (hips_vis > 0.5)
    cropped = det & ((top < CROP_MARGIN) | (fy > 1 - CROP_MARGIN) | feet_hidden)
    ok = det & ~cropped
    win = max(1, int(round(WINDOW_S * fps)))
    stand = _rolling(np.where(ok, cur, np.nan), win, np.max)
    ht = math.tan(math.radians(vfov_deg) / 2)
    elev = math.radians(pitch_deg) + np.arctan((0.5 - hip) * 2 * ht)
    raw_off = np.tan(elev) / (np.where(np.isnan(stand), 1.0, stand) * 2 * ht)
    offset = _rolling(np.where(ok, raw_off, np.nan), win, np.median)
    a, b = frame_range or (0, T)
    sl = slice(a, b)
    n = max(int(det[sl].sum()), 1)
    valid = ok[sl] & ~np.isnan(stand[sl])

    def frac(mask):
        return float((mask & valid).sum() / n)

    with np.errstate(invalid="ignore"):
        return {
            "detected_frames": int(det[sl].sum()), "cropped_fraction": float(cropped[sl].sum() / n),
            "standing_height_median": float(np.median(stand[sl][valid])) if valid.any() else float("nan"),
            "camera_offset_median": float(np.median(offset[sl][valid])) if valid.any() else float("nan"),
            "too_far_fraction": frac(stand[sl] < TOO_FAR_ENTER), "too_close_fraction": frac(stand[sl] > TOO_CLOSE_ENTER),
            "camera_low_fraction": frac(offset[sl] > CAMERA_HEIGHT_ENTER),
            "camera_high_fraction": frac(offset[sl] < -CAMERA_HEIGHT_ENTER),
            "pitch_deg": float(pitch_deg), "vfov_deg": float(vfov_deg),
        }


def framing_codes(res):
    """Warning codes whose condition holds in at least REPORT_FRACTION of the analysed frames."""
    out = []
    for code, key in (("too_far", "too_far_fraction"), ("too_close", "too_close_fraction"),
                      ("camera_low", "camera_low_fraction"), ("camera_high", "camera_high_fraction")):
        if res[key] >= REPORT_FRACTION:
            out.append(code)
    return out


def take_camera_info(take):
    """(pitch_deg, vfov_deg, have_imu) for a package Take (manifest entry in take.info); pitch 0 without IMU."""
    info = take.info or {}
    cam = info.get("camera") or {}
    front = cam.get("lens_facing") == "front"
    w, h = info.get("width"), info.get("height")
    rot = int(info.get("rotation_deg") or 0) % 180
    upright_h = (w if rot else h) if (w and h) else None
    vfov = vertical_fov_deg(cam.get("focal_length_px"), upright_h)
    g = None
    if info.get("imu_file"):
        g = gravity_from_csv(Path(take.path).with_name(Path(info["imu_file"]).name))
    return (camera_pitch_deg(g, front) if g is not None else 0.0), vfov, g is not None
