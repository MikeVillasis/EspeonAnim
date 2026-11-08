"""GEM-X reconstruction stage: video -> gem_world.npz (+ MediaPipe landmark statistics), cached by input hash.

The heavy model runs in a child process (`espeon_processor.run_gem`, which swaps YOLOX for MediaPipe boxes)
so the parent can report progress, free GPU memory afterwards and survive a native crash.
"""
from __future__ import annotations
import hashlib, json, os, shutil, subprocess, sys, time
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "configs" / "body_video_native77.yaml"
SOMA_ASSETS = ROOT / "models" / "soma"
CACHE_VERSION = "gemx-native77-mp-v1"      # bump when anything affecting gem_world.npz changes


class ProcessorError(RuntimeError):
    pass


def file_key(path, chunk=1 << 22):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while b := f.read(chunk):
            h.update(b)
    return h.hexdigest()[:20] + "-" + CACHE_VERSION


def _frames_estimate(video):
    from .video import probe
    st = probe(video)
    n, (a, b) = st.get("nb_frames"), (st.get("r_frame_rate") or "30/1").split("/")
    fps = float(a) / float(b)
    return (int(n) if n and str(n).isdigit() else 0), fps, int(st["width"]), int(st["height"])


def _luma(video, n=8):
    import cv2
    cap = cv2.VideoCapture(str(video))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    vals = []
    for i in np.linspace(0, total - 1, n).astype(int):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(i))
        ok, fr = cap.read()
        if ok:
            vals.append(float(cv2.cvtColor(fr, cv2.COLOR_BGR2GRAY).mean()))
    cap.release()
    return float(np.median(vals)) if vals else float("nan")


def landmark_stats(npz_path, frame_range=None):
    """Visibility / tracking / framing statistics from the MediaPipe landmarks saved during GEM
    (optionally restricted to frames [a, b) of the 30 fps reconstruction)."""
    d = np.load(npz_path)
    L, obs = d["landmarks"], d["observed"].astype(bool)
    if frame_range:
        L, obs = L[frame_range[0]:frame_range[1]], obs[frame_range[0]:frame_range[1]]
    det = ~np.isnan(L[:, :, 0]).all(1)
    body = [0] + list(range(11, 33))
    vis = L[:, body, 2]
    mean_vis = float(np.nanmean(vis[det])) if det.any() else 0.0
    feet, head = [27, 28, 29, 30, 31, 32], [0, 7, 8]
    margin = 0.01
    xy = L[:, :, :2]
    out_of = lambda idx: ((xy[:, idx] < margin) | (xy[:, idx] > 1 - margin)).any(2).any(1) & det
    cropped_feet = out_of(feet) | ((xy[:, feet, 1] > 1 - margin).any(1) & det)
    cropped_head = (xy[:, head, 1] < margin).any(1) & det
    ys = xy[det][:, body, 1]
    height_frac = float(np.median(np.nanmax(ys, 1) - np.nanmin(ys, 1))) if det.any() else 0.0
    return {"tracked_fraction": float(det.mean()), "mean_visibility": mean_vis,
            "cropped_fraction": float((cropped_feet | cropped_head)[det].mean()) if det.any() else 0.0,
            "person_height_fraction": height_frac, "frames": int(len(L))}


def reconstruct(video, cache_root, device="cuda", keep_intermediate=False, progress=None, label=""):
    """Run (or fetch from cache) the reconstruction for `video`. Returns (gem_world.npz path, meta dict)."""
    from .video import prepare_clip
    progress = progress or (lambda f, m: None)
    key = file_key(video)
    cdir = Path(cache_root) / key
    npz, meta_p = cdir / "gem_world.npz", cdir / "meta.json"
    if npz.exists() and meta_p.exists():
        progress(1.0, f"{label}: using cached reconstruction")
        meta = json.loads(meta_p.read_text())
        meta["cached"] = True
        return npz, meta
    cdir.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    progress(0.0, f"{label}: preparing video")
    prepared = prepare_clip(video, cdir)
    nframes, fps, w, h = _frames_estimate(prepared)
    dur30 = (nframes / fps) if nframes else 8.0
    motion_pt, lm_npz, log = cdir / "motion.pt", cdir / "landmarks.npz", cdir / "run.log"
    env = dict(os.environ, ESPEON_LANDMARKS_OUT=str(lm_npz), PYTHONPATH=str(ROOT), PYTHONUNBUFFERED="1")
    cmd = [sys.executable, "-m", "espeon_processor.run_gem", "motion-video", "--task", "body", "--input", str(prepared),
           "--config", str(CONFIG), "--device", device, "--camera-mode", "static", "--postprocess",
           "--soma-assets", str(SOMA_ASSETS), "--output", str(motion_pt)]
    est = 25.0 + 0.34 * dur30 * 30          # rough wall time, only used to animate progress
    progress(0.05, f"{label}: reconstructing 3D motion")
    with open(log, "wb") as lf:
        proc = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=lf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        while proc.poll() is None:
            time.sleep(1.0)
            progress(0.05 + 0.8 * min(0.98, (time.time() - t0) / est), f"{label}: reconstructing 3D motion")
    if proc.returncode != 0 or not motion_pt.exists():
        tail = log.read_text(errors="replace")[-1500:]
        raise ProcessorError(f"GEM-X reconstruction failed for {Path(video).name} (exit {proc.returncode}):\n{tail}")
    progress(0.9, f"{label}: exporting skeleton")
    r = subprocess.run([sys.executable, "-m", "espeon_processor.gem_export", str(motion_pt), str(npz), "--device", device],
                       cwd=ROOT, env=env, capture_output=True, text=True)
    if r.returncode != 0 or not npz.exists():
        raise ProcessorError(f"GEM export failed: {r.stderr[-1500:]}")
    meta = {"source_fps": fps, "width": w, "height": h, "frames_source": nframes, "mean_luma": _luma(prepared),
            "reconstruct_s": round(time.time() - t0, 1), "cached": False}
    meta["landmarks"] = landmark_stats(lm_npz) if lm_npz.exists() else None
    meta_p.write_text(json.dumps(meta, indent=1))
    if not keep_intermediate:
        for p in (prepared, motion_pt):
            p.unlink(missing_ok=True)
    progress(1.0, f"{label}: reconstruction done")
    return npz, meta
