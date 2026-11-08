import json
import os
import zipfile
from pathlib import Path

import numpy as np
import pytest

from espeon_processor import analysis, mathutil as mu
from espeon_processor.gem_stage import ProcessorError
from espeon_processor.motion_io import fk_from_motion, motion_dict, validate_motion
from espeon_processor.package import load_capture
from espeon_processor.retarget import Retargeter, direction_errors, pos_to_ue, rot_to_ue
from espeon_processor.skeleton import DRIVEN_BONES, Profile, Rig
from espeon_processor.solve import manny_leg_length, solve
from espeon_processor.validate import direction_error

OUT = Path(__file__).resolve().parents[1] / "out"
JUMPS = ["Jump1", "Jump2", "Jump3", "Jump4"]
needs_gem = pytest.mark.skipif(not (OUT / "Jump1" / "gem_world.npz").exists(), reason="run GEM on Jump1 first")


@pytest.fixture(scope="module")
def prof():
    return Profile.load("ue5_manny")


@pytest.fixture(scope="module")
def rig(prof):
    return Rig(prof)


def run_clip(name, rig):
    g = analysis.Gem.load(OUT / name / "gem_world.npz")
    jump = analysis.detect_jump(g)
    a, b, _ = analysis.action_window(g, jump)
    scale = manny_leg_length(rig) / (analysis.measure_body(g)["leg_length_m"] * 100)
    s = solve(g, (a, b), rig, scale)
    return g, jump, s


@pytest.fixture(scope="module")
def jump1(rig):
    return run_clip("Jump1", rig)


# --- skeleton profile -----------------------------------------------------------------------------

def test_profile_fk_reproduces_ref_component(prof):
    cq, ct = prof.fk_full()
    assert np.abs(ct - prof.ref_comp_t).max() < 1e-4
    assert np.abs((cq * prof.ref_comp_q).sum(-1)).min() > 1 - 1e-9      # same rotation up to sign


def test_rig_fk_of_reference_pose(rig):
    q = np.tile(rig.ref_q, (1, 1, 1))
    wq, wt = rig.fk(q, np.zeros((1, 3)), rig.ref_t[rig.idx["pelvis"]][None])
    assert np.abs(wt[0] - rig.ref_comp_t).max() < 1e-4


# --- retargeting --------------------------------------------------------------------------------------

@needs_gem
def test_identity_motion_retargets_to_matched_rest(rig):
    g = analysis.Gem.load(OUT / "Jump1" / "gem_world.npz")
    rt = Retargeter(rig, g.names, pos_to_ue(g.rest_pos), rot_to_ue(g.rest_rot))
    wq = rt.world_rotations(rot_to_ue(g.rest_rot)[None])[0]            # SOMA rest pose as the "motion"
    want = mu.mat_to_q(rt.match)
    assert np.abs(np.abs((wq * want).sum(-1)) - 1).max() < 1e-9
    # and the Manny bone directions of that pose equal the SOMA rest directions
    lq = rig.world_to_local(wq[None])
    _, wt = rig.fk(lq, np.zeros((1, 3)), rig.ref_t[rig.idx["pelvis"]][None])
    err, labels = direction_errors(rig, wt, pos_to_ue(g.rest_pos)[None], g.names)
    assert err.max() < 3.0, dict(zip(labels, err[0].round(2)))


def test_matched_rest_pose_is_unit_and_finite(rig):
    g = analysis.Gem.load(OUT / "Jump1" / "gem_world.npz") if (OUT / "Jump1" / "gem_world.npz").exists() else None
    if g is None:
        pytest.skip("no GEM data")
    rt = Retargeter(rig, g.names, pos_to_ue(g.rest_pos), rot_to_ue(g.rest_rot))
    q = rt.matched_rest_pose()
    assert np.isfinite(q).all() and np.allclose(np.linalg.norm(q, axis=-1), 1)


