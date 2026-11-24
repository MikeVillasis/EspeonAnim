"""POV (first-person hands) pipeline tests. No GPU, no real POV clip: hands are synthesised from Manny's own
reference hand, projected through a pinhole camera, and pushed through the same stages the pipeline uses."""
import json
import math

import numpy as np
import pytest

from espeon_processor import mathutil as mu, pov, pov_clean, pov_geom
from espeon_processor.gem_stage import ProcessorError
from espeon_processor.motion_io import validate_motion
from espeon_processor.pov_detect import HandTrack, assign_slots
from espeon_processor.pov_retarget import (HEAD_TO_UE, POV_BONES, SGN, PovRig, finger_bones, side_bones, two_bone_ik)
from espeon_processor.skeleton import Profile


@pytest.fixture(scope="module")
def rig():
    return PovRig(Profile.load("ue5_manny"))


def angle_deg(qa, qb):
    return np.degrees(2 * np.arccos(np.clip(np.abs((qa * qb).sum(-1)), 0, 1)))


# --- two-bone IK -----------------------------------------------------------------------------------------------

def test_ik_reaches_target_and_keeps_bone_lengths():
    rng = np.random.default_rng(1)
    S = np.array([[20.0, 0, 140]]).repeat(50, 0)
    T = S + rng.uniform(-1, 1, (50, 3)) * 25 + np.array([0, 20, 0])
    T = S + mu.normalize(T - S) * rng.uniform(15, 50, (50, 1))
    elbow, wrist, st = two_bone_ik(S, T, 27.8, 27.3, np.array([0.3, 0, -1.0]))
    assert np.abs(wrist - T).max() < 1e-6 and st["out_of_reach_fraction"] == 0
    assert np.abs(np.linalg.norm(elbow - S, axis=1) - 27.8).max() < 1e-6
    assert np.abs(np.linalg.norm(wrist - elbow, axis=1) - 27.3).max() < 1e-6


def test_ik_straightens_towards_unreachable_target():
    S = np.array([[20.0, 0, 140]])
    T = S + np.array([[0, 80.0, 0]])
    elbow, wrist, st = two_bone_ik(S, T, 27.8, 27.3, np.array([0.3, 0, -1.0]))
    assert st["out_of_reach_fraction"] == 1.0 and st["max_reach_error_cm"] > 20
    assert np.linalg.norm(wrist - S) == pytest.approx(55.1, abs=0.05)
    assert mu.angle_between(wrist - S, T - S)[0] < 0.1                       # points at the target
    assert mu.angle_between(elbow - S, wrist - S)[0] < 1.0                   # straight arm


def test_ik_elbow_points_down_and_out():
    S = np.array([[20.0, 0, 140]])
    T = S + np.array([[-5.0, 40.0, 5.0]])
    for pole, out in ((np.array([0.3, 0, -1.0]), 1), (np.array([-0.3, 0, -1.0]), -1)):
        elbow, wrist, _ = two_bone_ik(S, T, 27.8, 27.3, pole)
        mid = 0.5 * (S + wrist)
        assert (elbow - mid)[0, 2] < 0 and out * (elbow - mid)[0, 0] > 0


def test_arm_retarget_fk_hits_the_wrist_target_with_elbow_down_and_out(rig):
    wrist = np.array([[0.12, 0.30, -0.12]])
    for s in ("l", "r"):
        w_head = wrist * np.array([[1 if s == "r" else -1, 1, 1]])
        q, st = rig.retarget_side(s, w_head, np.tile(rig.reference_hand_head(s), (1, 1, 1)), 1.0)
        full = rig.reference_local_q(1)
        full[:, [rig.idx[b] for b in side_bones(s)]] = q
        wq, wt = rig.fk(full)
        target = rig.eyes + 100 * (w_head[0] @ HEAD_TO_UE.T)
        assert np.linalg.norm(wt[0, rig.idx[f"hand_{s}"]] - target) < 0.05
        el, up = wt[0, rig.idx[f"lowerarm_{s}"]], wt[0, rig.idx[f"upperarm_{s}"]]
        mid = 0.5 * (up + target)
        assert el[2] < mid[2] and SGN[s] * (el[0] - mid[0]) > 0              # down, and out (left = +X)


