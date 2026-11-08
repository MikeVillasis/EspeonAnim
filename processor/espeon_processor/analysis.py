"""Analysis of a GEM reconstruction: contacts, jump detection, trimming window, body measurements."""
from __future__ import annotations
from dataclasses import dataclass, field
from pathlib import Path
import numpy as np
from scipy.ndimage import uniform_filter1d
from scipy.signal import savgol_filter


@dataclass
class Gem:
    """GEM-X reconstruction in SOMA space (metres, Y up, right-handed, 30 fps)."""
    fps: float
    names: list
    parents: np.ndarray
    J: np.ndarray            # (T,77,3) world joint positions
    Rw: np.ndarray           # (T,77,3,3) world rotations
    contact: np.ndarray      # (T,6) l_ankle,l_foot,r_ankle,r_foot,l_wrist,r_wrist
    rest_pos: np.ndarray     # (77,3) zero-pose joints, Hips at origin
    rest_rot: np.ndarray     # (77,3,3) zero-pose world rotations

    @property
    def T(self):
        return len(self.J)

    def idx(self, name):
        return self.names.index(name)

    @classmethod
    def load(cls, path):
        d = np.load(path)
        return cls(float(d["fps"]), [str(n) for n in d["joint_names"]], d["parents"].astype(int),
                   d["joint_positions_world"].astype(np.float64), d["world_rotations_matrix"].astype(np.float64),
                   d["contact_prob"].astype(np.float64), d["rest_joint_positions"].astype(np.float64),
                   d["rest_world_rotations"].astype(np.float64))

    def slice(self, a, b):
        """Frames [a, b) as a new Gem."""
        return Gem(self.fps, self.names, self.parents, self.J[a:b], self.Rw[a:b], self.contact[a:b],
                   self.rest_pos, self.rest_rot)


def hysteresis(x, on=0.6, off=0.4):
    state = bool(x[0] > 0.5)
    out = np.zeros(len(x), bool)
    for i, v in enumerate(x):
        if state and v < off:
            state = False
        elif not state and v > on:
            state = True
        out[i] = state
    return out


def runs(mask):
    """[(start, end_exclusive)] of True runs."""
    m = np.concatenate([[0], np.asarray(mask, np.int8), [0]])
    d = np.diff(m)
    return list(zip(np.flatnonzero(d == 1), np.flatnonzero(d == -1)))


def foot_contacts(contact, smooth=3, on=0.6, off=0.4):
    """Per-foot (left, right) contact booleans with hysteresis, from the four foot probabilities."""
    foot = np.stack([contact[:, [0, 1]].mean(1), contact[:, [2, 3]].mean(1)], 1)
    foot = uniform_filter1d(foot, smooth, axis=0, mode="nearest")
    return np.stack([hysteresis(foot[:, k], on, off) for k in range(2)], 1), foot


def _smooth(x, fps, win_s=0.15):
    w = int(round(win_s * fps)) | 1
    if len(x) <= w:
        return x
    return savgol_filter(x, w, 2, mode="interp")


@dataclass
class JumpInfo:
    found: bool = False
    takeoff: int = -1
    landing: int = -1            # first frame with ground contact again
    crouch_onset: int = -1
    crouch_min: int = -1
    crouch_depth_m: float = 0.0
    hips_rise_m: float = 0.0
    flight_s: float = 0.0
    stand_height_m: float = 0.0
    notes: list = field(default_factory=list)


def detect_jump(g: Gem, min_flight_s=0.09, min_crouch_m=0.03) -> JumpInfo:
    fps, T = g.fps, g.T
    on, _ = foot_contacts(g.contact)
    flight = ~on.any(1)
    hips = _smooth(g.J[:, 0, 1], fps)
    best, best_score = JumpInfo(), -1.0
    for s, e in runs(flight):
        dur = (e - s) / fps
        if dur < min_flight_s or s < 2:
            continue
        lo = max(0, s - int(1.5 * fps))
        i_min = lo + int(np.argmin(hips[lo:s + 1]))
        lo2 = max(0, i_min - int(1.5 * fps))
        h_ref = hips[lo2:i_min + 1].max()
        drop = h_ref - hips[i_min]
        rise = hips[s:min(T, e + int(0.2 * fps))].max() - hips[s]
        score = 0.5 * dur + max(rise, 0) + 0.5 * max(drop, 0)
        if score <= best_score:
            continue
        info = JumpInfo(True, s, e, 0, i_min, float(drop), float(rise), float(dur), float(h_ref))
        if drop >= min_crouch_m:
            thr = h_ref - 0.1 * drop
            idx = np.flatnonzero(hips[lo2:i_min + 1] >= thr)
            info.crouch_onset = int(lo2 + idx[-1]) if len(idx) else lo2
        else:
            info.crouch_onset = max(0, s - int(0.6 * fps))
            info.notes.append("no preparatory crouch found; using takeoff - 0.6 s")
        best, best_score = info, score
    return best


