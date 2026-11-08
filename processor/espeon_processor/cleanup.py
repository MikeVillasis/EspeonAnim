"""Cleanup in UE space: zero-phase smoothing, foot locking with two-bone IK, 30 -> 60 fps resampling."""
from __future__ import annotations
import numpy as np
from scipy.interpolate import PchipInterpolator
from scipy.signal import butter, filtfilt
from . import mathutil as mu
from .skeleton import Rig
from .analysis import runs

# Sole-height references of the Manny reference pose (cm): ankle joint and ball joint above the floor.
ANKLE_H = 8.24
BALL_H = 0.75


def lowpass(x, fps, cutoff_hz, order=2, axis=0):
    """Zero-phase Butterworth low-pass; short signals are returned unchanged."""
    x = np.asarray(x, np.float64)
    if x.shape[axis] < 12 or cutoff_hz >= fps / 2 * 0.98:
        return x
    b, a = butter(order, cutoff_hz / (fps / 2))
    return filtfilt(b, a, x, axis=axis)


def smooth_quats(q, fps, cutoff_hz=9.0):
    """Sign-continuous, zero-phase filtered, renormalised quaternions (F,B,4)."""
    q = mu.make_continuous(q, 0)
    return mu.qnorm(lowpass(q, fps, cutoff_hz, axis=0))


def smooth_translation(t, fps, cutoff_hz=7.0):
    return lowpass(t, fps, cutoff_hz, axis=0)


# --------------------------------------------------------------------------------------------
# Foot locking

def _ramp(n, blend):
    w = np.ones(n)
    k = min(blend, n // 2)
    for i in range(k):
        v = (i + 1) / (k + 1)
        v = v * v * (3 - 2 * v)
        w[i] = min(w[i], v)
        w[n - 1 - i] = min(w[n - 1 - i], v)
    return w


def contact_phases(on, min_len=3):
    return [(s, e) for s, e in runs(on) if e - s >= min_len]


def foot_slide_cm(wt, rig: Rig, on, trim=4):
    """Mean over contact phases of the horizontal extent (cm) travelled by the ankle and ball joints,
    ignoring `trim` frames at each end of a phase (where the lock is blended in/out)."""
    res = []
    for side, k in (("l", 0), ("r", 1)):
        for s, e in contact_phases(on[:, k]):
            for bone in (f"foot_{side}", f"ball_{side}"):
                p = wt[s + trim:e - trim, rig.idx[bone], :2] if e - s > 2 * trim + 2 else wt[s:e, rig.idx[bone], :2]
                res.append(np.linalg.norm(p.max(0) - p.min(0)))
    return float(np.mean(res)) if res else 0.0


def lock_feet(rig: Rig, local_q, root_t, pelvis_t, on, blend=4):
    """Pin ankle/ball world positions to their contact-phase means and solve thigh/calf with two-bone IK.

    The knee pole direction comes from the original pose; blending in/out over `blend` frames.
    Returns new local rotations (F,B,4). on: (F,2) bool [left, right].
    """
    wq, wt = rig.fk(local_q, root_t, pelvis_t)
    WQ = wq.copy()
    R = mu.q_to_mat(wq)                       # (F,B,3,3)
    Rn = R.copy()
    F = len(wq)
    for side, k in (("l", 0), ("r", 1)):
        ith, ica, ifo, iba = (rig.idx[f"{n}_{side}"] for n in ("thigh", "calf", "foot", "ball"))
        for s, e in contact_phases(on[:, k]):
            pinA = wt[s:e, ifo].mean(0)
            pinB = wt[s:e, iba].mean(0)
            # pin the sole to the floor: shift the pins so the lowest sole point averages 0 over the phase
            hl = np.minimum(wt[s:e, ifo, 2] - ANKLE_H, wt[s:e, iba, 2] - BALL_H).mean()
            hl = float(np.clip(hl, -5.0, 5.0))
            pinA[2] -= hl
            pinB[2] -= hl
            w = _ramp(e - s, blend)
            for f in range(s, e):
                wf = w[f - s]
                H, K, A, Bl = wt[f, ith], wt[f, ica], wt[f, ifo], wt[f, iba]
                At = A + wf * (pinA - A)
                L1, L2 = np.linalg.norm(K - H), np.linalg.norm(A - K)
                dv = At - H
                d = np.linalg.norm(dv)
                dc = float(np.clip(d, abs(L1 - L2) + 1e-3, L1 + L2 - 1e-3))
                u = dv / max(d, 1e-9)
                a = (L1 * L1 - L2 * L2 + dc * dc) / (2 * dc)
                h = np.sqrt(max(L1 * L1 - a * a, 0.0))
                pole = (K - H) - np.dot(K - H, u) * u
                pn = np.linalg.norm(pole)
                pole = pole / pn if pn > 1e-6 else np.cross(u, [1.0, 0, 0])
                Kn = H + u * a + pole * h
                An = H + u * dc
                S1 = mu.swing_matrix(K - H, Kn - H)
                S2 = mu.swing_matrix(A - K, An - Kn)
                Rn[f, ith] = S1 @ R[f, ith]
                Rn[f, ica] = S2 @ R[f, ica]
                # foot/ball: tilt the foot so the ball approaches its pinned position (bounded)
                v0 = Bl - A
                vt = pinB - An
                vn = mu.normalize(v0) * (1 - wf) + mu.normalize(vt) * wf
                S3 = mu.swing_matrix(v0, vn)
                if mu.angle_between(v0, vn) > 20:
                    S3 = np.eye(3)
                Rn[f, ifo] = S3 @ R[f, ifo]
                Rn[f, iba] = S3 @ R[f, iba]
    wq2 = mu.make_continuous(mu.mat_to_q(Rn), 0)
    return rig.world_to_local(wq2)


# --------------------------------------------------------------------------------------------
# Resampling

def resample(local_q, root_t, pelvis_t, src_fps=30.0, dst_fps=60.0):
    """Slerp rotations, monotone cubic Hermite (PCHIP) translations. Returns arrays on the dst grid."""
    F = len(local_q)
    t_src = np.arange(F) / src_fps
    n_out = int(round((F - 1) / src_fps * dst_fps)) + 1
    t_dst = np.arange(n_out) / dst_fps
    t_dst = np.minimum(t_dst, t_src[-1])
    i0 = np.clip(np.searchsorted(t_src, t_dst, side="right") - 1, 0, F - 2)
    u = (t_dst - t_src[i0]) * src_fps
    q = mu.make_continuous(local_q, 0)
    qo = mu.slerp(q[i0], q[i0 + 1], np.repeat(u[:, None], q.shape[1], 1))
    ro = PchipInterpolator(t_src, root_t, axis=0)(t_dst)
    po = PchipInterpolator(t_src, pelvis_t, axis=0)(t_dst)
    return mu.make_continuous(qo, 0), ro, po