def test_clavicle_assist_is_limited_to_15_degrees(rig):
    far = np.array([[0.0, 0.85, 0.0]])                                       # beyond reach
    q, st = rig.retarget_side("l", far, rig.reference_hand_head("l")[None], 1.0)
    assert 1.0 < st["clavicle_max_deg"] <= 15.0 + 1e-6 and st["out_of_reach_fraction"] == 1.0
    near = np.array([[-0.1, 0.25, -0.15]])
    _, st2 = rig.retarget_side("l", near, rig.reference_hand_head("l")[None], 1.0)
    assert st2["clavicle_max_deg"] < 1e-6


# --- fingers ---------------------------------------------------------------------------------------------------

def _hand_from_local(rig, side, finger_q):
    """Landmarks (UE cm, relative to the wrist) and hand world rotation for given local rotations of the 15 finger bones."""
    full = rig.reference_local_q(1)
    ids = [rig.idx[b] for b in finger_bones(side)]
    full[:, ids] = finger_q
    wq, wt = rig.fk(full)
    lm = rig.landmarks_ue(wq, wt, side)
    return lm - lm[:, :1]


@pytest.mark.parametrize("side", ["l", "r"])
def test_reference_hand_gives_reference_rotations(rig, side):
    rel = rig.reference_hand_head(side)[None]
    q, _ = rig.retarget_side(side, np.array([[0.1 * SGN[side] * -1, 0.3, -0.1]]), rel, 1.0)
    ref = rig.reference_local_q(1)[0][[rig.idx[b] for b in finger_bones(side)]]
    assert angle_deg(q[0, 4:], ref).max() < 1.0


@pytest.mark.parametrize("side", ["l", "r"])
def test_fist_gives_flexion_within_clamps(rig, side):
    ref = rig.reference_local_q(1)[0][[rig.idx[b] for b in finger_bones(side)]]
    cmd = ref.copy()
    for i, b in enumerate(finger_bones(side)):
        if not b.startswith("thumb"):
            cmd[i] = mu.qmul(ref[i], mu.q_from_z(math.radians(-60))) if hasattr(mu, "q_from_z") else \
                mu.qmul(ref[i], np.array([0, 0, math.sin(math.radians(-30)), math.cos(math.radians(-30))]))
    rel_ue = _hand_from_local(rig, side, cmd[None])
    rel_head = rel_ue @ HEAD_TO_UE.T / 100.0
    q, st = rig.retarget_side(side, np.array([[0.1 * SGN[side] * -1, 0.3, -0.1]]), rel_head, 1.0)
    got = q[0, 4:]
    for i, b in enumerate(finger_bones(side)):
        if not b.startswith("thumb"):
            assert angle_deg(got[i], cmd[i]) < 3.0, b
    assert st["finger_clamped_fraction"] < 0.2


@pytest.mark.parametrize("side", ["l", "r"])
def test_hyperflexed_fingers_are_clamped_to_110_degrees(rig, side):
    ref = rig.reference_local_q(1)[0][[rig.idx[b] for b in finger_bones(side)]]
    cmd = ref.copy()
    for i, b in enumerate(finger_bones(side)):
        if b.startswith("index"):
            cmd[i] = mu.qmul(ref[i], np.array([0, 0, math.sin(math.radians(-80)), math.cos(math.radians(-80))]))   # 160 deg more curl
    rel_head = _hand_from_local(rig, side, cmd[None]) @ HEAD_TO_UE.T / 100.0
    q, st = rig.retarget_side(side, np.array([[0.1 * SGN[side] * -1, 0.3, -0.1]]), rel_head, 1.0)
    assert st["finger_clamped_fraction"] > 0
    a_b = np.array([SGN[side], 0, 0])
    for i, b in enumerate(finger_bones(side)):
        if b.startswith("index"):
            d = mu.q_to_mat(q[0, 4 + i]) @ a_b                               # bone direction in the parent frame
            flex = -math.degrees(math.atan2(SGN[side] * d[1], SGN[side] * d[0]))
            assert -10.5 <= flex <= 110.5, (b, flex)


# --- depth, head frame -----------------------------------------------------------------------------------------

def _project(P, f, W, H):
    return np.stack([P[..., 0] / P[..., 2] * f / W + 0.5, P[..., 1] / P[..., 2] * f / H + 0.5, np.zeros(P.shape[:-1])], -1)


