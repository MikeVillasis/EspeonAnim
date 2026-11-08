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


MIN_FLIGHT_S = 0.25          # both feet must be off the ground at least this long to count as a jump
MIN_CLEARANCE_M = 0.05       # ... and the hips must rise at least this much (GEM underestimates, so this is small)


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
    stable_landing: bool = True  # standing height and stillness recovered after landing (before the next jump)
    flickers: int = 0            # foot-contact changes in the second after landing
    score: float = 0.0


def detect_jumps(g: Gem, min_flight_s=MIN_FLIGHT_S, min_clearance_m=MIN_CLEARANCE_M, min_crouch_m=0.03):
    """All jumps in a take, in time order. A jump is a run of frames with both feet off the ground that lasts at
    least `min_flight_s` and lifts the hips by at least `min_clearance_m` (shorter / lower "flights" are tracking
    noise, e.g. a foot flicker during a crouch). Each jump carries its crouch onset, bounded by the previous jump's
    landing, and a quality score used by `best_jump`: airtime, minus a penalty for an unstable landing."""
    fps, T = g.fps, g.T
    on, _ = foot_contacts(g.contact)
    flight = ~on.any(1)
    hips = _smooth(g.J[:, 0, 1], fps)
    out = []
    bound = 0                    # earliest frame a crouch may start: after the previous landing has been absorbed
    for s, e in runs(flight):
        dur = (e - s) / fps
        rise = hips[s:min(T, e + int(0.2 * fps))].max() - hips[s]
        if dur < min_flight_s or s < 2 or rise < min_clearance_m:
            continue
        lo = max(bound, s - int(1.5 * fps))
        i_min = lo + int(np.argmin(hips[lo:s + 1]))
        lo2 = max(bound, i_min - int(1.5 * fps))
        h_ref = hips[lo2:i_min + 1].max()
        drop = h_ref - hips[i_min]
        info = JumpInfo(True, int(s), int(e), 0, int(i_min), float(drop), float(rise), float(dur), float(h_ref))
        if drop >= min_crouch_m:
            thr = h_ref - 0.1 * drop
            idx = np.flatnonzero(hips[lo2:i_min + 1] >= thr)
            info.crouch_onset = int(lo2 + idx[-1]) if len(idx) else int(lo2)
        else:
            info.crouch_onset = int(max(bound, s - int(0.6 * fps)))
            info.notes.append("no preparatory crouch found; using takeoff - 0.6 s")
        out.append(info)
        bound = int(e) + (int(np.argmin(hips[e:e + int(0.5 * fps)])) if e < T else 0)
    for k, j in enumerate(out):
        nxt = out[k + 1].crouch_onset if k + 1 < len(out) else None
        j.stable_landing = recovery_frame(g, j, limit=nxt) is not None
        lo = max(0, j.landing - 5)
        j.flickers = int((np.diff(on[lo:lo + int(fps)].astype(int), axis=0) != 0).sum())
        j.score = j.flight_s - (0.0 if j.stable_landing else 0.15) - 0.03 * j.flickers
    return out


def best_jump(jumps):
    """The jump to keep: highest score (airtime, penalised for an unstable landing / contact flicker); ties by hip rise."""
    if not jumps:
        return JumpInfo()
    return max(jumps, key=lambda j: (round(j.score, 3), j.hips_rise_m))


def detect_jump(g: Gem, min_flight_s=MIN_FLIGHT_S, min_crouch_m=0.03) -> JumpInfo:
    """The best jump of the take (see `detect_jumps`), or JumpInfo(found=False)."""
    return best_jump(detect_jumps(g, min_flight_s, MIN_CLEARANCE_M, min_crouch_m))


def recovery_frame(g: Gem, jump: JumpInfo, max_s=3.0, hold_s=0.1, v_thr=0.3, limit=None):
    """First frame after landing where the hips are back at standing height and vertically still, feet down.
    `limit`: do not look past this frame (e.g. the next jump's crouch onset)."""
    fps = g.fps
    on, _ = foot_contacts(g.contact)
    hips = _smooth(g.J[:, 0, 1], fps)
    vy = np.gradient(hips) * fps
    a = jump.landing
    end = min(g.T, a + int(max_s * fps))
    if limit is not None:
        end = min(end, int(limit))
    if end <= a + 1:
        return None
    i_min = a + int(np.argmin(hips[a:min(end, a + int(1.0 * fps))]))
    hold = max(1, int(hold_s * fps))
    ok = on.all(1) & (hips >= jump.stand_height_m - 0.05) & (np.abs(vy) < v_thr)
    for i in range(i_min, min(g.T - hold + 1, end - hold + 1)):
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


