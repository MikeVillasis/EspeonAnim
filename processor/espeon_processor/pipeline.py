"""EspeonAnim processor main pipeline: capture -> GEM-X -> take selection -> calibration -> trim -> retarget ->
cleanup -> motion.json / report.json / debug PNGs."""
from __future__ import annotations
import json, shutil, sys, time
from pathlib import Path
import numpy as np
from . import analysis
from .analysis import Gem
from .gem_stage import ProcessorError, landmark_stats, reconstruct
from .motion_io import motion_dict, validate_motion, write_motion
from .package import load_capture
from .skeleton import Profile, Rig
from .solve import manny_leg_length, solve
from .validate import direction_error

PROCESSOR_VERSION = "0.1.0"
ASSUMED_HEIGHT_CM = 170.0

# code -> (severity, message, suggestion). severity: info | warn | block (block => quality "poor")
WARNINGS = {
    "no_jump_detected": ("block", "No jump (both feet leaving the ground) was found in the chosen take; the whole clip was used.",
                         "Record again: stand still, crouch, jump clearly off the ground, land and stand still."),
    "calibration_missing": ("warn", "No calibration clip: the performer's proportions were estimated from the action take.",
                            "Record the short A-pose turn calibration clip for a better fit."),
    "low_keypoint_confidence": ("warn", "Body landmarks were detected with low confidence.",
                                "Use better light and clothes that contrast with the background."),
    "body_cropped": ("warn", "Head or feet leave the frame during the jump.",
                     "Step back until there's space above your head and below your feet."),
    "no_person": ("block", "The performer was not tracked in most frames.", "Step into frame."),
    "low_light": ("warn", "The video is dark, which hurts pose estimation.", "Turn on more lights or face a window."),
    "low_fps": ("warn", "The video frame rate is below 30 fps.", "Use 60 fps if your phone supports it."),
    "too_far": ("warn", "The performer is small in the frame.", "Move to fill about 60-80% of the frame height."),
    "too_close": ("warn", "The performer fills almost the whole frame.", "Move to fill about 60-80% of the frame height."),
    "foot_sliding": ("warn", "Feet slid noticeably during ground contact before cleanup (fixed by foot locking).",
                     "Good light and a plain floor help the tracker; check the result near landings."),
    "scale_mismatch": ("warn", "Leg length measured in the action take differs from the calibration clip.",
                       "Keep the same distance to the camera for the calibration and action clips."),
    "performer_height_mismatch": ("warn", "Reconstructed height differs a lot from the declared performer height.",
                                  "Check the height entered in the app; a wrong camera distance also skews it."),
    "trim_fallback": ("warn", "The action window could not be located precisely; a fallback was used.",
                      "Make sure the whole jump, including the landing, is in the video."),
    "weak_jump": ("warn", "The jump is small, so the reconstructed airtime and height may be inaccurate.",
                  "Jump higher and with a clear crouch beforehand."),
}


def make_warning(code, **over):
    sev, msg, sug = WARNINGS[code]
    return {"code": code, "message": over.get("message", msg), "suggestion": over.get("suggestion", sug), "severity": sev}


class Progress:
    """JSON-lines progress events; `emit` is called with one dict per event."""

    def __init__(self, emit=None):
        self.emit = emit or (lambda o: None)
        self.last = 0.0

    def __call__(self, stage, progress, message):
        self.last = max(self.last, min(1.0, float(progress)))
        self.emit({"event": "progress", "stage": stage, "progress": round(self.last, 4), "message": message})

    def sub(self, stage, lo, hi):
        return lambda f, m: self(stage, lo + (hi - lo) * f, m)


def default_device():
    try:
        import torch
        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


GUIDANCE_SCORE = {"ok": 1.0, "warn": 0.6, "poor": 0.2, None: 0.7}


