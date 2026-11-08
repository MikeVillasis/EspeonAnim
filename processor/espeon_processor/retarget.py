"""SOMA (GEM-X) -> UE5 Manny retargeting by matched rest pose.

Coordinate change: SOMA is right-handed, Y up, +Z forward, +X = performer's left.
UE is left-handed, Z up; the mannequin faces +Y in the AnimSequence, and the character's left is +X.
So  x_ue = x_soma (left),  y_ue = z_soma (forward),  z_ue = y_soma (up). This matrix has det -1
(a reflection, as expected when going between handedness); rotations convert as  R_ue = M R M^T.
"""
from __future__ import annotations
from dataclasses import dataclass
import numpy as np
from . import mathutil as mu
from .skeleton import Rig

SOMA_TO_UE = np.array([[1.0, 0, 0], [0, 0, 1.0], [0, 1.0, 0]])


def pos_to_ue(P):
    return np.asarray(P, np.float64) @ SOMA_TO_UE.T


def rot_to_ue(R):
    return SOMA_TO_UE @ np.asarray(R, np.float64) @ SOMA_TO_UE.T


# manny bone -> (soma joint, optional second joint, weight toward the second)
def _drivers():
    d = {"pelvis": ("Hips",), "spine_01": ("Spine1",), "spine_02": ("Spine1", "Spine2", 0.5),
         "spine_03": ("Spine2",), "spine_04": ("Spine2", "Chest", 0.5), "spine_05": ("Chest",),
         "neck_01": ("Neck1",), "neck_02": ("Neck2",), "head": ("Head",)}
    for s, S in (("l", "Left"), ("r", "Right")):
        d.update({f"clavicle_{s}": (f"{S}Shoulder",), f"upperarm_{s}": (f"{S}Arm",),
                  f"lowerarm_{s}": (f"{S}ForeArm",), f"hand_{s}": (f"{S}Hand",),
                  f"thigh_{s}": (f"{S}Leg",), f"calf_{s}": (f"{S}Shin",),
                  f"foot_{s}": (f"{S}Foot",), f"ball_{s}": (f"{S}ToeBase",)})
    return d


DRIVERS = _drivers()

# Bones whose matched-rest swing comes from a (manny child, soma from, soma to) direction pair.
def _dir_pairs():
    p = {"neck_01": ("neck_02", "Neck1", "Neck2"), "neck_02": ("head", "Neck2", "Head")}
    for s, S in (("l", "Left"), ("r", "Right")):
        p.update({f"clavicle_{s}": (f"upperarm_{s}", f"{S}Shoulder", f"{S}Arm"),
                  f"upperarm_{s}": (f"lowerarm_{s}", f"{S}Arm", f"{S}ForeArm"),
                  f"lowerarm_{s}": (f"hand_{s}", f"{S}ForeArm", f"{S}Hand"),
                  f"hand_{s}": (f"middle_01_{s}", f"{S}Hand", f"{S}HandMiddle1"),
                  f"thigh_{s}": (f"calf_{s}", f"{S}Leg", f"{S}Shin"),
                  f"calf_{s}": (f"foot_{s}", f"{S}Shin", f"{S}Foot"),
                  f"foot_{s}": (f"ball_{s}", f"{S}Foot", f"{S}ToeBase")})
    return p


DIR_PAIRS = _dir_pairs()
LEAVES = {"head": "neck_02", "ball_l": "foot_l", "ball_r": "foot_r"}
SPINE_CHAIN = ["spine_01", "spine_02", "spine_03", "spine_04", "spine_05", "neck_01"]
SOMA_SPINE = ["Spine1", "Spine2", "Chest", "Neck1"]

# Segments used to validate the retarget: (manny a, manny b, soma a, soma b)
VALIDATION_SEGMENTS = [("pelvis", "neck_01", "Hips", "Neck1"), ("neck_01", "head", "Neck1", "Head")]
for _s, _S in (("l", "Left"), ("r", "Right")):
    VALIDATION_SEGMENTS += [(f"clavicle_{_s}", f"upperarm_{_s}", f"{_S}Shoulder", f"{_S}Arm"),
                            (f"upperarm_{_s}", f"lowerarm_{_s}", f"{_S}Arm", f"{_S}ForeArm"),
                            (f"lowerarm_{_s}", f"hand_{_s}", f"{_S}ForeArm", f"{_S}Hand"),
                            (f"thigh_{_s}", f"calf_{_s}", f"{_S}Leg", f"{_S}Shin"),
                            (f"calf_{_s}", f"foot_{_s}", f"{_S}Shin", f"{_S}Foot"),
                            (f"foot_{_s}", f"ball_{_s}", f"{_S}Foot", f"{_S}ToeBase")]


def _polyline_sample(points, fracs):
    seg = np.linalg.norm(np.diff(points, axis=0), axis=1)
    cum = np.concatenate([[0], np.cumsum(seg)]) / seg.sum()
    return np.stack([np.interp(fracs, cum, points[:, k]) for k in range(3)], 1)


