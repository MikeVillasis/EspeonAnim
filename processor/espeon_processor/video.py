"""ffmpeg clip normalization: upright (rotation baked in), H.264 yuv420p."""
from __future__ import annotations
import json, subprocess
from pathlib import Path

OUT_ROOT = Path(__file__).resolve().parents[1] / "out"


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                        "stream=width,height,r_frame_rate,nb_frames,codec_name:stream_side_data=rotation",
                        "-of", "json", str(path)], check=True, capture_output=True, text=True)
    return json.loads(r.stdout)["streams"][0]


def prepare_clip(src, out_dir=None, resample_fps=None, crf=16):
    """Bake rotation metadata into pixels (ffmpeg autorotate), H.264 yuv420p, no audio.

    Keeps native fps unless resample_fps is given (GEM-X resamples to 30 fps itself, so
    the default of keeping native fps is fine). Returns path of <out_dir>/<clip>/prepared.mp4.
    """
    src = Path(src)
    out_dir = Path(out_dir) if out_dir else OUT_ROOT / src.stem
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / ("prepared.mp4" if resample_fps is None else f"prepared_{resample_fps}fps.mp4")
    vf = ["pad=ceil(iw/2)*2:ceil(ih/2)*2"]
    if resample_fps:
        vf.insert(0, f"fps={resample_fps}")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", str(src),
           "-map", "0:v:0", "-vf", ",".join(vf), "-c:v", "libx264", "-crf", str(crf),
           "-pix_fmt", "yuv420p", "-an", "-metadata:s:v:0", "rotate=0", "-movflags", "+faststart", str(dst)]
    subprocess.run(cmd, check=True)
    return dst


if __name__ == "__main__":
    import sys
    p = prepare_clip(sys.argv[1], resample_fps=int(sys.argv[2]) if len(sys.argv) > 2 else None)
    print(p, probe(p))