@needs_gem
def test_quaternions_unit_and_sign_continuous(jump1):
    _, _, s = jump1
    q = s.local_q
    assert np.abs(np.linalg.norm(q, axis=-1) - 1).max() < 1e-9
    dots = (q[1:] * q[:-1]).sum(-1)
    assert dots.min() > 0, "sign flip between consecutive frames"


@needs_gem
def test_motion_json_shape_validates(jump1):
    _, _, s = jump1
    d = motion_dict(s, "ue5_manny")
    validate_motion(json.loads(json.dumps(d)))
    assert d["fps"] == 60 and d["bones"] == DRIVEN_BONES
    assert d["frame_count"] == len(d["root_translation"]) == len(d["pelvis_translation"]) == len(d["local_rotations"])
    assert len(d["local_rotations"][0]) == len(d["bones"]) and len(d["local_rotations"][0][0]) == 4
    bad = dict(d, fps=30)
    with pytest.raises(ValueError):
        validate_motion(bad)
    with pytest.raises(ValueError):
        validate_motion(dict(d, version="2.0"))


@needs_gem
def test_motion_is_upright_forward_and_grounded(jump1, rig):
    _, _, s = jump1
    d = motion_dict(s, "ue5_manny")
    _, wq, wt = fk_from_motion(d)
    L, R = wt[:, rig.idx["thigh_l"]], wt[:, rig.idx["thigh_r"]]
    n = 20
    assert (L[:n, 0] - R[:n, 0]).mean() > 10, "character's left (+X) should be on +X"
    fwd = wt[:n, rig.idx["ball_l"], 1] - wt[:n, rig.idx["foot_l"], 1]
    assert fwd.mean() > 0, "toes should point to +Y (Manny forward)"
    assert abs(wt[0, rig.idx["pelvis"], 0]) < 15 and abs(wt[0, rig.idx["pelvis"], 1]) < 15      # root starts at origin
    assert wt[:, rig.idx["head"], 2].max() > 140 and wt[:, :, 2].min() > -3                   # nothing below the floor
    assert np.array(d["root_translation"])[:, 2].min() >= 0


@needs_gem
def test_jump_has_crouch_flight_landing(jump1):
    _, jump, s = jump1
    d = motion_dict(s, "ue5_manny")
    pel = np.array(d["root_translation"])[:, 2] + np.array(d["pelvis_translation"])[:, 2]
    stand = np.median(pel[:15])
    assert pel.min() < stand - 15                    # crouch
    assert pel.max() > stand + 5                     # lift
    flight = ~s.on60.any(1)
    assert flight.sum() >= 15 and pel[flight].max() > stand + 5     # the lift is in the pelvis...
    assert np.abs(np.array(d["root_translation"])[:, 2]).max() < 1e-6  # ...and the root stays on the floor


@needs_gem
def test_feet_locked_in_contact(jump1, rig):
    _, _, s = jump1
    assert s.stats["foot_slide_cm_after"] < 0.5
    assert s.stats["foot_floor_error_cm"] < 1.5


@needs_gem
@pytest.mark.parametrize("clip", JUMPS)
def test_direction_error_below_5_degrees(clip, rig, prof):
    if not (OUT / clip / "gem_world.npz").exists():
        pytest.skip("no GEM data")
    g, _, s = run_clip(clip, rig)
    mean, per = direction_error(motion_dict(s, "ue5_manny"), s, g.names, prof)
    print(f"{clip}: mean direction error {mean:.3f} deg")
    assert mean < 5.0
    assert max(per.values()) < 8.0


# --- analysis / math ---------------------------------------------------------------------------------

def test_slerp_and_swing():
    q = mu.mat_to_q(mu.rotz(0.8))
    assert np.allclose(mu.slerp(q, q, 0.3), q)
    a, b = np.array([1.0, 0, 0]), np.array([0, 1.0, 0])
    S = mu.swing_matrix(a, b)
    assert np.allclose(S @ a, b) and np.isclose(np.linalg.det(S), 1)
    S = mu.swing_matrix(a, -a)
    assert np.allclose(S @ a, -a)