@pytest.mark.parametrize("depth,u,v", [(0.45, 0.0, 0.0), (0.30, 0.08, -0.05), (0.7, -0.12, 0.1)])
def test_depth_from_projected_hand(rig, depth, u, v):
    H_m = 1.70
    rel = rig.reference_hand_head("r")                                       # metres, any orientation will do
    rel = rel * (pov_geom.HAND_LENGTH_PER_HEIGHT * H_m / sum(np.linalg.norm(rel[b] - rel[a]) for a, b in pov_geom.CHAIN))
    rot = mu.q_to_mat(mu.qnorm(np.array([0.3, -0.2, 0.1, 0.9])))
    world = rel @ rot.T
    f, W, Hh = 1400.0, 1080, 1920
    wrist = np.array([u * depth, v * depth, depth])
    P = wrist + world
    img = _project(P, f, W, Hh)
    model = pov_geom.measure_hand_model(np.repeat(world[None], 5, 0), np.ones(5), H_m, True)
    cam = pov_geom.Camera(W, Hh, f, "manifest")
    wr, _ = pov_geom.wrist_from_pinhole(img[None], world[None], model, cam)
    assert np.linalg.norm(wr[0] - wrist) / depth < 0.01


def test_fallback_hand_scale_is_0_108_times_height():
    rng = np.random.default_rng(0)
    base = np.cumsum(rng.uniform(0.01, 0.02, (21, 3)), 0)
    w = np.repeat(base[None], 6, 0)
    m = pov_geom.measure_hand_model(w, np.ones(6), 1.80, False)
    assert m.chain_len == pytest.approx(0.108 * 1.80, rel=1e-6) and m.source == "take"
    cal = pov_geom.measure_hand_model(w * (0.9 * 0.108 * 1.8 / (m.chain_len / m.scale)), np.ones(6), 1.80, True)   # plausible: kept as is
    assert cal.source == "calibration" and cal.scale == 1.0


def test_camera_defaults_and_manifest_focal():
    c = pov_geom.Camera.from_take({}, 1080, 1920)
    assert c.source != "manifest" and c.f_px == pytest.approx(1920 / 2 / math.tan(math.radians(35)))
    c = pov_geom.Camera.from_take({"camera": {"focal_length_px": 1450.0}, "width": 1920, "height": 1080}, 1080, 1920)
    assert c.f_px == pytest.approx(1450.0) and c.source == "manifest"


def test_head_frame_from_gravity():
    p = math.radians(45)
    g = np.array([0.0, math.cos(p), math.sin(p)]) * 9.81                     # portrait phone, camera pitched 45 deg down
    sky, src = pov_geom.sky_in_camera(g, 90)
    assert src == "imu"
    R = pov_geom.head_rotation(sky)
    assert np.linalg.det(R) == pytest.approx(1.0) and np.allclose(R @ R.T, np.eye(3))
    assert np.allclose(R @ [0, 0, 1], [0, math.cos(p), -math.sin(p)], atol=1e-9)     # optical axis: forward and down
    assert np.allclose(R @ [1, 0, 0], [1, 0, 0], atol=1e-9)                          # image right stays right
    assert pov_geom.mount_angles(sky)[0] == pytest.approx(-45.0) and abs(pov_geom.mount_angles(sky)[1]) < 1e-9


def test_head_frame_removes_roll_and_handles_landscape():
    p, roll = math.radians(30), math.radians(20)
    sky = np.array([math.sin(roll) * math.cos(p), -math.cos(roll) * math.cos(p), -math.sin(p)])   # camera rolled by 20 deg
    R = pov_geom.head_rotation(sky)
    fwd = R @ [0, 0, 1]
    assert fwd[2] == pytest.approx(-math.sin(p)) and abs((R @ [0, 0, 1])[0]) < 1e-9   # still straight ahead, level
    assert pov_geom.mount_angles(sky)[1] == pytest.approx(20.0)
    sky0, _ = pov_geom.sky_in_camera(np.array([9.81, 0, 0]), 0)             # landscape (rotation 0): up is device +x
    assert np.allclose(sky0, [0, -1, 0], atol=1e-9)
    assert pov_geom.sky_in_camera(None, 90, "chest")[1] == "assumed"
    assert pov_geom.mount_angles(pov_geom.sky_in_camera(None, 90, "chest")[0])[0] == pytest.approx(-30.0)
    assert np.allclose(pov_geom.mount_offset("chest", 1.70), [0, 0.05, -0.30])