def recovery_frame(g: Gem, jump: JumpInfo, max_s=3.0, hold_s=0.1, v_thr=0.3):
    """First frame after landing where the hips are back at standing height and vertically still, feet down."""
    fps = g.fps
    on, _ = foot_contacts(g.contact)
    hips = _smooth(g.J[:, 0, 1], fps)
    vy = np.gradient(hips) * fps
    a = jump.landing
    i_min = a + int(np.argmin(hips[a:min(g.T, a + int(1.0 * fps))]))
    hold = max(1, int(hold_s * fps))
    ok = on.all(1) & (hips >= jump.stand_height_m - 0.05) & (np.abs(vy) < v_thr)
    for i in range(i_min, min(g.T - hold + 1, a + int(max_s * fps))):
        if ok[i:i + hold].all():
            return int(i)
    return None


def walk_onset(g: Gem, start, speed=0.45, hold_s=0.2):
    """First frame >= start where the hips travel horizontally faster than `speed` for hold_s, or None."""
    fps = g.fps
    sm = np.stack([_smooth(g.J[:, 0, k], fps) for k in (0, 2)], 1)
    v = np.linalg.norm(np.gradient(sm, axis=0), axis=1) * fps
    hold = max(1, int(hold_s * fps))
    for i in range(start, g.T - hold + 1):
        if (v[i:i + hold] > speed).all():
            return int(i)
    return None


def action_window(g: Gem, jump: JumpInfo, pad_s=0.5):
    """(start, end_exclusive, details) for the jump action, or None when no jump was found.

    start = crouch onset - pad; end = stable standing after landing + pad, cut short where the
    performer starts walking away.
    """
    if not jump.found:
        return None
    fps = g.fps
    pad = int(round(pad_s * fps))
    details = {}
    rec = recovery_frame(g, jump)
    if rec is None:
        rec = min(g.T - 1, jump.landing + int(1.0 * fps))
        details["recovery"] = "stable standing height not found after landing; used landing + 1 s"
    end = rec + pad
    w = walk_onset(g, rec)
    if w is not None and w < end:
        end = max(rec + int(0.2 * fps), w)
        details["end_cut_by_walk_out"] = True
    a = max(0, jump.crouch_onset - pad)
    b = min(g.T, end + 1)
    details["recovery_frame"] = int(rec)
    return int(a), int(b), details


def facing_xz(g: Gem):
    """Per-frame facing direction (x,z) in SOMA world: cross(left, up), left from hips + shoulders."""
    L = (g.J[:, g.idx("LeftLeg")] - g.J[:, g.idx("RightLeg")]) + (g.J[:, g.idx("LeftShoulder")] - g.J[:, g.idx("RightShoulder")])
    f = np.stack([-L[:, 2], L[:, 0]], 1)
    n = np.linalg.norm(f, axis=1, keepdims=True)
    return f / np.maximum(n, 1e-9)


# ---------------------------------------------------------------------------------------------
# Body measurements

def _chain(J, names, a, b):
    return np.linalg.norm(J[:, names.index(a)] - J[:, names.index(b)], axis=1)


def measure_body(g: Gem, still_fraction=0.5):
    """Leg length, height and arm span (m) as medians over the stillest frames."""
    fps, J, n = g.fps, g.J, g.names
    sel = [n.index(k) for k in ("Hips", "Head", "LeftHand", "RightHand", "LeftFoot", "RightFoot")]
    sp = np.linalg.norm(np.gradient(J[:, sel], axis=0), axis=2).mean(1) * fps
    sp = uniform_filter1d(sp, max(3, int(0.5 * fps)), mode="nearest")
    k = max(10, int(len(sp) * still_fraction))
    still = np.sort(np.argsort(sp)[:k])
    leg = 0.5 * sum(_chain(J, n, f"{s}Leg", f"{s}Shin") + _chain(J, n, f"{s}Shin", f"{s}Foot") for s in ("Left", "Right"))
    feet = [n.index(f"{s}{p}") for s in ("Left", "Right") for p in ("Foot", "ToeBase", "ToeEnd")]
    height = J[:, n.index("HeadEnd"), 1] - J[:, feet, 1].min(1)
    arm = [sum(_chain(J, n, a, b) for a, b in ((f"{s}Arm", f"{s}ForeArm"), (f"{s}ForeArm", f"{s}Hand"), (f"{s}Hand", f"{s}HandMiddle1")))
           for s in ("Left", "Right")]
    span = _chain(J, n, "LeftArm", "RightArm") + arm[0] + arm[1]
    med = lambda x: float(np.median(x[still]))
    return {"leg_length_m": med(leg), "height_m": med(height), "arm_span_m": med(span), "still_frames": int(len(still))}
