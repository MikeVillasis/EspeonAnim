"""POV steps 2-4: hand size, wrist depth from the pinhole model, camera -> level head frame.

Frames used here
  camera : OpenCV style, x right, y down, z forward (into the scene), metres. MediaPipe world landmarks use it too.
  head   : level, physical, right-handed: X right, Y forward, Z up, origin at the eyes, metres.
"""
from __future__ import annotations
import math
from dataclasses import dataclass, field
import numpy as np
from . import mathutil as mu

HAND_LENGTH_PER_HEIGHT = 0.108             # wrist -> middle fingertip, straight hand
DEFAULT_HFOV_DEG = 70.0
DEFAULT_PITCH_DOWN_DEG = {"head": 45.0, "chest": 30.0}
CHEST_TO_EYES = (0.0, 0.05, -0.30)         # camera on the chest, relative to the eyes, x right / y forward / z up, at 1.70 m
DEPTH_SEGMENTS = ((0, 9), (0, 5), (0, 17), (5, 17))   # landmark pairs of the rigid palm used for the pinhole depth
CHAIN = ((0, 9), (9, 10), (10, 11), (11, 12))          # wrist -> middle tip, its summed length is invariant to finger flexion
MIN_DEPTH_M, MAX_DEPTH_M = 0.08, 1.3


@dataclass
class HandModel:
    """Per-hand size. seg_len: metric length of each DEPTH_SEGMENTS pair; chain_len: wrist -> middle tip."""
    seg_len: dict
    chain_len: float
    scale: float                   # factor applied to MediaPipe's world landmarks
    source: str                    # calibration | take | assumed
    note: str = ""

    def info(self):
        return {"source": self.source, "wrist_to_middle_mcp_m": round(self.seg_len[(0, 9)], 4),
                "hand_length_m": round(self.chain_len, 4), "world_scale": round(self.scale, 3), "note": self.note}


def _seg(world, a, b):
    return np.linalg.norm(world[:, b] - world[:, a], axis=-1)


def measure_hand_model(world, score, height_m, calibrated):
    """world (T,21,3) (NaN when absent), score (T,). Median over the frames with a high detection score. Without a
    calibration clip (`calibrated` False) or when the measurement is implausible, the world landmarks are scaled to a
    hand length of 0.108 x height."""
    ok = ~np.isnan(world[:, 0, 0])
    expect = HAND_LENGTH_PER_HEIGHT * height_m
    if ok.sum() < 3:
        return assumed_hand_model(height_m)
    sc = np.nan_to_num(score, nan=0.0)
    hi = ok & (sc >= 0.8)
    if hi.sum() < 3:
        hi = ok & (sc >= np.median(sc[ok]))
    w = world[hi]
    chain = float(np.median(sum(_seg(w, a, b) for a, b in CHAIN)))
    note = ""
    if calibrated and 0.75 * expect <= chain <= 1.3 * expect:
        scale = 1.0
    else:
        scale = expect / chain
        note = ("scaled to 0.108 x height (no calibration clip)" if not calibrated
                else f"measured hand length {chain * 100:.1f} cm is implausible; scaled to 0.108 x height")
    seg = {p: float(np.median(_seg(w, *p))) * scale for p in DEPTH_SEGMENTS}
    return HandModel(seg, chain * scale, scale, "calibration" if calibrated else "take", note)


def assumed_hand_model(height_m):
    """Fallback proportions of an adult hand (wrist->middle MCP 0.55 hand lengths, palm width 0.45)."""
    L = HAND_LENGTH_PER_HEIGHT * height_m
    return HandModel({(0, 9): 0.55 * L, (0, 5): 0.54 * L, (0, 17): 0.50 * L, (5, 17): 0.40 * L}, L, 1.0, "assumed",
                     "no usable detections: typical proportions assumed")


# --- camera ---------------------------------------------------------------------------------------------------

@dataclass
class Camera:
    width: int
    height: int
    f_px: float
    source: str      # manifest | assumed_70deg_hfov

    @classmethod
    def from_take(cls, info, width, height):
        """info = manifest take entry (may be empty). width/height: the upright frames that were analysed."""
        info = info or {}
        f = (info.get("camera") or {}).get("focal_length_px")
        long_side = max(width, height)
        if f:
            stored_long = max(info.get("width") or long_side, info.get("height") or long_side)
            return cls(width, height, float(f) * long_side / stored_long, "manifest")
        return cls(width, height, long_side / 2.0 / math.tan(math.radians(DEFAULT_HFOV_DEG / 2)), "assumed_70deg_hfov")


def sky_in_camera(g_dev, rotation_deg, mount="head"):
    """(unit sky vector in the camera frame, source). `g_dev` = median IMU gravity, device frame, pointing to the sky
    (formats.md section 1; device x right, y up in portrait, z out of the screen; the back camera looks along -z).
    `rotation_deg` = display rotation of the stored video (back camera: sensor 90 deg => portrait is 90, landscape 0 / 180):
    the image's up direction in the device is (sin p, cos p) with p = 90 - rotation. Without usable IMU data the mount
    is assumed pitched 45 deg (head) / 30 deg (chest) downward with no roll."""
    if g_dev is not None and np.linalg.norm(g_dev) > 1.0:
        g = np.asarray(g_dev, float) / np.linalg.norm(g_dev)
        p = math.radians(90 - (90 if rotation_deg is None else rotation_deg))
        up = np.array([math.sin(p), math.cos(p)])
        right = np.array([math.cos(p), -math.sin(p)])
        return np.array([g[:2] @ right, -(g[:2] @ up), -g[2]]), "imu"
    d = math.radians(DEFAULT_PITCH_DOWN_DEG.get(mount, 45.0))
    return np.array([0.0, -math.cos(d), -math.sin(d)]), "assumed"