def test_hysteresis_and_runs():
    x = np.array([0.9, 0.55, 0.45, 0.3, 0.5, 0.7, 0.55, 0.3])
    m = analysis.hysteresis(x, 0.6, 0.4)
    assert m.tolist() == [True, True, True, False, False, True, True, False]
    assert analysis.runs(m) == [(0, 3), (5, 7)]


# --- package reading ---------------------------------------------------------------------------------

def _package(tmp_path, manifest, files=("takes/action_01.mp4",)):
    p = tmp_path / "t.espeon"
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("manifest.json", json.dumps(manifest))
        for f in files:
            z.writestr(f, b"x")
    return p


def _manifest(**kw):
    m = {"format": "espeon-package", "version": "1.0", "performer": {"height_cm": 175}, "action": "jump",
         "takes": [{"id": "action_01", "role": "action", "file": "takes/action_01.mp4", "guidance": {"verdict": "ok"}},
                   {"id": "calibration", "role": "calibration", "file": "takes/calibration.mp4"}]}
    m.update(kw)
    return m


def test_package_reads_takes(tmp_path):
    c = load_capture(_package(tmp_path, _manifest(), ("takes/action_01.mp4", "takes/calibration.mp4")), tmp_path / "w")
    assert [t.id for t in c.actions] == ["action_01"] and c.calibration.id == "calibration"
    assert c.performer_height_cm == 175 and c.actions[0].guidance_verdict == "ok"


def test_package_rejects_newer_major_and_bad_format(tmp_path):
    with pytest.raises(ProcessorError, match="newer"):
        load_capture(_package(tmp_path, _manifest(version="2.0")), tmp_path / "w")
    with pytest.raises(ProcessorError, match="format"):
        load_capture(_package(tmp_path, _manifest(format="nope")), tmp_path / "w2")
    c = load_capture(_package(tmp_path, _manifest(version="1.7"), ("takes/action_01.mp4", "takes/calibration.mp4")), tmp_path / "w3")
    assert c.actions


def test_package_missing_file_and_bare_video(tmp_path):
    with pytest.raises(ProcessorError, match="missing"):
        load_capture(_package(tmp_path, _manifest()), tmp_path / "w")
    v = tmp_path / "clip.mp4"
    v.write_bytes(b"x")
    c = load_capture(v, tmp_path / "w")
    assert len(c.actions) == 1 and c.calibration is None


# --- ballistic flight ---------------------------------------------------------------------------------

def _synthetic_flight(air_frames=16, bump=11.0):
    F = 60
    z = np.full(F, 95.0)
    s, e = 20, 20 + air_frames
    u = (np.arange(s, e) - s) / air_frames
    z[s:e] += bump * 4 * u * (1 - u)
    prob = np.ones(F)
    prob[s:e] = 0.05
    return z, prob, ~(prob > 0.5), s, e


def test_ballistic_flight_apex_and_continuity():
    from espeon_processor.solve import ballistic_correct
    z, prob, flight, s, e = _synthetic_flight()
    delta, infos = ballistic_correct(z, prob, flight, 30.0)
    assert len(infos) == 1
    T = infos[0]["airtime_s"]
    assert 0.45 < T < 0.6
    want = 980 * T * T / 8
    new = z + delta
    apex = new[s:e].max() - 95.0
    assert apex > 0.75 * want and apex > 2 * 11     # far above the 11 cm measured, close to g T^2 / 8
    assert abs(new[0] - 95) < 1e-6 and abs(new[-1] - 95) < 1e-6
    vel = np.diff(new) * 30
    assert np.abs(np.diff(vel)).max() < 0.5 * 980 * T / 2   # no velocity step: the takeoff speed is reached over several frames