class Retargeter:
    """Builds the matched rest pose once, then maps SOMA world rotations to Manny world rotations."""

    def __init__(self, rig: Rig, soma_names, soma_rest_pos_ue, soma_rest_rot_ue):
        self.rig = rig
        self.sidx = {str(n): i for i, n in enumerate(soma_names)}
        self.rest_pos = np.asarray(soma_rest_pos_ue, np.float64)   # (77,3) UE space
        self.rest_rot = np.asarray(soma_rest_rot_ue, np.float64)   # (77,3,3)
        prof = rig.profile
        self.ref_rot = mu.q_to_mat(prof.ref_comp_q[rig.gidx])      # (B,3,3) Manny reference world rotations
        self.ref_pos = prof.ref_comp_t[rig.gidx]
        self._pos_by_name = lambda n: prof.ref_comp_t[prof.index[n]]
        self.match = self._build_matched_rest()                    # (B,3,3)

    def sp(self, name):
        return self.rest_pos[self.sidx[name]]

    def _build_matched_rest(self):
        rig, prof = self.rig, self.rig.profile
        B = rig.n
        match = np.tile(np.eye(3), (B, 1, 1))
        done = np.zeros(B, bool)
        match[0] = self.ref_rot[0]; done[0] = True
        # pelvis: two-vector alignment (spine axis + hip line)
        i = rig.idx["pelvis"]
        dm1 = self._pos_by_name("spine_01") - self._pos_by_name("pelvis")
        dm2 = self._pos_by_name("thigh_l") - self._pos_by_name("thigh_r")
        ds1 = self.sp("Spine1") - self.sp("Hips")
        ds2 = self.sp("LeftLeg") - self.sp("RightLeg")
        match[i] = mu.triad(ds1, ds2) @ mu.triad(dm1, dm2).T @ self.ref_rot[i]
        done[i] = True
        # spine chain: arc-length matched polyline directions
        mp = np.array([self._pos_by_name(n) for n in SPINE_CHAIN])
        seg = np.linalg.norm(np.diff(mp, axis=0), axis=1)
        fracs = np.concatenate([[0], np.cumsum(seg)]) / seg.sum()
        q = _polyline_sample(np.array([self.sp(n) for n in SOMA_SPINE]), fracs)
        for k, n in enumerate(SPINE_CHAIN[:-1]):
            i = rig.idx[n]
            S = mu.swing_matrix(mp[k + 1] - mp[k], q[k + 1] - q[k])
            match[i] = S @ self.ref_rot[i]; done[i] = True
        for n, (child, sa, sb) in DIR_PAIRS.items():
            i = rig.idx[n]
            S = mu.swing_matrix(self._pos_by_name(child) - self._pos_by_name(n), self.sp(sb) - self.sp(sa))
            match[i] = S @ self.ref_rot[i]; done[i] = True
        for n, parent in LEAVES.items():
            i, p = rig.idx[n], rig.idx[parent]
            match[i] = match[p] @ self.ref_rot[p].T @ self.ref_rot[i]; done[i] = True
        assert done.all(), [rig.bones[k] for k in np.flatnonzero(~done)]
        return match

    def matched_rest_pose(self):
        """Local rotations (B,4) of the matched rest pose (what SOMA's rest pose retargets to)."""
        return self.rig.world_to_local(mu.mat_to_q(self.match)[None])[0]

    def delta(self, R_soma_ue):
        """Per-joint world rotation change vs SOMA rest: R(t) R_rest^T. (T,77,3,3)"""
        return R_soma_ue @ np.swapaxes(self.rest_rot, -1, -2)[None]

    def world_rotations(self, R_soma_ue):
        """Manny world rotations (T,B,4 xyzw) for SOMA world rotations (T,77,3,3) in UE space."""
        rig = self.rig
        D = self.delta(R_soma_ue)
        T = D.shape[0]
        out = np.zeros((T, rig.n, 4))
        out[:, 0] = [0, 0, 0, 1]
        Dq = {}
        for b in range(1, rig.n):
            drv = DRIVERS[rig.bones[b]]
            ja = self.sidx[drv[0]]
            if ja not in Dq:
                Dq[ja] = mu.mat_to_q(D[:, ja])
            qd = Dq[ja]
            if len(drv) == 3:
                jb = self.sidx[drv[1]]
                if jb not in Dq:
                    Dq[jb] = mu.mat_to_q(D[:, jb])
                qd = mu.slerp(Dq[ja], Dq[jb], np.full(T, drv[2]))
            out[:, b] = mu.qmul(qd, mu.mat_to_q(self.match[b]))
        return mu.qnorm(out)

    def hip_anchor_local(self):
        """Offset (cm, in pelvis frame) from the Manny pelvis origin to the midpoint of its hip joints."""
        prof = self.rig.profile
        mid = 0.5 * (self._pos_by_name("thigh_l") + self._pos_by_name("thigh_r"))
        i = self.rig.idx["pelvis"]
        return mu.qrot(mu.qconj(prof.ref_comp_q[self.rig.gidx[i]]), mid - self._pos_by_name("pelvis"))


def direction_errors(rig: Rig, manny_pos, soma_pos_ue, soma_names, segments=VALIDATION_SEGMENTS):
    """Angles (deg) per frame and segment between Manny FK bone directions and SOMA ones.

    manny_pos (F,B,3) driven-bone positions; soma_pos_ue (F,77,3) in the same (UE) frame.
    Returns (F, S) array and the segment labels.
    """
    sidx = {str(n): i for i, n in enumerate(soma_names)}
    errs, labels = [], []
    for a, b, sa, sb in segments:
        dm = manny_pos[:, rig.idx[b]] - manny_pos[:, rig.idx[a]]
        ds = soma_pos_ue[:, sidx[sb]] - soma_pos_ue[:, sidx[sa]]
        errs.append(mu.angle_between(dm, ds))
        labels.append(f"{a}->{b}")
    return np.stack(errs, 1), labels