# --- cleaning --------------------------------------------------------------------------------------------------

def _series(valid, wrist_val=0.3):
    T = len(valid)
    wr = np.tile([0.1, wrist_val, -0.1], (T, 1)).astype(float)
    wr[:, 1] += np.linspace(0, 0.05, T)
    rel = np.tile(np.linspace(0, 0.1, 63).reshape(21, 3), (T, 1, 1))
    return pov_geom.HandSeries(np.where(valid[:, None], wr, np.nan), np.where(valid[:, None, None], rel, np.nan),
                               valid, np.where(valid, 0.9, np.nan))


REST = np.array([0.2, 0.0, -0.75])


def test_short_gap_is_interpolated_long_gap_eases_to_rest():
    valid = np.ones(240, bool)
    valid[60:78] = False                                                     # 0.3 s at 60 fps
    valid[120:180] = False                                                   # 1.0 s
    out = pov_clean.fill_and_resample(_series(valid), 60.0, 0.0, 4.0, REST, np.zeros((21, 3)), smooth=False)
    assert not out.resting[60:78].any() and np.abs(out.wrist[60:78, 1] - 0.3).max() < 0.06
    assert out.resting[135:165].all() and np.abs(out.wrist[150] - REST).max() < 1e-9
    assert np.abs(out.wrist[120] - out.wrist[119]).max() < 0.05              # eases, no jump at the gap start
    assert np.abs(out.wrist[180] - out.wrist[181]).max() < 0.05
    assert out.seen[:60].all() and not out.seen[130]


def test_leading_and_trailing_gaps_start_and_end_at_rest_and_resample_to_60():
    valid = np.zeros(60, bool)
    valid[20:40] = True
    out = pov_clean.fill_and_resample(_series(valid), 30.0, 0.0, 2.0, REST, np.zeros((21, 3)), smooth=False)   # 30 fps in
    assert len(out.wrist) == 120 and np.allclose(out.wrist[0], REST) and np.allclose(out.wrist[-1], REST)
    assert out.seen[45:75].all() and not out.seen[5]


def test_one_euro_reduces_jitter_without_lag():
    rng = np.random.default_rng(2)
    t = np.arange(240) / 60.0
    clean = np.stack([0.1 * np.sin(2 * math.pi * 0.7 * t), 0.3 + 0 * t, 0 * t], 1)
    noisy = clean + rng.normal(0, 0.004, clean.shape)
    sm = pov_clean.one_euro_zero_phase(noisy, 60, **pov_clean.WRIST_FILTER)
    assert np.std(sm - clean) < 0.6 * np.std(noisy - clean)
    lagk = np.argmin([np.abs(sm[10 + k:230 + k, 0] - clean[10:230, 0]).mean() for k in range(-5, 6)]) - 5
    assert lagk == 0


def test_active_window_cuts_idle_ends():
    v = np.zeros(300, bool)
    v[100:200] = True
    a, b = pov_clean.active_window(v, 60.0)
    assert a == pytest.approx(100 / 60 - 0.25) and b == pytest.approx(200 / 60 + 0.25)
    assert pov_clean.active_window(np.zeros(10, bool), 60.0) is None


# --- handedness ------------------------------------------------------------------------------------------------

def test_handedness_labels_are_swapped_for_unmirrored_frames():
    # MediaPipe says "Left" for the hand that is really the performer's right one (un-mirrored image)
    assert assign_slots([[("Left", 0.9, (0.7, 0.5))]], 60.0) == [[1]]
    assert assign_slots([[("Right", 0.9, (0.3, 0.5))]], 60.0) == [[0]]


def test_lone_hand_keeps_its_slot_when_the_label_flickers():
    dets = [[("Right", 0.9, (0.3 + 0.002 * i, 0.5))] for i in range(10)]
    dets[5] = [("Left", 0.6, (0.31, 0.5))]
    assert assign_slots(dets, 60.0) == [[0]] * 10


def test_two_hands_do_not_swap_when_their_labels_flicker():
    dets = [[("Right", 0.9, (0.3, 0.5)), ("Left", 0.9, (0.7, 0.5))] for _ in range(8)]
    dets[4] = [("Left", 0.55, (0.3, 0.5)), ("Right", 0.55, (0.7, 0.5))]       # both labels wrong in one frame
    slots = assign_slots(dets, 60.0)
    assert all(s == [0, 1] for s in slots)
    d2 = [[("Left", 0.9, (0.5, 0.5)), ("Left", 0.8, (0.5, 0.505))]]           # one hand detected twice
    assert len(assign_slots(d2, 60.0)[0]) == 1