def test_ballistic_ignores_short_or_long_flights():
    from espeon_processor.solve import ballistic_correct
    z, prob, flight, *_ = _synthetic_flight(air_frames=3)
    d, infos = ballistic_correct(z, prob, flight, 30.0)
    assert not infos and np.abs(d).max() == 0
    z, prob, flight, *_ = _synthetic_flight(air_frames=40)
    d, infos = ballistic_correct(z, prob, flight, 30.0)
    assert not infos


# --- support-centre root motion -----------------------------------------------------------------------

def test_support_center_ignores_hip_sway_and_interpolates_flight():
    from espeon_processor.solve import support_center
    F = 100
    ank = np.zeros((F, 2, 2))
    ank[:, 0] = [-10, 0]
    ank[:, 1] = [10, 0]
    on = np.ones((F, 2), bool)
    on[40:60] = False                       # flight
    ank[60:, :, 1] = 30.0                   # lands 30 cm forward
    c = support_center(ank, on)
    assert np.abs(c[:38]).max() < 2.0       # in-place crouch (hips would sway back; feet do not move)
    assert abs(c[-1, 1] - 30) < 2.0
    v = np.diff(c[:, 1])
    assert np.abs(np.diff(v)).max() < 1.5   # no velocity steps
    assert np.all(np.diff(c[38:62, 1]) >= -1e-6)


@needs_gem
def test_root_xy_stays_put_before_takeoff(jump1):
    _, jump, s = jump1
    d = motion_dict(s, "ue5_manny")
    root = np.array(d["root_translation"])
    take = int((jump.takeoff / 30 - s.debug["window_start_s"]) * 60)
    assert np.ptp(root[:max(take - 3, 2), :2], axis=0).max() < 4.0


# ---- framing / camera height (issue #3); numbers mirror the app's GuidanceConfig.kt ----------------------------
from espeon_processor import framing  # noqa: E402
from espeon_processor.package import Take  # noqa: E402

RAW = Path(__file__).resolve().parents[2] / "testdata" / "raw"
PIXEL_PKG = Path(os.environ.get("ESPEON_PIXEL_PKG", ""))


def _person(head, feet, hip=None, n=90):
    """n identical frames of a person from head y to feet y (normalized), 33 landmarks x (x, y, visibility)."""
    L = np.full((n, 33, 3), 0.5)
    L[:, :, 2] = 1.0
    L[:, framing.HEAD, 1] = head
    L[:, framing.FEET, 1] = feet
    L[:, framing.HIPS, 1] = hip if hip is not None else (head + feet) / 2
    return L


def test_framing_height_boundaries():
    # headTop = head - 5% of body height, so a 0.4-wide head/feet span is ~0.42 of the frame
    for h, far, close in [(0.45, False, False), (0.55, False, False), (0.65, False, False), (0.33, True, False), (0.80, False, True)]:
        r = framing.analyze(_person(0.5 - h / 2, 0.5 + h / 2), fps=30)
        assert (r["too_far_fraction"] > 0.9) == far, (h, r)
        assert (r["too_close_fraction"] > 0.9) == close, (h, r)


def test_crouch_does_not_look_too_far():
    a, b = _person(0.23, 0.77, n=90), _person(0.35, 0.77, n=30)   # 1 s crouch after 3 s standing
    r = framing.analyze(np.concatenate([a, b, a]), fps=30)
    assert framing.framing_codes(r) == []


def test_camera_height_uses_signed_pitch():
    g = (0.09, 9.76, 1.03)        # Pixel 10, screen tilted up 6 degrees
    assert framing.camera_pitch_deg(g, front=True) == pytest.approx(6.0, abs=0.1)
    assert framing.camera_pitch_deg(g, front=False) == pytest.approx(-6.0, abs=0.1)
    lm = _person(0.28, 0.81, hip=0.58)
    vfov = framing.vertical_fov_deg(1253.5, 1920)
    assert vfov == pytest.approx(74.9, abs=0.2)
    ok = framing.analyze(lm, 30, framing.camera_pitch_deg(g, True), vfov)
    assert "camera_high" not in framing.framing_codes(ok) and "camera_low" not in framing.framing_codes(ok)
    wrong = framing.analyze(lm, 30, framing.camera_pitch_deg(g, False), 50.0)   # old back-camera sign
    assert "camera_high" in framing.framing_codes(wrong)
    low = framing.analyze(_person(0.1, 0.8, hip=0.25), 30)
    assert "camera_low" in framing.framing_codes(low)