def head_rotation(sky_cam):
    """Rows X (right), Y (forward), Z (up) of the level head frame in camera coordinates: p_head = R @ p_cam. Roll is
    removed because X is horizontal by construction."""
    Z = mu.normalize(sky_cam)
    f = np.array([0.0, 0.0, 1.0])
    Y = f - (f @ Z) * Z
    if np.linalg.norm(Y) < 0.2:                       # looking (almost) straight up/down: use the top of the image
        u = np.array([0.0, -1.0, 0.0])
        Y = u - (u @ Z) * Z
    Y = mu.normalize(Y)
    X = np.cross(Y, Z)
    return np.stack([X, Y, Z])


def mount_angles(sky_cam):
    """(pitch_deg, roll_deg): elevation of the camera axis above the horizon (negative = looking down) and roll."""
    s = mu.normalize(sky_cam)
    return math.degrees(math.asin(float(np.clip(s[2], -1, 1)))), math.degrees(math.atan2(s[0], -s[1]))


def mount_offset(mount, height_m):
    """Shift from camera-centred head coordinates to eye-centred ones (zero for the head mount)."""
    if mount != "chest":
        return np.zeros(3)
    return np.array(CHEST_TO_EYES) * (height_m / 1.70)


# --- wrist position ---------------------------------------------------------------------------------------------

def segment_depth(img_a, img_b, d_world, f):
    """Depth Z of point A from a rigid segment A->B of known 3D offset `d_world` (camera orientation, true length).
    img_a / img_b: pixel coordinates relative to the principal point, (..,2). Exact pinhole algebra:
    pix(B) - pix(A) = f (d_xy - a dz) / (Z + dz) with a = pix(A) / f, so Z + dz = f |d_xy - a dz| / |pix(B) - pix(A)|.
    Returns (Z, in-plane length |d_xy - a dz|, pixel length)."""
    a = img_a / f
    dxy = d_world[..., :2] - a * d_world[..., 2:3]
    num = np.linalg.norm(dxy, axis=-1)
    den = np.linalg.norm(img_b - img_a, axis=-1)
    with np.errstate(divide="ignore", invalid="ignore"):
        z = f * num / den - d_world[..., 2]
    return z, num, den


def wrist_from_pinhole(img, world, model: HandModel, cam: Camera):
    """img (T,21,3) normalised image landmarks, world (T,21,3) MediaPipe world landmarks (any scale). Returns
    (wrist (T,3) in camera coordinates, depth-quality (T,)) with NaN where the depth is not reliable (hand edge-on)."""
    T = len(img)
    pix = np.stack([(img[..., 0] - 0.5) * cam.width, (img[..., 1] - 0.5) * cam.height], -1)   # relative to the image centre
    zs, ws = [], []
    for (a, b) in DEPTH_SEGMENTS:
        d = world[:, b] - world[:, a]
        d = mu.normalize(d) * model.seg_len[(a, b)]
        z, num, den = segment_depth(pix[:, a], pix[:, b], d, cam.f_px)
        if a != 0:                                    # depth of landmark `a` -> depth of the wrist
            z = z - (mu.normalize(world[:, a] - world[:, 0]) * model.seg_len[(0, a)])[:, 2]
        good = (num > 0.35 * model.seg_len[(a, b)]) & (den > 2.0) & np.isfinite(z)
        zs.append(np.where(good, z, np.nan)); ws.append(np.where(good, den, 0.0))
    Z = np.stack(zs, 1); W = np.stack(ws, 1)
    wsum = W.sum(1)
    with np.errstate(invalid="ignore", divide="ignore"):
        z = np.nansum(np.where(np.isnan(Z), 0, Z) * W, 1) / wsum
    z = np.where((wsum > 0) & (z > MIN_DEPTH_M) & (z < MAX_DEPTH_M), z, np.nan)
    wr = np.stack([pix[:, 0, 0] / cam.f_px * z, pix[:, 0, 1] / cam.f_px * z, z], -1)
    return wr, np.where(np.isnan(z), 0.0, wsum)


# --- hand series in the head frame -----------------------------------------------------------------------------

@dataclass
class HandSeries:
    """One hand over time in the level head frame (metres). `rel` = landmarks relative to the wrist."""
    wrist: np.ndarray          # (T,3)
    rel: np.ndarray            # (T,21,3)
    valid: np.ndarray          # (T,) bool
    score: np.ndarray          # (T,)


def hand_series(track, slot, model: HandModel, cam: Camera, R_head, offset):
    """HandSeries for hand `slot` of a HandTrack at the track's frame rate."""
    T = track.T
    present = track.present[:, slot]
    wrist = np.full((T, 3), np.nan)
    rel = np.full((T, 21, 3), np.nan)
    valid = np.zeros(T, bool)
    if present.any():
        world = track.world[present, slot] * model.scale
        wr_cam, _ = wrist_from_pinhole(track.img[present, slot], world, model, cam)
        ok = ~np.isnan(wr_cam[:, 2])
        idx = np.flatnonzero(present)[ok]
        wrist[idx] = wr_cam[ok] @ R_head.T + offset
        rel[idx] = (world[ok] - world[ok][:, :1]) @ R_head.T
        valid[idx] = True
    return HandSeries(wrist, rel, valid, track.score[:, slot].copy())
