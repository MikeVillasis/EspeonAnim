"""POV (first-person hands) pipeline, docs/formats.md section 7: detections -> head frame -> clean -> Manny arms/hands.

Modules: pov_detect (MediaPipe hands), pov_geom (hand size, depth, head frame), pov_clean (gaps, smoothing, 60 fps),
pov_retarget (IK, palm, fingers). This file glues them and builds motion.json.
"""
from __future__ import annotations
from dataclasses import dataclass, field
import numpy as np
from . import pov_clean, pov_geom
from .gem_stage import ProcessorError
from .pov_detect import HandTrack
from .pov_retarget import HEAD_TO_UE, POV_BONES, PovRig, SIDES, retarget_pov

SIDE_SLOT = {"l": 0, "r": 1}
SIDE_NAME = {"l": "left", "r": "right"}
LOST_WARN_FRACTION = 0.25          # hands_lost: a hand missing in more than this share of the frames
REPORT_FRACTION = 0.20             # hand_cropped / hands_too_close: held in this share of the frames (formats.md section 4)
EDGE_MARGIN = 0.03                 # hand_cropped: a landmark within 3% of the frame edge
TOO_CLOSE_HEIGHT = 0.60            # hands_too_close: hand bounding box taller than 60% of the frame
REST_WRIST_HEAD = np.array([0.20, 0.0, -0.75])      # hand at the hip, below the view, per 1.70 m of height (x mirrored for left)


def take_stats(track: HandTrack, window=None):
    """Detection quality of a take over frames [a, b) (default all): confidence, lost fractions, crop / close fractions."""
    a, b = window or (0, track.T)
    pres = track.present[a:b]
    img = track.img[a:b]
    n = max(b - a, 1)
    any_hand = pres.any(1)
    conf = track.score[a:b]
    mean_conf = float(np.nanmean(conf)) if np.isfinite(conf).any() else 0.0
    cropped = np.zeros(len(pres), bool); close = np.zeros(len(pres), bool)
    for s in (0, 1):
        m = pres[:, s]
        if not m.any():
            continue
        p = img[m, s]
        edge = ((p[..., :2] < EDGE_MARGIN) | (p[..., :2] > 1 - EDGE_MARGIN)).any(axis=(1, 2))
        cropped[m] |= edge
        close[m] |= (p[..., 1].max(1) - p[..., 1].min(1)) > TOO_CLOSE_HEIGHT
    ah = max(int(any_hand.sum()), 1)
    return {"frames": int(b - a), "mean_confidence": mean_conf,
            "lost_l": float(1 - pres[:, 0].mean()) if len(pres) else 1.0, "lost_r": float(1 - pres[:, 1].mean()) if len(pres) else 1.0,
            "any_hand_fraction": float(any_hand.sum() / n),
            "hand_cropped_fraction": float(cropped.sum() / ah), "hands_too_close_fraction": float(close.sum() / ah)}


def take_score(st):
    """Take choice: highest detection confidence, then fewest lost-hand frames."""
    lost = 0.5 * (st["lost_l"] + st["lost_r"])
    return round(0.5 * st["mean_confidence"] + 0.5 * (1 - lost), 4)


def rest_pose(rig: PovRig, side, height_m):
    """(wrist (3,), rel (21,3)) head frame: the arm hangs, the hand rests at the hip below the view."""
    w = REST_WRIST_HEAD * np.array([1.0 if side == "r" else -1.0, 1.0, 1.0]) * (height_m / 1.70)
    return w, rig.reference_hand_head(side)


@dataclass
class PovSolution:
    local_q: np.ndarray                  # (F,38,4) for POV_BONES, 60 fps
    window_s: tuple                      # (start, end) in the take, seconds
    mount: str
    pitch_deg: float
    roll_deg: float
    gravity_source: str                  # imu | assumed
    camera: pov_geom.Camera
    models: dict                         # side -> HandModel
    k: float                             # wrist target scale (Manny arm / performer arm)
    hands: dict                          # side -> HandClean
    stats: dict
    assumptions: list = field(default_factory=list)

    @property
    def frames(self):
        return len(self.local_q)