def test_cropped_frames_are_not_judged():
    r = framing.analyze(_person(-0.1, 0.9), 30)
    assert r["cropped_fraction"] > 0.9 and framing.framing_codes(r) == []


def _landmarks_from_video(path, step):
    cv2 = pytest.importorskip("cv2")
    from espeon_processor.boxes import MODEL, _detect
    if not MODEL.exists():
        pytest.skip("pose model missing")
    cap, frames, i = cv2.VideoCapture(str(path)), [], 0
    fps = cap.get(cv2.CAP_PROP_FPS)
    while True:
        ok, f = cap.read()
        if not ok:
            break
        if i % step == 0:
            s = 640 / max(f.shape[:2])
            frames.append(cv2.cvtColor(cv2.resize(f, None, fx=s, fy=s), cv2.COLOR_BGR2RGB))
        i += 1
    out = []
    _detect(frames, fps=fps / step, landmarks_out=out)
    return np.stack(out), fps / step


@pytest.mark.skipif(not (PIXEL_PKG / "manifest.json").exists(), reason="set ESPEON_PIXEL_PKG to the unpacked Pixel 10 package")
def test_real_pixel_take_has_no_framing_warnings():
    man = json.loads((PIXEL_PKG / "manifest.json").read_text())
    t = next(x for x in man["takes"] if x["id"] == "action_02")
    take = Take(t["id"], "action", PIXEL_PKG / t["file"], info=t)
    pitch, vfov, have_imu = framing.take_camera_info(take)
    assert have_imu and pitch == pytest.approx(6.0, abs=1.0)
    lms, fps = _landmarks_from_video(take.path, 4)
    r = framing.analyze(lms, fps, pitch, vfov)
    assert framing.framing_codes(r) == [], r
    assert 0.40 <= r["standing_height_median"] <= 0.65


@pytest.mark.skipif(not (RAW / "Jump1.mp4").exists(), reason="testdata/raw not present")
def test_real_badly_framed_clip_still_warns():
    lms, fps = _landmarks_from_video(RAW / "Jump1.mp4", 4)
    r = framing.analyze(lms, fps)
    assert r["cropped_fraction"] > 0.1      # head/feet leave the frame: body_cropped fires in the pipeline


# --- mirrored (selfie-style) recordings: camera.mirrored -> horizontal flip before any analysis -------------------
def test_take_mirrored_flag():
    from espeon_processor.package import take_mirrored
    assert take_mirrored({"camera": {"lens_facing": "front", "mirrored": True}}) is True
    assert take_mirrored({"camera": {"lens_facing": "front", "mirrored": False}}) is False
    # old packages (no field), no camera block, bare video: not mirrored
    assert take_mirrored({"camera": {"lens_facing": "front"}}) is False
    assert take_mirrored({}) is False and take_mirrored(None) is False


def test_video_filters_flip_only_when_mirrored():
    from espeon_processor.video import video_filters
    assert "hflip" not in video_filters(False)
    assert video_filters(True)[0] == "hflip"
    assert video_filters(True, 30)[0] == "fps=30"


