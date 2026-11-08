"""Skeleton profiles (dumped from Unreal) and a small forward-kinematics utility."""
from __future__ import annotations
import json
from pathlib import Path
import numpy as np
from . import mathutil as mu

SKELETON_DIR = Path(__file__).resolve().parent / "skeletons"

# Bones the processor drives, in motion.json order (parents always precede children).
DRIVEN_BONES = ["root", "pelvis", "spine_01", "spine_02", "spine_03", "spine_04", "spine_05",
                "neck_01", "neck_02", "head", "clavicle_l", "clavicle_r", "upperarm_l", "upperarm_r",
                "lowerarm_l", "lowerarm_r", "hand_l", "hand_r", "thigh_l", "thigh_r", "calf_l", "calf_r",
                "foot_l", "foot_r", "ball_l", "ball_r"]


class Profile:
    """Full skeleton profile: all bones with parent index and reference local/component transforms."""

    def __init__(self, data: dict):
        self.name = data["name"]
        self.bones = data["bones"]
        self.names = [b["name"] for b in self.bones]
        self.index = {n: i for i, n in enumerate(self.names)}
        self.parent = np.array([b["parent"] for b in self.bones], int)
        self.ref_t = np.array([b["ref_local"]["t"] for b in self.bones], np.float64)
        self.ref_q = mu.qnorm(np.array([b["ref_local"]["r"] for b in self.bones], np.float64))
        self.ref_comp_t = np.array([b["ref_component"]["t"] for b in self.bones], np.float64)
        self.ref_comp_q = mu.qnorm(np.array([b["ref_component"]["r"] for b in self.bones], np.float64))

    @classmethod
    def load(cls, name_or_path="ue5_manny"):
        p = Path(name_or_path)
        if not p.suffix:
            p = SKELETON_DIR / f"{name_or_path}.json"
        return cls(json.loads(p.read_text()))

    def fk_full(self):
        """Component-space transforms of the reference pose recomputed from local transforms."""
        n = len(self.names)
        cq = np.zeros((n, 4)); ct = np.zeros((n, 3))
        for i in range(n):
            p = self.parent[i]
            if p < 0:
                cq[i], ct[i] = self.ref_q[i], self.ref_t[i]
            else:
                cq[i] = mu.qmul(cq[p], self.ref_q[i])
                ct[i] = ct[p] + mu.qrot(cq[p], self.ref_t[i])
        return cq, ct


class Rig:
    """The driven subset of a profile with vectorised FK over frames."""

    def __init__(self, profile: Profile, bones=DRIVEN_BONES):
        self.profile = profile
        self.bones = list(bones)
        self.n = len(self.bones)
        gi = [profile.index[b] for b in self.bones]
        self.gidx = gi
        pos = {g: k for k, g in enumerate(gi)}
        self.parent = np.array([pos[profile.parent[g]] if profile.parent[g] >= 0 else -1 for g in gi])
        for k, p in enumerate(self.parent):
            assert p < k, "driven bones must be ordered parent-first"
        self.ref_t = profile.ref_t[gi]
        self.ref_q = profile.ref_q[gi]
        self.ref_comp_t = profile.ref_comp_t[gi]
        self.ref_comp_q = profile.ref_comp_q[gi]
        self.idx = {b: i for i, b in enumerate(self.bones)}

    def fk(self, local_q, root_t, pelvis_t):
        """local_q (F,B,4) xyzw, root_t (F,3) cm, pelvis_t (F,3) cm (local to root).

        Returns component-space rotations (F,B,4) and positions (F,B,3). Bones other than
        root/pelvis use their reference local translation.
        """
        local_q = np.asarray(local_q, np.float64)
        F = local_q.shape[0]
        wq = np.zeros((F, self.n, 4)); wt = np.zeros((F, self.n, 3))
        for b in range(self.n):
            p = self.parent[b]
            if p < 0:
                wq[:, b] = local_q[:, b]
                wt[:, b] = root_t
                continue
            off = pelvis_t if self.bones[b] == "pelvis" else np.broadcast_to(self.ref_t[b], (F, 3))
            wq[:, b] = mu.qmul(wq[:, p], local_q[:, b])
            wt[:, b] = wt[:, p] + mu.qrot(wq[:, p], off)
        return wq, wt

    def world_to_local(self, wq):
        """Convert world (component) rotations (F,B,4) to parent-local."""
        lq = wq.copy()
        for b in range(self.n):
            p = self.parent[b]
            if p >= 0:
                lq[:, b] = mu.qmul(mu.qconj(wq[:, p]), wq[:, b])
        return lq
