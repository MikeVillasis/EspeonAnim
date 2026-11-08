"""motion.json (docs/formats.md section 2) writing, reading and validation."""
from __future__ import annotations
import json
from pathlib import Path
import numpy as np
from .skeleton import Profile, Rig

FORMAT = "espeon-motion"
VERSION = "1.0"


def motion_dict(solved, skeleton_name):
    rig = solved.rig
    return {
        "format": FORMAT, "version": VERSION, "skeleton": skeleton_name, "fps": int(round(solved.fps)),
        "frame_count": int(len(solved.local_q)), "bones": list(rig.bones),
        "root_translation": np.round(solved.root_t, 4).tolist(),
        "pelvis_translation": np.round(solved.pelvis_t, 4).tolist(),
        "local_rotations": np.round(solved.local_q, 7).tolist(),
    }


def write_motion(path, d):
    Path(path).write_text(json.dumps(d, separators=(",", ":")), encoding="utf-8")


def validate_motion(d):
    """Raise ValueError if d does not match the section 2 shape. Returns d."""
    def need(c, m):
        if not c:
            raise ValueError(m)
    need(d.get("format") == FORMAT, "format must be espeon-motion")
    major = int(str(d.get("version", "0")).split(".")[0])
    need(major == 1, f"unsupported motion version {d.get('version')}")
    need(d.get("fps") == 60, "fps must be 60")
    F, bones = d["frame_count"], d["bones"]
    need(bones[0] == "root" and len(set(bones)) == len(bones), "bones must start with root and be unique")
    for k in ("root_translation", "pelvis_translation"):
        a = np.array(d[k], float)
        need(a.shape == (F, 3), f"{k} shape {a.shape} != ({F}, 3)")
        need(np.isfinite(a).all(), f"{k} has non-finite values")
    q = np.array(d["local_rotations"], float)
    need(q.shape == (F, len(bones), 4), f"local_rotations shape {q.shape}")
    need(np.isfinite(q).all(), "local_rotations has non-finite values")
    need(np.abs(np.linalg.norm(q, axis=-1) - 1).max() < 1e-4, "rotations are not unit quaternions")
    return d


def fk_from_motion(d, profile: Profile | None = None):
    """(rig, world_q (F,B,4), world_pos (F,B,3)) computed from a motion dict with the skeleton profile."""
    profile = profile or Profile.load(d["skeleton"])
    rig = Rig(profile, d["bones"])
    wq, wt = rig.fk(np.array(d["local_rotations"]), np.array(d["root_translation"]), np.array(d["pelvis_translation"]))
    return rig, wq, wt