# --- synthetic end to end ---------------------------------------------------------------------------------------

F_PX, W_PX, H_PX = 1400.0, 1080, 1920


def synth_track(rig, n=150, fps=60.0, drop_left=(50, 60), only_right_after=None):
    """Hands moving in the head frame, seen by a camera pitched 45 deg down (the no-IMU assumption)."""
    sky, _ = pov_geom.sky_in_camera(None, 90, "head")
    R = pov_geom.head_rotation(sky)
    t = np.arange(n) / fps
    truth = {}
    img = np.full((n, 2, 21, 3), np.nan); world = np.full((n, 2, 21, 3), np.nan); score = np.full((n, 2), np.nan)
    for slot, s in ((0, "l"), (1, "r")):
        rel0 = rig.reference_hand_head(s)
        rel0 = rel0 * (pov_geom.HAND_LENGTH_PER_HEIGHT * 1.70 / sum(np.linalg.norm(rel0[b] - rel0[a]) for a, b in pov_geom.CHAIN))
        sx = 1 if s == "r" else -1
        wr = np.stack([sx * (0.07 + 0.02 * np.sin(2 * t)), 0.35 + 0.04 * np.cos(1.5 * t), -0.18 + 0.03 * np.sin(1.2 * t)], 1)
        truth[s] = wr
        for i in range(n):
            yaw = 0.3 * math.sin(2 * t[i])
            Rz = np.array([[math.cos(yaw), -math.sin(yaw), 0], [math.sin(yaw), math.cos(yaw), 0], [0, 0, 1.0]])
            rel_head = rel0 @ Rz.T
            world_cam = rel_head @ R                                         # inverse of rel_head = world_cam @ R.T
            P = wr[i] @ R + world_cam                                        # camera coords of every landmark
            img[i, slot] = _project(P, F_PX, W_PX, H_PX)
            world[i, slot] = world_cam
            score[i, slot] = 0.95
    if drop_left:
        img[drop_left[0]:drop_left[1], 0] = np.nan; world[drop_left[0]:drop_left[1], 0] = np.nan; score[drop_left[0]:drop_left[1], 0] = np.nan
    return HandTrack(fps, W_PX, H_PX, img, world, score, 120.0), truth


def test_synthetic_hands_flow_through_solve_to_valid_motion(rig):
    track, truth = synth_track(rig)
    cam = pov_geom.Camera.from_take({"camera": {"focal_length_px": F_PX}, "width": 1920, "height": 1080}, W_PX, H_PX)
    sol = pov.solve_pov(track, None, cam, None, 90, "head", 170.0, rig)
    assert sol.gravity_source == "assumed" and sol.pitch_deg == pytest.approx(-45.0)
    assert sol.frames == 150
    for s in ("l", "r"):                                                      # recovered wrist = truth (cm-level)
        err = np.linalg.norm(sol.hands[s].wrist - truth[s], axis=1)
        assert np.median(err) < 0.02, (s, np.median(err))
    assert sol.hands["l"].seen[:45].all() and not sol.hands["l"].seen[55]
    md = pov.motion_dict_pov(sol, rig, "ue5_manny")
    validate_motion(md)
    assert md["bones"] == POV_BONES and len(md["bones"]) == 38 and "root" not in md["bones"]
    assert {b.split("_")[0] for b in md["bones"]} == {"clavicle", "upperarm", "lowerarm", "hand", "thumb", "index", "middle", "ring", "pinky"}
    assert sum(b.startswith(("thumb", "index", "middle", "ring", "pinky")) for b in md["bones"]) == 30
    assert np.abs(np.array(md["root_translation"])).max() == 0
    assert np.allclose(np.array(md["pelvis_translation"]), rig.profile.ref_t[rig.g["pelvis"]], atol=1e-4)
    # FK: Manny's wrists end up at eyes + k * head-frame target
    wq, wt = rig.fk(sol.local_q)
    for s in ("l", "r"):
        want = rig.eyes + sol.k * 100 * (sol.hands[s].wrist @ HEAD_TO_UE.T)
        assert np.linalg.norm(wt[:, rig.idx[f"hand_{s}"]] - want, axis=1).max() < 0.5


