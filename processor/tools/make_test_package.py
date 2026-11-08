"""Build a test .espeon package from the raw test clips (docs/formats.md section 1).

    python tools/make_test_package.py [--raw ../testdata/raw] [--out out/test_package.espeon]

Jump1..4 become action takes action_01..04, APoseTurn the calibration take.
"""
from __future__ import annotations
import argparse, json, subprocess, zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def probe(p):
    r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                        "stream=width,height,r_frame_rate:stream_side_data=rotation:format=duration", "-of", "json", str(p)],
                       check=True, capture_output=True, text=True)
    j = json.loads(r.stdout)
    st = j["streams"][0]
    a, b = st["r_frame_rate"].split("/")
    rot = int(round(float((st.get("side_data_list") or [{}])[0].get("rotation", 0)))) % 360
    return {"fps": round(float(a) / float(b), 2), "width": st["width"], "height": st["height"],
            "rotation_deg": rot, "duration_s": round(float(j["format"]["duration"]), 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default=str(ROOT.parent / "testdata" / "raw"))
    ap.add_argument("--out", default=str(ROOT / "out" / "test_package.espeon"))
    ap.add_argument("--height-cm", type=float, default=None, help="optional performer height for the manifest")
    a = ap.parse_args()
    raw = Path(a.raw)
    takes, files = [], []
    for i, name in enumerate(["Jump1", "Jump2", "Jump3", "Jump4"], 1):
        tid = f"action_{i:02d}"
        takes.append({"id": tid, "role": "action", "file": f"takes/{tid}.mp4", **probe(raw / f"{name}.mp4"),
                      "guidance": {"verdict": "warn", "warnings": ["low_light"]}})
        files.append((raw / f"{name}.mp4", f"takes/{tid}.mp4"))
    takes.append({"id": "calibration", "role": "calibration", "file": "takes/calibration.mp4", **probe(raw / "APoseTurn.mp4")})
    files.append((raw / "APoseTurn.mp4", "takes/calibration.mp4"))
    manifest = {"format": "espeon-package", "version": "1.0",
                "created_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "app": {"name": "EspeonAnim", "version": "0.1.0", "platform": "android"},
                "device": {"manufacturer": "test", "model": "raw test clips", "os_version": "0"},
                "performer": ({"height_cm": a.height_cm} if a.height_cm else {}), "action": "jump", "takes": takes}
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:      # mp4 is already compressed
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        for src, dst in files:
            z.write(src, dst)
    print(out, f"{out.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