def score_take(gem: Gem, meta: dict, verdict):
    """Score one action take and report the components."""
    jump = analysis.detect_jump(gem)
    lm = meta.get("landmarks") or {}
    vis = lm.get("mean_visibility", 0.5)
    tracked = lm.get("tracked_fraction", 1.0)
    cropped = lm.get("cropped_fraction", 0.0)
    jump_s = 0.0 if not jump.found else 0.6 + 0.4 * min(1.0, jump.hips_rise_m / 0.15)
    guid = GUIDANCE_SCORE.get(verdict, 0.7)
    score = 0.4 * jump_s + 0.2 * vis + 0.2 * tracked + 0.15 * guid + 0.05 * (1 - cropped) - 0.3 * cropped
    on, _ = analysis.foot_contacts(gem.contact)
    lo = max(0, int(jump.landing) - 5) if jump.found else 0
    flick = int((np.diff(on[lo:lo + int(gem.fps)].astype(int), axis=0) != 0).sum())
    return jump, {"landing_contact_flickers": flick, "score": round(float(score), 4), "jump_detected": bool(jump.found),
                  "jump_hips_rise_m": round(float(jump.hips_rise_m), 3), "flight_s": round(float(jump.flight_s), 3),
                  "crouch_depth_m": round(float(jump.crouch_depth_m), 3), "mean_landmark_visibility": round(vis, 3),
                  "tracked_fraction": round(tracked, 3), "cropped_fraction": round(cropped, 3),
                  "guidance_verdict": verdict}


def _round(d, n=4):
    return {k: (round(float(v), n) if isinstance(v, (float, np.floating)) else v) for k, v in d.items()}


