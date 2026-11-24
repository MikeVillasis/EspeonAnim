"""POV step 5: idle trim, gap filling, One-Euro smoothing and resampling to 60 fps."""
from __future__ import annotations
from dataclasses import dataclass
import numpy as np
from .pov_geom import HandSeries

OUT_FPS = 60
MAX_FILL_S = 0.5          # gaps up to this are interpolated
EASE_S = 0.3              # longer gaps: ease to the rest pose (and back) over this time
PAD_S = 0.25              # idle trim keeps this much before the first / after the last visible hand
WRIST_FILTER = dict(min_cutoff=2.0, beta=10.0, d_cutoff=2.0)     # metres, tuned for 60 fps output
FINGER_FILTER = dict(min_cutoff=4.0, beta=20.0, d_cutoff=2.0)


@dataclass
class HandClean:
    wrist: np.ndarray      # (F,3) 60 fps, level head frame, metres
    rel: np.ndarray        # (F,21,3)
    seen: np.ndarray       # (F,) bool: a real detection backs this frame
    resting: np.ndarray    # (F,) bool: the frame is (partly) the rest pose of a long gap


def one_euro(x, fps, min_cutoff, beta, d_cutoff=1.0):
    """Causal One-Euro filter along axis 0 (Casiez et al. 2012). Speed = norm over the last axis (xyz)."""
    x = np.asarray(x, np.float64)
    dt = 1.0 / fps
    a = lambda fc: 1.0 / (1.0 + 1.0 / (2 * np.pi * fc) / dt)
    out = np.empty_like(x)
    out[0] = x[0]
    dxh = np.zeros_like(x[0])
    ad = a(d_cutoff)
    for i in range(1, len(x)):
        dxh = ad * (x[i] - x[i - 1]) / dt + (1 - ad) * dxh
        speed = np.linalg.norm(dxh, axis=-1, keepdims=True)
        al = a(min_cutoff + beta * speed)
        out[i] = al * x[i] + (1 - al) * out[i - 1]
    return out


def one_euro_zero_phase(x, fps, **kw):
    """Forward and backward One-Euro passes averaged, so the smoothing adds no lag."""
    x = np.asarray(x, np.float64)
    if len(x) < 2:
        return x.copy()
    fwd = one_euro(x, fps, **kw)
    bwd = one_euro(x[::-1], fps, **kw)[::-1]
    return 0.5 * (fwd + bwd)


def active_window(valid_any, fps, pad_s=PAD_S):
    """(start_s, end_s) around the frames where any hand is visible, or None when no hand is ever seen."""
    idx = np.flatnonzero(valid_any)
    if len(idx) == 0:
        return None
    return max(0.0, idx[0] / fps - pad_s), min(len(valid_any) / fps, (idx[-1] + 1) / fps + pad_s)


def _smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


def fill_and_resample(hs: HandSeries, src_fps, t0, t1, rest_wrist, rest_rel, out_fps=OUT_FPS, smooth=True) -> HandClean:
    """Resample `hs` (frames at src_fps) onto the 60 fps grid [t0, t1). Gaps <= 0.5 s are interpolated; longer gaps (and
    the stretches before the first / after the last detection) ease to the rest pose over 0.3 s."""
    F = max(2, int(round((t1 - t0) * out_fps)))
    t = t0 + np.arange(F) / out_fps
    ts = np.arange(len(hs.valid)) / src_fps
    vi = np.flatnonzero(hs.valid)
    rest_wrist = np.asarray(rest_wrist, float); rest_rel = np.asarray(rest_rel, float)
    if len(vi) == 0:
        w = np.tile(rest_wrist, (F, 1)); r = np.tile(rest_rel, (F, 1, 1))
        return HandClean(w, r, np.zeros(F, bool), np.ones(F, bool))
    flat = hs.rel[vi].reshape(len(vi), -1)
    wrist = np.stack([np.interp(t, ts[vi], hs.wrist[vi, k]) for k in range(3)], 1)
    rel = np.stack([np.interp(t, ts[vi], flat[:, k]) for k in range(flat.shape[1])], 1).reshape(F, 21, 3)
    near = np.clip(np.rint(t * src_fps).astype(int), 0, len(hs.valid) - 1)
    seen = hs.valid[near] & (t >= 0) & (t <= ts[-1] + 0.5 / src_fps)
    resting = np.zeros(F, bool)
    i = 0
    while i < F:
        if seen[i]:
            i += 1
            continue
        j = i
        while j < F and not seen[j]:
            j += 1
        lead, trail = i == 0, j == F
        if (j - i) / out_fps > MAX_FILL_S or lead or trail:
            n = np.arange(i, j)
            w_out = np.ones(len(n)) if lead else _smoothstep((n - i + 1) / (EASE_S * out_fps))
            w_in = np.ones(len(n)) if trail else _smoothstep((j - n) / (EASE_S * out_fps))
            w = np.minimum(w_out, w_in)
            wrist[i:j] = wrist[i:j] * (1 - w[:, None]) + rest_wrist * w[:, None]
            rel[i:j] = rel[i:j] * (1 - w[:, None, None]) + rest_rel * w[:, None, None]
            resting[i:j] = w > 0.5
        i = j
    if smooth:
        wrist = one_euro_zero_phase(wrist, out_fps, **WRIST_FILTER)
        rel = one_euro_zero_phase(rel, out_fps, **FINGER_FILTER)
    return HandClean(wrist, rel, seen, resting)
