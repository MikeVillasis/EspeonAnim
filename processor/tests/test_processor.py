import json
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