def test_pipeline_pov_action_writes_motion_and_report(tmp_path, monkeypatch, rig):
    from espeon_processor import pipeline, pov_detect
    track, _ = synth_track(rig, n=180, drop_left=(40, 150))                   # left hand lost for 1.8 s
    monkeypatch.setattr(pov_detect, "track_video", lambda *a, **k: track)
    clip = tmp_path / "pov.mp4"
    clip.write_bytes(b"not a real video")
    events = []
    res = pipeline.process(clip, tmp_path / "out", emit=events.append, action="pov")
    motion = json.loads(open(res["motion"]).read())
    validate_motion(motion)
    assert motion["frame_count"] == 180 and motion["fps"] == 60 and len(motion["bones"]) == 38
    rep = json.loads(open(res["report"]).read())
    codes = [w["code"] for w in rep["warnings"]]
    assert "hands_lost" in codes and rep["details"]["action"] == "pov"
    assert "The left hand was out of view" in next(w["message"] for w in rep["warnings"] if w["code"] == "hands_lost")
    pv = rep["details"]["pov"]
    assert pv["mount"] == "head" and pv["gravity"] == "assumed" and pv["hands_lost"]["left"] > 0.55 and pv["hands_lost"]["right"] == 0
    assert any("no IMU" in a for a in rep["assumptions"]) and any("focal_length" in a for a in rep["assumptions"])
    assert (tmp_path / "out" / "debug" / "pov_hands.png").exists()
    assert events[0]["event"] == "progress" and events[-1]["progress"] == 1.0


def test_pipeline_pov_without_any_hand_fails_clearly(tmp_path, monkeypatch, rig):
    from espeon_processor import pipeline, pov_detect
    track, _ = synth_track(rig, n=30, drop_left=(0, 30))
    track.score[:, 1] = np.nan
    track.img[:, 1] = np.nan; track.world[:, 1] = np.nan
    monkeypatch.setattr(pov_detect, "track_video", lambda *a, **k: track)
    clip = tmp_path / "pov.mp4"
    clip.write_bytes(b"x")
    with pytest.raises(ProcessorError, match="No hands"):
        pipeline.process(clip, tmp_path / "out", action="pov")


def test_take_stats_flags_cropped_and_close_hands(rig):
    track, _ = synth_track(rig, n=40, drop_left=None)
    st = pov.take_stats(track)
    assert st["lost_l"] == 0 and st["hand_cropped_fraction"] == 0 and st["hands_too_close_fraction"] == 0
    track.img[:, 1, 4, 0] = 0.01                                              # a fingertip at the edge in every frame
    track.img[:20, 0, 12, 1] = 1.2                                            # tall left hand in half the frames
    st = pov.take_stats(track)
    assert st["hand_cropped_fraction"] == 1.0 and st["hands_too_close_fraction"] == pytest.approx(0.5)


def test_fk_fingers_follow_the_solved_hand_and_match_the_observed_segments(rig):
    track, _ = synth_track(rig, n=60, drop_left=None)
    cam = pov_geom.Camera.from_take({"camera": {"focal_length_px": F_PX}, "width": 1920, "height": 1080}, W_PX, H_PX)
    sol = pov.solve_pov(track, None, cam, None, 90, "head", 170.0, rig)
    wq, wt = rig.fk(sol.local_q)
    for s in ("l", "r"):
        lm = rig.landmarks_ue(wq, wt, s)
        want = sol.hands[s].rel @ HEAD_TO_UE.T                                # observed landmarks relative to the wrist
        f = 30
        assert np.linalg.norm(lm[f, 0] - wt[f, rig.idx[f"hand_{s}"]]) < 1e-9
        # every finger bone points along its observed landmark segment (the last segment ends at a tip extrapolated along the bone)
        for k0 in (1, 5, 9, 13, 17):
            for n in range(3):
                got = lm[f, k0 + n + 1] - lm[f, k0 + n]
                assert mu.angle_between(got, want[f, k0 + n + 1] - want[f, k0 + n]) < 6.0, (s, k0, n)
        assert lm[f, 12, 1] - lm[f, 0, 1] > 5                                 # middle finger points forward (+Y), not at the hip