@pytest.mark.skipif(__import__("shutil").which("ffmpeg") is None, reason="ffmpeg not available")
def test_prepare_clip_flips_mirrored(tmp_path):
    import subprocess
    from espeon_processor.video import prepare_clip
    src = tmp_path / "src.mp4"   # left half white, right half black
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                    "color=c=white:s=64x64:d=0.5:r=10,drawbox=x=32:y=0:w=32:h=64:color=black:t=fill",
                    "-pix_fmt", "yuv420p", str(src)], check=True)

    def left_right(p):
        raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(p), "-frames:v", "1",
                              "-f", "rawvideo", "-pix_fmt", "gray", "-"], check=True, capture_output=True).stdout
        a = np.frombuffer(raw, np.uint8).reshape(64, 64)
        return a[:, :16].mean(), a[:, 48:].mean()

    l, r = left_right(prepare_clip(src, tmp_path / "plain"))
    assert l > 200 and r < 50
    l, r = left_right(prepare_clip(src, tmp_path / "flipped", mirrored=True))
    assert l < 50 and r > 200


# --- multi-jump takes, micro-flights, generic action ----------------------------------------------------------

def _synthetic_gem(hips_y, contact_on, fps=30.0, extra=None):
    """Minimal Gem whose hips follow hips_y (m) and whose two feet are planted where contact_on is True.
    Other joints sit on the hips so only the hips move; `extra(J)` may animate more joints."""
    names = ["Hips", "Head", "LeftHand", "RightHand", "LeftFoot", "RightFoot", "LeftShin", "RightShin", "LeftForeArm",
             "RightForeArm"]
    T = len(hips_y)
    J = np.zeros((T, len(names), 3))
    J[:, :, 1] = np.asarray(hips_y)[:, None]
    if extra:
        extra(J)
    c = np.where(np.asarray(contact_on), 0.95, 0.02)[:, None] * np.ones((1, 6))
    return analysis.Gem(fps, names, np.zeros(len(names), int), J, np.tile(np.eye(3), (T, len(names), 1, 1)), c,
                        np.zeros((len(names), 3)), np.tile(np.eye(3), (len(names), 1, 1)))


def _add_jump(h, on, takeoff, air, rise, crouch_frames=12, crouch=0.25):
    """Write a crouch -> flight -> landing dip -> settle jump into hips curve `h` and contact mask `on` (in place)."""
    c0 = takeoff - crouch_frames
    base = h[c0 - 1]
    h[c0:takeoff] = base - crouch * np.sin(np.linspace(0, np.pi / 2, crouch_frames)) ** 2
    u = np.linspace(0, 1, air)
    h[takeoff:takeoff + air] = h[takeoff - 1] + 4 * rise * u * (1 - u) + 0.0
    on[takeoff:takeoff + air] = False
    land = takeoff + air
    n = 18
    h[land:land + n] = base - 0.12 * np.sin(np.linspace(0, np.pi, n)) * np.exp(-np.linspace(0, 3, n))
    h[land + n:] = base


def _curve(T=300, three=True, micro=True):
    h = np.full(T, 1.0)
    on = np.ones(T, bool)
    _add_jump(h, on, 45, 14, 0.25)               # 0.47 s, medium
    if three:
        _add_jump(h, on, 120, 20, 0.35)          # 0.67 s: the best one
        _add_jump(h, on, 190, 10, 0.15)          # 0.33 s, small
    if micro:
        on[262:268] = False                      # 0.2 s foot flicker in a crouch: hips barely move
        h[262:268] += 0.01
    return h, on


def test_three_jumps_keep_best_and_window_stays_between_neighbours():
    h, on = _curve()
    g = _synthetic_gem(h, on)
    jumps = analysis.detect_jumps(g)
    assert [j.takeoff for j in jumps] == [45, 120, 190]          # the micro-flight is not a jump
    best = analysis.best_jump(jumps)
    assert best.takeoff == 120 and best.flight_s == pytest.approx(20 / 30)
    a, b, det = analysis.action_window(g, best, jumps=jumps)
    assert jumps[0].landing <= a                                  # does not contain the first jump's flight
    assert a < best.crouch_onset and b > best.landing
    assert b <= jumps[2].crouch_onset + 1                          # nor the third jump's crouch
    assert not ((on[a:b] == False).sum() > best.flight_s * 30 + 1)  # only one flight in the window
    # the same selection when only one jump is in the take: unchanged window rules, no neighbours
    assert analysis.detect_jump(g).takeoff == 120