def action_window(g: Gem, jump: JumpInfo, pad_s=0.5, jumps=None):
    """(start, end_exclusive, details) for the jump action, or None when no jump was found.

    start = crouch onset - pad; end = stable standing after landing + pad, cut short where the
    performer starts walking away. With several jumps in the take (`jumps`, from `detect_jumps`), the window is
    limited to the kept one: it starts no earlier than the standing pose after the previous jump (or the midpoint
    between the previous landing and this crouch when there is no standing) and ends no later than the next
    jump's crouch onset.
    """
    if not jump.found:
        return None
    fps = g.fps
    pad = int(round(pad_s * fps))
    details = {}
    prev = nxt = None
    for j in jumps or []:
        if j.landing <= jump.takeoff:
            prev = j
        elif j.takeoff >= jump.landing and nxt is None:
            nxt = j
    limit = nxt.crouch_onset if nxt is not None else None
    rec = recovery_frame(g, jump, limit=limit)
    if rec is None:
        if limit is not None:
            end = int(limit)
            details["end_cut_by_next_jump"] = True
        else:
            rec = min(g.T - 1, jump.landing + int(1.0 * fps))
            details["recovery"] = "stable standing height not found after landing; used landing + 1 s"
            end = rec + pad
    else:
        end = rec + pad
        if limit is not None and end > limit:
            end = int(limit)
            details["end_cut_by_next_jump"] = True
        w = walk_onset(g, rec)
        if w is not None and w < end:
            end = max(rec + int(0.2 * fps), w)
            details["end_cut_by_walk_out"] = True
        details["recovery_frame"] = int(rec)
    a = max(0, jump.crouch_onset - pad)
    if prev is not None:
        prev_rec = recovery_frame(g, prev, limit=jump.crouch_onset)
        bound = prev_rec if prev_rec is not None else (prev.landing + jump.crouch_onset) // 2
        if bound > a:
            a = int(min(bound, jump.crouch_onset))
            details["start_after_previous_jump"] = True
    b = min(g.T, end + 1)
    return int(a), int(b), details


def idle_trim(g: Gem, pad_s=0.3, frac=0.25, min_peak=0.2, min_len_s=1.0):
    """(start, end_exclusive, details) for a generic action: cut only the idle (low-motion) frames at the start and
    end. Motion = mean speed (m/s) of hips, head, hands, feet, shins and forearms, smoothed over ~0.4 s. The tracker
    jitters even when the performer stands still, so "idle" is relative: below noise floor (10th percentile) +
    `frac` x (95th percentile - floor). Nothing is cut when the whole clip is quiet (peak < `min_peak`)."""
    fps, T = g.fps, g.T
    sel = [g.idx(n) for n in ("Hips", "Head", "LeftHand", "RightHand", "LeftFoot", "RightFoot", "LeftShin", "RightShin",
                              "LeftForeArm", "RightForeArm")]
    sp = np.linalg.norm(np.gradient(g.J[:, sel], axis=0), axis=2).mean(1) * fps
    sp = uniform_filter1d(sp, max(3, int(0.4 * fps)), mode="nearest")
    floor, peak = np.percentile(sp, [10, 95])
    thr = float(floor + frac * (peak - floor))
    details = {"mode": "idle_trim", "motion_threshold_mps": round(thr, 3)}
    active = np.flatnonzero(sp > thr)
    if peak < min_peak or len(active) == 0:
        details["note"] = "no clear motion; whole clip kept"
        return 0, T, details
    pad = int(round(pad_s * fps))
    a, b = max(0, int(active[0]) - pad), min(T, int(active[-1]) + 1 + pad)
    if b - a < int(min_len_s * fps):
        c, h = (a + b) // 2, int(min_len_s * fps) // 2
        a, b = max(0, c - h), min(T, c + h)
    return a, b, details


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
