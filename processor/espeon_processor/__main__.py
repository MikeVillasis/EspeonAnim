"""EspeonAnim processor command line (docs/formats.md section 6).

    python -m espeon_processor process --input <file.espeon|video> --out <dir> [--skeleton ue5_manny] [--device cuda|cpu]
    python -m espeon_processor doctor

stdout carries only JSON lines (progress / done / error); everything else goes to stderr.
"""
from __future__ import annotations
import argparse, json, platform, shutil, sys, traceback
from pathlib import Path


def _doctor():
    from . import gem_stage, skeleton
    info = {"python": platform.python_version(), "platform": platform.platform(), "ok": True, "problems": []}
    root = gem_stage.ROOT
    try:
        import torch
        info["torch"] = torch.__version__
        info["cuda_available"] = bool(torch.cuda.is_available())
        if torch.cuda.is_available():
            info["gpu"] = torch.cuda.get_device_name(0)
            info["gpu_memory_gb"] = round(torch.cuda.get_device_properties(0).total_memory / 2**30, 1)
        else:
            info["problems"].append("CUDA not available: processing will use the (slow) CPU fallback")
    except Exception as e:  # noqa: BLE001
        info["problems"].append(f"torch import failed: {e}")
    for mod in ("gem", "mediapipe", "cv2", "scipy", "matplotlib"):
        try:
            __import__(mod)
            info[mod] = "ok"
        except Exception as e:  # noqa: BLE001
            info[mod] = f"missing ({e.__class__.__name__})"
            info["problems"].append(f"python module {mod} not importable")
    weights = {
        "gem body": root / "models/gem-x/body_s240000.ckpt", "gem vitpose": root / "models/gem-x/vitpose.pth",
        "gem motion": root / "models/gem-x/motion_native77_e774_s292175.ckpt", "gem stats": root / "models/gem-x/motion_stats_raw.npz",
        "soma": root / "models/soma/SOMA_neutral.npz", "mediapipe pose": root / "models/pose_landmarker_heavy.task",
    }
    info["weights"] = {k: v.exists() for k, v in weights.items()}
    info["problems"] += [f"weights missing: {k} ({v})" for k, v in weights.items() if not v.exists()]
    for exe in ("ffmpeg", "ffprobe"):
        info[exe] = shutil.which(exe) or None
        if not info[exe]:
            info["problems"].append(f"{exe} not on PATH")
    info["skeletons"] = sorted(p.stem for p in skeleton.SKELETON_DIR.glob("*.json"))
    info["ok"] = not [p for p in info["problems"] if "CPU fallback" not in p]
    return info


def main(argv=None):
    ap = argparse.ArgumentParser(prog="espeon_processor")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("process", help="turn a .espeon package or video into motion.json + report.json")
    p.add_argument("--input", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--skeleton", default="ue5_manny")
    p.add_argument("--device", choices=["cuda", "cpu"], default=None)
    p.add_argument("--keep-intermediate", action="store_true", help="keep prepared video, motion.pt and the unpacked package")
    p.add_argument("--no-ballistic", action="store_true", help="keep the reconstructed jump height instead of a ballistic arc")
    p.add_argument("--action", choices=["jump", "other"], default=None, help="override the manifest's action (bare videos default to jump)")
    sub.add_parser("doctor", help="check the environment and print a JSON summary")
    args = ap.parse_args(argv)

    out = sys.stdout
    sys.stdout = sys.stderr            # stray prints must not corrupt the JSON-lines stream

    def emit(obj):
        out.write(json.dumps(obj) + "\n")
        out.flush()

    if args.cmd == "doctor":
        info = _doctor()
        emit(info)
        return 0 if info["ok"] else 1
    try:
        from .pipeline import process
        from .gem_stage import ProcessorError
        res = process(args.input, args.out, args.skeleton, args.device, args.keep_intermediate, emit, not args.no_ballistic, args.action)
        emit({"event": "done", "motion": res["motion"], "report": res["report"]})
        return 0
    except Exception as e:  # noqa: BLE001
        traceback.print_exc(file=sys.stderr)
        emit({"event": "error", "message": str(e) or e.__class__.__name__})
        return 1


if __name__ == "__main__":
    sys.exit(main())