def test_single_jump_take_is_unchanged_by_neighbour_logic():
    h, on = _curve(three=False, micro=False)
    g = _synthetic_gem(h, on)
    jumps = analysis.detect_jumps(g)
    assert len(jumps) == 1
    assert analysis.action_window(g, jumps[0], jumps=jumps)[:2] == analysis.action_window(g, jumps[0])[:2]
    a, b, det = analysis.action_window(g, jumps[0])
    assert "end_cut_by_next_jump" not in det and "start_after_previous_jump" not in det


def test_micro_flight_is_not_a_jump():
    h, on = _curve(three=False, micro=True)
    g = _synthetic_gem(h, on)
    assert len(analysis.detect_jumps(g)) == 1
    h2 = np.full(300, 1.0)
    on2 = np.ones(300, bool)
    on2[100:108] = False                          # 0.27 s but the hips do not rise (tracking flicker)
    h2[100:108] += 0.015
    assert not analysis.detect_jump(_synthetic_gem(h2, on2)).found
    on3 = np.ones(300, bool)
    on3[100:106] = False                          # 0.2 s, even with a real rise: too short
    h3 = np.full(300, 1.0)
    h3[100:106] += 0.1
    assert not analysis.detect_jump(_synthetic_gem(h3, on3)).found


def test_ballistic_rejects_micro_flights():
    from espeon_processor.solve import ballistic_correct
    F = 200
    z = np.full(F, 90.0)
    prob = np.ones(F)
    prob[100:106] = 0.05                          # 0.2 s flicker
    z[100:106] += 2.0                             # 2 cm
    d, infos = ballistic_correct(z, prob, ~(prob > 0.5), 30.0)
    assert not infos and np.abs(d).max() == 0
    prob[100:110] = 0.05                          # 0.33 s (long enough) but only 2 cm of rise
    z[100:110] = 90.0 + 2.0
    d, infos = ballistic_correct(z, prob, ~(prob > 0.5), 30.0)
    assert not infos


def test_idle_trim_cuts_only_idle_ends():
    T = 240
    t = np.arange(T) / 30.0
    sway = np.where((t > 2.0) & (t < 5.0), 0.5 * np.sin(2 * np.pi * 1.0 * t), 0.0)

    def extra(J):
        J[:, 2, 0] = sway                         # a hand waves for 3 s only
    g = _synthetic_gem(np.full(T, 1.0), np.ones(T, bool), extra=extra)
    a, b, det = analysis.idle_trim(g)
    assert 1.4 < a / 30 < 2.0 and 5.0 < b / 30 < 5.6
    assert det["mode"] == "idle_trim"
    g2 = _synthetic_gem(np.full(T, 1.0), np.ones(T, bool))
    assert analysis.idle_trim(g2)[:2] == (0, T)   # nothing moves: keep everything, no error


def test_other_action_scoring_ignores_jumps():
    from espeon_processor.pipeline import score_take
    h, on = _curve()
    g = _synthetic_gem(h, on)
    meta = {"landmarks": {"mean_visibility": 0.9, "tracked_fraction": 1.0, "cropped_fraction": 0.0}}
    _, jumps, sc = score_take(g, meta, "ok", "other")
    assert jumps == [] and sc["jump_detected"] is False
    _, jumps_j, sc_j = score_take(g, meta, "ok", "jump")
    assert len(jumps_j) == 3 and sc["score"] >= sc_j["score"] - 1e-9
    flat = _synthetic_gem(np.full(300, 1.0), np.ones(300, bool))
    assert score_take(flat, meta, "ok", "other")[2]["score"] > score_take(flat, meta, "ok", "jump")[2]["score"]


def test_multiple_jumps_warning_text():
    from espeon_processor.pipeline import make_warning
    w = make_warning("multiple_jumps", n=3)
    assert w["severity"] == "info" and w["message"] == "Found 3 jumps; kept the best one." and w["suggestion"] == "Do one jump per take."