def process(input_path, out_dir, skeleton="ue5_manny", device=None, keep_intermediate=False, emit=None, ballistic=True):
    """Run the whole pipeline. Returns {"motion": path, "report": path}. Raises ProcessorError on failure."""
    from .debug_plots import contact_sheet, height_plot, root_xy_plot
    prog = Progress(emit)
    t_start = time.time()
    timings = {}
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    cache, work, dbg = out_dir / ".cache", out_dir / ".work", out_dir / "debug"
    dbg.mkdir(exist_ok=True)
    device = device or default_device()

    prog("extract", 0.0, "Reading input")
    cap = load_capture(input_path, work)
    try:
        profile = Profile.load(skeleton)
    except FileNotFoundError:
        raise ProcessorError(f"unknown skeleton profile {skeleton!r}")
    rig = Rig(profile)
    takes = ([cap.calibration] if cap.calibration else []) + list(cap.actions)
    prog("extract", 0.02, f"{len(cap.actions)} action take(s)" + (", 1 calibration take" if cap.calibration else ""))

    # --- reconstruct every take ---------------------------------------------------------------
    t0 = time.time()
    gems, metas = {}, {}
    span = 0.83 / len(takes)
    for k, t in enumerate(takes):
        lo = 0.02 + k * span
        npz, meta = reconstruct(t.path, cache, device, keep_intermediate, prog.sub("reconstruct", lo, lo + span), t.id)
        gems[t.id], metas[t.id] = Gem.load(npz), meta
        meta["landmarks_file"] = str(Path(npz).with_name("landmarks.npz"))
    timings["reconstruct_s"] = round(time.time() - t0, 1)

    # --- take selection -----------------------------------------------------------------------
    prog("analyze", 0.86, "Scoring takes")
    scored = []
    for t in cap.actions:
        jump, sc = score_take(gems[t.id], metas[t.id], t.guidance_verdict)
        sc["id"] = t.id
        scored.append((sc["score"], t, jump, sc))
    scored.sort(key=lambda x: -x[0])
    tie = [x for x in scored if x[0] >= scored[0][0] - 0.02]
    selection = {"reason": "highest score"}
    if len(tie) > 1:
        best = max(tie, key=lambda x: x[3]["flight_s"] - 0.03 * x[3]["landing_contact_flickers"])
        selection = {"reason": f"scores within 0.02 for {[x[1].id for x in tie]}; preferred larger airtime and fewer landing "
                               f"contact flickers (airtime {best[3]['flight_s']} s, {best[3]['landing_contact_flickers']} flickers)"}
        scored.remove(best)
        scored.insert(0, best)
    _, take, jump, take_score = scored[0]
    gem, meta = gems[take.id], metas[take.id]
    warnings, assumptions = [], []

    # --- calibration / body proportions ---------------------------------------------------------
    body_act = analysis.measure_body(gem)
    manny_leg = manny_leg_length(rig)
    if cap.calibration:
        body = analysis.measure_body(gems[cap.calibration.id])
        calib_info = {"source": "calibration", **_round(body)}
        ratio = body_act["leg_length_m"] / body["leg_length_m"]
        if abs(ratio - 1) > 0.10:
            warnings.append(make_warning("scale_mismatch", message=f"Leg length differs by {100 * (ratio - 1):+.0f}% between the "
                                         "action take and the calibration clip."))
    else:
        body = body_act
        calib_info = {"source": "action_take", **_round(body)}
        warnings.append(make_warning("calibration_missing"))
        assumptions.append("no calibration clip: performer proportions estimated from the standing frames of the action take")
    scale = manny_leg / (body["leg_length_m"] * 100.0)
    if cap.performer_height_cm:
        est_h = body["height_m"] * 100
        calib_info["declared_height_cm"] = cap.performer_height_cm
        if abs(est_h / cap.performer_height_cm - 1) > 0.2:
            warnings.append(make_warning("performer_height_mismatch", message=f"Reconstructed height {est_h:.0f} cm vs declared "
                                         f"{cap.performer_height_cm:.0f} cm."))
    else:
        assumptions.append(f"performer height assumed {ASSUMED_HEIGHT_CM:.0f} cm (not in manifest); the leg-length based scale does not depend on it")
    assumptions.append(f"root/pelvis translation scaled x{scale:.3f} (Manny leg {manny_leg:.1f} cm / performer leg {body['leg_length_m'] * 100:.1f} cm)")
    assumptions.append("camera intrinsics and IMU data are not used; GEM-X estimates the camera itself (static-camera mode)")
    assumptions.extend(cap.notes)

    # --- trim ---------------------------------------------------------------------------------------
    win = analysis.action_window(gem, jump)
    trim = {}
    if win is None:
        a, b = 0, gem.T
        warnings.append(make_warning("no_jump_detected"))
        trim["fallback"] = "whole clip"
    else:
        a, b, trim = win
        trim = dict(trim)
        if "recovery" in trim:
            warnings.append(make_warning("trim_fallback", message="Stable standing after landing was not found: " + trim["recovery"]))
        if jump.hips_rise_m < 0.06:
            warnings.append(make_warning("weak_jump"))
        for n in jump.notes:
            trim.setdefault("notes", []).append(n)
    fps_g = gem.fps
    trim.update({"start_s": round(a / fps_g, 3), "end_s": round(b / fps_g, 3), "start_frame": int(a), "end_frame": int(b)})
    if jump.found:
        trim.update({"takeoff_s": round(jump.takeoff / fps_g, 3), "landing_s": round(jump.landing / fps_g, 3),
                     "crouch_onset_s": round(jump.crouch_onset / fps_g, 3)})

    # --- input-quality warnings ----------------------------------------------------------------------
    lm = meta.get("landmarks") or {}
    if lm:
        if lm["tracked_fraction"] < 0.6:
            warnings.append(make_warning("no_person"))
        lmw = landmark_stats(meta["landmarks_file"], (a, b)) if Path(meta["landmarks_file"]).exists() else lm
        cf = lmw["cropped_fraction"]
        if cf > 0.1:
            w = make_warning("body_cropped", message=f"Head or feet leave the frame in {100 * cf:.0f}% of the action frames; "
                             "the reconstruction there is partly guessed.")
            if cf > 0.5:
                w["severity"] = "block"
            warnings.append(w)
        if lm["mean_visibility"] < 0.6:
            warnings.append(make_warning("low_keypoint_confidence", message=f"Mean landmark visibility is {lm['mean_visibility']:.2f}."))
        if lm["person_height_fraction"] < 0.4:
            warnings.append(make_warning("too_far"))
        elif lm["person_height_fraction"] > 0.97 and cf <= 0.1:
            warnings.append(make_warning("too_close"))
    if meta.get("mean_luma", 255) < 70:
        warnings.append(make_warning("low_light", message=f"The video is dark (mean luma {meta['mean_luma']:.0f}/255)."))
    if meta.get("source_fps", 60) < 29.5:
        warnings.append(make_warning("low_fps"))
    for w in take.guidance_warnings:
        if w in WARNINGS and not any(x["code"] == w for x in warnings):
            warnings.append(make_warning(w))

    # --- retarget + cleanup ---------------------------------------------------------------------------
    prog("retarget", 0.90, "Retargeting to " + skeleton)
    t0 = time.time()
    solved = solve(gem, (a, b), rig, scale, ballistic=ballistic)
    timings["solve_s"] = round(time.time() - t0, 2)
    prog("cleanup", 0.95, "Cleanup done")
    if solved.stats["foot_slide_cm_before"] > 4.0:
        warnings.append(make_warning("foot_sliding", message=f"Feet slid {solved.stats['foot_slide_cm_before']:.1f} cm on average "
                                     "during ground contact before cleanup."))

    # --- write outputs ----------------------------------------------------------------------------------
    prog("export", 0.97, "Writing motion.json")
    md = validate_motion(motion_dict(solved, skeleton))
    motion_path, report_path = out_dir / "motion.json", out_dir / "report.json"
    write_motion(motion_path, md)
    mean_err, seg_err = direction_error(md, solved, gem.names, profile)
    contact_sheet(md, dbg / "contact_sheet.png", title=f"{take.id} ({Path(str(input_path)).name})")
    marks = {}
    if jump.found:
        t_win0 = a / fps_g
        marks = {"crouch": jump.crouch_onset / fps_g - t_win0, "takeoff": jump.takeoff / fps_g - t_win0,
                 "landing": jump.landing / fps_g - t_win0}
    root_xy_plot(md, dbg / "root_xy.png", title="root vs pelvis")
    height_plot(md, solved.on60, dbg / "height_contacts.png", title="pelvis height, sole heights and foot contacts", info=marks)

    quality = _quality(warnings, take_score)
    on_any = solved.on60.any(1)
    report = {
        "format": "espeon-report", "version": "1.0", "chosen_take": take.id, "quality": quality,
        "warnings": [{k: v for k, v in w.items() if k != "severity"} for w in warnings],
        "stats": {"frames_in": int(meta.get("frames_source") or gem.T), "frames_out": md["frame_count"],
                  "mean_keypoint_confidence": round(float(take_score["mean_landmark_visibility"]), 3),
                  "foot_contact_frames": int(on_any.sum()), "gem_frames": int(gem.T), "output_seconds": round(md["frame_count"] / 60, 3),
                  **_round({k: v for k, v in solved.stats.items() if k != "ballistic"}, 3)},
        "assumptions": assumptions,
        "details": {"processor_version": PROCESSOR_VERSION, "skeleton": skeleton, "device": device,
                    "take_scores": [s[3] for s in scored], "calibration": calib_info, "scale": round(scale, 4),
                    "trim": trim, "retarget_direction_error_deg": {"mean": round(mean_err, 3), **{k: round(v, 2) for k, v in seg_err.items()}},
                    "selection": selection, "ballistic_flights": solved.stats.get("ballistic", []), "ballistic_enabled": ballistic,
                    "timings": timings, "input": str(input_path)},
    }
    timings["total_s"] = round(time.time() - t_start, 1)
    report_path.write_text(json.dumps(report, indent=1), encoding="utf-8")
    if not keep_intermediate:
        shutil.rmtree(work, ignore_errors=True)
    prog("export", 1.0, "Done")
    return {"motion": str(motion_path.resolve()), "report": str(report_path.resolve()), "quality": quality, "chosen_take": take.id}


def _quality(warnings, take_score):
    sev = [w["severity"] for w in warnings]
    if "block" in sev or take_score["score"] < 0.4:
        return "poor"
    if "warn" in sev or take_score["score"] < 0.75:
        return "usable"
    return "good"
