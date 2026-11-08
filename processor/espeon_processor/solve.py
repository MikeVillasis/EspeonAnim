"""Turn a GEM reconstruction window into a Manny motion: retarget, ground, root motion, cleanup, 60 fps."""
from __future__ import annotations
from dataclasses import dataclass, field
import numpy as np
from scipy.ndimage import gaussian_filter1d, median_filter, uniform_filter1d
from . import mathutil as mu
from .analysis import Gem, facing_xz, foot_contacts, hysteresis, runs
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


G_CM = 980.0


def _cross(m, i0, i1):
    """Sub-frame time where m crosses 0.5 between frames i0 and i1 (falls back to i1)."""
    a, b = m[i0], m[i1]
    if (a - 0.5) * (b - 0.5) < 0 and a != b:
        return i0 + (0.5 - a) / (b - a) * (i1 - i0)
    return float(i1)


def ballistic_correct(pel_z, prob_max, flight, fps, g=G_CM, min_flight_s=0.15, max_air_s=1.0, max_apex_cm=80.0, blend_sigma=2.0):
    """Replace the vertical pelvis path in each flight phase by a parabola of the measured airtime.

    pel_z (F,) cm; prob_max (F,) max foot contact probability; flight (F,) bool (both feet off).
    Takeoff/landing are refined to sub-frame times from the 0.5 crossing of prob_max; the parabola passes through
    the data's heights at those times; the correction is Gaussian-blended so vertical velocity stays continuous.
    Returns (delta (F,) to add to pel_z, [info dicts]).
    """
    F = len(pel_z)
    delta = np.zeros(F)
    infos = []
    for s, e in runs(flight):
        if s < 1 or e >= F:
            continue
        t0, t1 = _cross(prob_max, s - 1, s), _cross(prob_max, e - 1, e)
        T = (t1 - t0) / fps
        if T < min_flight_s or T > max_air_s:
            continue
        z0, z1 = np.interp([t0, t1], np.arange(F), pel_z)
        f = np.arange(int(np.ceil(t0)), int(np.floor(t1)) + 1)
        u = (f - t0) / (t1 - t0)
        apex = min(g * T * T / 8.0, max_apex_cm)
        target = z0 + (z1 - z0) * u + 4 * apex * u * (1 - u)
        d = np.zeros(F)
        d[f] = target - pel_z[f]
        orig = float((pel_z[f] - (z0 + (z1 - z0) * u)).max())
        delta += d
        infos.append({"takeoff_s": t0 / fps, "landing_s": t1 / fps, "airtime_s": T, "apex_original_cm": orig,
                      "apex_ballistic_cm": apex, "capped": bool(g * T * T / 8.0 > max_apex_cm)})
    if infos and blend_sigma > 0:
        delta = gaussian_filter1d(delta, blend_sigma, mode="constant")
        ratios = []                      # the blur shaves the apex: rescale so the final apex matches the parabola
        for i in infos:
            f0, f1 = int(np.ceil(i["takeoff_s"] * fps)), int(np.floor(i["landing_s"] * fps))
            z = pel_z[f0:f1 + 1] + delta[f0:f1 + 1]
            got = (z - np.linspace(z[0], z[-1], len(z))).max()
            ratios.append(i["apex_ballistic_cm"] / got if got > 1 else 1.0)
        delta = delta * float(np.clip(np.mean(ratios), 1.0, 1.4))
    return delta, infos


def support_center(ankle_xy, on, sigma=2.0, min_len=3):
    """Root XY from the planted feet instead of the hips (which sway when crouching).

    ankle_xy (F,2feet,2) world ankle xy in cm, on (F,2) contact booleans [left, right].
    Contact: midpoint of the planted feet's phase-mean positions; a single planted foot is offset by half the
    stance vector (taken from the nearest two-foot frame). Flight: linear in time between the takeoff and landing
    centres. A Gaussian (sigma frames, ~6 frame blend) removes velocity steps at phase changes.
    """
    F = len(on)
    pin = np.full((F, 2, 2), np.nan)
    for k in range(2):
        for s, e in runs(on[:, k]):
            if e - s >= min_len:
                pin[s:e, k] = ankle_xy[s:e, k].mean(0)
    both = ~np.isnan(pin).any(2).any(1)
    stance = np.where(both[:, None], pin[:, 1] - pin[:, 0], np.nan)          # left -> right
    c = np.full((F, 2), np.nan)
    c[both] = pin[both].mean(1)
    gb = np.flatnonzero(both)
    for k in range(2):
        only = ~np.isnan(pin[:, k]).any(1) & ~both
        for f in np.flatnonzero(only):
            if len(gb):
                v = stance[gb[np.argmin(np.abs(gb - f))]]
                c[f] = pin[f, k] + 0.5 * (v if k == 0 else -v)
            else:
                c[f] = pin[f, k]
    ok = ~np.isnan(c[:, 0])
    if not ok.any():
        c[:] = ankle_xy.mean(1)
    else:
        idx = np.arange(F)
        c = np.stack([np.interp(idx, idx[ok], c[ok, a]) for a in range(2)], 1)
    return gaussian_filter1d(c, sigma, axis=0, mode="nearest")


def solve(gem: Gem, window, rig: Rig, scale: float, out_fps=60.0, rt: Retargeter | None = None,
          smooth=True, lock=True, ballistic=True) -> Solved:
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

    # --- ballistic flight correction (GEM underestimates jump height) ---------------------------
    stats["ballistic"] = []
    if ballistic and flight.any():
        delta, infos = ballistic_correct(pel_w[:, 2], prob.max(1), flight, fps)
        pel_w[:, 2] += delta
        h = h + delta[:, None]
        for i in infos:
            f0, f1 = int(np.ceil(i["takeoff_s"] * fps)), int(np.floor(i["landing_s"] * fps))
            i["apex_final_cm"] = float((pel_w[f0:f1 + 1, 2] - np.linspace(pel_w[f0, 2], pel_w[f1, 2], f1 - f0 + 1)).max())
            stats["ballistic"].append({k: (round(v, 3) if isinstance(v, float) else v) for k, v in i.items()})

    # --- root motion --------------------------------------------------------------------------
    wflight = uniform_filter1d(flight.astype(float), 3, mode="nearest")
    clearance = np.clip(h.min(1), 0, None)
    root_t = np.zeros((F, 3))
    ank = np.stack([wt[:, rig.idx['foot_l'], :2], wt[:, rig.idx['foot_r'], :2]], 1)
    cxy = support_center(ank, on)
    pel_w[:, :2] -= cxy[0]                      # root starts at the origin
    root_t[:, :2] = cxy - cxy[0]
    stats['root_travel_cm'] = [round(float(v), 2) for v in root_t[-1, :2]]
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

    debug.update(dict(window_start_s=a / fps, floor=floor, flight30=flight, on30=on, prob30=prob, pelvis_world30=pel_w.copy(),
                      clearance30=clearance, h_low30=h_after, soma_ue30=P, theta=theta, origin=origin,
                      lq30=lq_l, root30=root_t, pel30=pelvis_t))

    # --- resample to 60 fps ------------------------------------------------------------------------
    q60, r60, p60 = resample(lq_l, root_t, pelvis_t, fps, out_fps)
    r60[:, 2] = np.clip(r60[:, 2], 0, None)
    n60 = len(q60)
    src = np.minimum(np.round(np.arange(n60) * fps / out_fps).astype(int), F - 1)
    return Solved(rig, out_fps, q60, r60, p60, on[src], stats, debug)