def solve_pov(track: HandTrack, calib: HandTrack | None, cam: pov_geom.Camera, g_dev, rotation_deg, mount, height_cm,
              rig: PovRig, assumptions=None, height_assumed=False):
    """Steps 2-6 for one take. `g_dev` = median IMU gravity (device frame) or None."""
    assumptions = list(assumptions or [])
    H = height_cm / 100.0
    if height_assumed:
        assumptions.append(f"performer height assumed {height_cm:.0f} cm (not in manifest); arm length and hand size scale with it")
    # hand sizes: calibration clip when it shows the hand, else the take itself, else typical proportions
    models = {}
    for s in SIDES:
        sl = SIDE_SLOT[s]
        for src, calibrated in ((calib, True), (track, False)):
            if src is not None and src.present[:, sl].sum() >= 3:
                models[s] = pov_geom.measure_hand_model(src.world[:, sl], src.score[:, sl], H, calibrated)
                break
        else:
            models[s] = None
    for s in SIDES:
        if models[s] is None:
            other = models["r" if s == "l" else "l"]
            models[s] = other if other is not None else pov_geom.assumed_hand_model(H)
    if calib is None:
        assumptions.append("no hands calibration clip: hand size taken from the take's own world landmarks, scaled to 0.108 x height")
    for s in SIDES:
        if models[s].note:
            assumptions.append(f"{SIDE_NAME[s]} hand: {models[s].note}")
    if cam.source != "manifest":
        assumptions.append("no camera.focal_length_px in the manifest: assumed 70 deg horizontal FOV for the hand depth")

    sky, gsrc = pov_geom.sky_in_camera(g_dev, rotation_deg, mount)
    if gsrc == "assumed":
        assumptions.append(f"no IMU data: camera assumed pitched {pov_geom.DEFAULT_PITCH_DOWN_DEG.get(mount, 45):.0f} deg down ({mount} mount)")
    R_head = pov_geom.head_rotation(sky)
    pitch, roll = pov_geom.mount_angles(sky)
    offset = pov_geom.mount_offset(mount, H)
    if mount == "chest":
        assumptions.append("chest mount: camera assumed 5 cm in front of and 30 cm below the eyes (x height / 1.70)")

    series = {s: pov_geom.hand_series(track, SIDE_SLOT[s], models[s], cam, R_head, offset) for s in SIDES}
    valid_any = series["l"].valid | series["r"].valid
    win = pov_clean.active_window(valid_any, track.fps)
    if win is None:
        raise ProcessorError("No hands were tracked in the chosen take.")
    t0, t1 = win
    hands, hand_arrays = {}, {}
    for s in SIDES:
        rw, rr = rest_pose(rig, s, H)
        hands[s] = pov_clean.fill_and_resample(series[s], track.fps, t0, t1, rw, rr)
        hand_arrays[s] = (hands[s].wrist, hands[s].rel)

    performer_arm_m = max(0.44 * H - 0.5 * (models["l"].chain_len + models["r"].chain_len), 0.3)
    k = rig.arm_length_cm / 100.0 / performer_arm_m
    motion = retarget_pov(rig, hand_arrays, k)
    st = {"window_frames": int(round((t1 - t0) * 60)), "performer_arm_m": round(performer_arm_m, 3), "wrist_scale": round(k, 3),
          "retarget": motion.stats}
    return PovSolution(motion.local_q, (t0, t1), mount, pitch, roll, gsrc, cam, models, k, hands, st, assumptions)


def pelvis_translation(rig: PovRig):
    return rig.profile.ref_t[rig.g["pelvis"]]


def motion_dict_pov(sol: PovSolution, rig: PovRig, skeleton_name):
    F = sol.frames
    return {
        "format": "espeon-motion", "version": "1.0", "skeleton": skeleton_name, "fps": 60, "frame_count": F,
        "bones": list(POV_BONES),
        "root_translation": np.zeros((F, 3)).tolist(),
        "pelvis_translation": np.round(np.tile(pelvis_translation(rig), (F, 1)), 4).tolist(),
        "local_rotations": np.round(sol.local_q, 7).tolist(),
    }


def details_pov(sol: PovSolution, stats):
    """report.json details.pov"""
    return {
        "mount": sol.mount, "mount_pitch_deg": round(sol.pitch_deg, 1), "mount_roll_deg": round(sol.roll_deg, 1),
        "gravity": sol.gravity_source,
        "camera": {"focal_length_px": round(sol.camera.f_px, 1), "source": sol.camera.source},
        "hand_lengths": {SIDE_NAME[s]: sol.models[s].info() for s in SIDES},
        "hands_lost": {"left": round(stats["lost_l"], 3), "right": round(stats["lost_r"], 3)},
        "window_s": [round(sol.window_s[0], 3), round(sol.window_s[1], 3)],
        "frames_seen": {SIDE_NAME[s]: int(sol.hands[s].seen.sum()) for s in SIDES},
        "frames_resting": {SIDE_NAME[s]: int(sol.hands[s].resting.sum()) for s in SIDES},
        "wrist_target_scale": sol.stats["wrist_scale"], "performer_arm_m": sol.stats["performer_arm_m"],
        "ik": {SIDE_NAME[s]: {k: round(float(v), 3) for k, v in sol.stats["retarget"][s].items()} for s in SIDES},
    }
