"""Turn a GEM reconstruction window into a Manny motion: retarget, ground, root motion, cleanup, 60 fps."""
from __future__ import annotations
from dataclasses import dataclass, field
import numpy as np
from scipy.ndimage import median_filter, uniform_filter1d
from . import mathutil as mu
from .analysis import Gem, facing_xz, foot_contacts, hysteresis
from .cleanup import (ANKLE_H, BALL_H, foot_slide_cm, lock_feet, lowpass, resample, smooth_quats,
                      smooth_translation)
from .retarget import Retargeter, pos_to_ue, rot_to_ue
from .skeleton import Rig


@dataclass
class Solved:
    rig: Rig
    fps: float
    local_q: np.ndarray            # (F,B,4) xyzw
    root_t: np.ndarray             # (F,3) cm
    pelvis_t: np.ndarray           # (F,3) cm, local to root
    on60: np.ndarray               # (F,2) foot contact booleans on the output grid
    stats: dict = field(default_factory=dict)
    debug: dict = field(default_factory=dict)


def manny_leg_length(rig: Rig):
    p = rig.profile
    t, c, f = (p.ref_comp_t[p.index[n]] for n in ("thigh_l", "calf_l", "foot_l"))
    return float(np.linalg.norm(c - t) + np.linalg.norm(f - c))


def foot_low_height(wt, rig: Rig):
    """Estimated height of the lowest sole point (cm) per frame for [left, right] feet."""
    out = []
    for s in ("l", "r"):
        out.append(np.minimum(wt[:, rig.idx[f"foot_{s}"], 2] - ANKLE_H, wt[:, rig.idx[f"ball_{s}"], 2] - BALL_H))
    return np.stack(out, 1)


def floor_curve(h, prob, fps):
    """Per-frame floor offset (cm): per-frame sole height of confidently planted feet, median filtered,
    interpolated across flight. Returns (curve, n_samples)."""
    planted = prob > 0.8
    anyp = planted.any(1)
    if not anyp.any():
        return np.zeros(len(h)), 0
    val = np.where(planted, h, np.nan)
    v = np.nanmean(np.where(anyp[:, None], val, 0), axis=1)
    idx = np.flatnonzero(anyp)
    s = median_filter(v[idx], size=min(len(idx), max(3, int(0.3 * fps)) | 1), mode="nearest")
    curve = np.interp(np.arange(len(h)), idx, s)
    return uniform_filter1d(curve, 5, mode="nearest"), int(len(idx))


def solve(gem: Gem, window, rig: Rig, scale: float, out_fps=60.0, rt: Retargeter | None = None,
          smooth=True, lock=True) -> Solved:
    a, b = window
    g = gem.slice(a, b)
    fps, F = g.fps, g.T
    stats, debug = {}, {}

    # --- facing / origin ---------------------------------------------------------------
    f = facing_xz(g)
    n0 = max(2, int(0.5 * fps))
    phi = np.arctan2(f[:n0, 1].mean(), f[:n0, 0].mean())          # (x_ue, y_ue) = (x_soma, z_soma)
    theta = np.pi / 2 - phi
    Rz = mu.rotz(theta)
    P = pos_to_ue(g.J) * 100.0 @ Rz.T
    Rs = Rz @ rot_to_ue(g.Rw)
    origin = P[:n0, 0, :2].mean(0)
    stats["yaw_applied_deg"] = float(np.degrees(theta))

    if rt is None:
        rt = Retargeter(rig, g.names, pos_to_ue(g.rest_pos), rot_to_ue(g.rest_rot))
    lq_world = rt.world_rotations(Rs)
    lq = rig.world_to_local(lq_world)

    # --- pelvis placement: Manny hip-joint midpoint follows the performer's hip-joint midpoint ----
    hip = 0.5 * (P[:, g.idx("LeftLeg")] + P[:, g.idx("RightLeg")])
    hip = np.concatenate([(hip[:, :2] - origin) * scale, hip[:, 2:] * scale], 1)
    pel_w = hip - mu.qrot(lq_world[:, rig.idx["pelvis"]], rt.hip_anchor_local())

    # --- cleanup: smoothing --------------------------------------------------------------
    if smooth:
        lq = smooth_quats(lq, fps)
        pel_w = smooth_translation(pel_w, fps)
    else:
        lq = mu.make_continuous(lq, 0)
    lq[:, 0] = [0, 0, 0, 1]

    # --- contacts ---------------------------------------------------------------------------
    on, prob = foot_contacts(g.contact)
    flight = ~on.any(1)

    # --- ground: floor curve from planted feet of the retargeted pose --------------------
    z = np.zeros((F, 3))
    wq, wt = rig.fk(lq, np.zeros((F, 3)), pel_w)
    h = foot_low_height(wt, rig)
    floor, nfl = floor_curve(h, prob, fps)
    pel_w[:, 2] -= floor
    h = h - floor[:, None]
    stats["floor_offset_cm_median"] = float(np.median(floor))
    stats["floor_drift_cm"] = float(np.ptp(floor))
    stats["floor_samples"] = nfl

    # --- root motion --------------------------------------------------------------------------
    wflight = uniform_filter1d(flight.astype(float), 3, mode="nearest")
    clearance = np.clip(h.min(1), 0, None)
    root_t = np.zeros((F, 3))
    root_t[:, :2] = pel_w[:, :2]
    root_t[:, 2] = wflight * clearance
    pelvis_t = pel_w - root_t

    # --- foot locking -------------------------------------------------------------------------
    wq0, wt0 = rig.fk(lq, root_t, pelvis_t)
    stats["foot_slide_cm_before"] = foot_slide_cm(wt0, rig, on)
    lq_l = lock_feet(rig, lq, root_t, pelvis_t, on) if lock else lq
    wq1, wt1 = rig.fk(lq_l, root_t, pelvis_t)
    stats["foot_slide_cm_after"] = foot_slide_cm(wt1, rig, on)
    h_after = foot_low_height(wt1, rig)
    stats["foot_floor_error_cm"] = float(np.abs(h_after[on]).mean()) if on.any() else 0.0

    debug.update(dict(floor=floor, flight30=flight, on30=on, prob30=prob, pelvis_world30=pel_w.copy(),
                      clearance30=clearance, h_low30=h_after, soma_ue30=P, theta=theta, origin=origin,
                      lq30=lq_l, root30=root_t, pel30=pelvis_t))

    # --- resample to 60 fps ------------------------------------------------------------------------
    q60, r60, p60 = resample(lq_l, root_t, pelvis_t, fps, out_fps)
    r60[:, 2] = np.clip(r60[:, 2], 0, None)
    n60 = len(q60)
    src = np.minimum(np.round(np.arange(n60) * fps / out_fps).astype(int), F - 1)
    return Solved(rig, out_fps, q60, r60, p60, on[src], stats, debug)
