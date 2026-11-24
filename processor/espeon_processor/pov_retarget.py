"""POV step 6: retarget cleaned hand series (level head frame) onto Manny's arms, hands and fingers.

Coordinates. Head frame (physical, right-handed): X right, Y forward, Z up, origin at the eyes. Manny's component
space is the UE mirror image used everywhere else in the processor: the character faces +Y, Z is up and the
character's LEFT is +X (see retarget.py). So  p_ue = diag(-1, 1, 1) p_head  (det -1: a reflection, exactly like
retarget.SOMA_TO_UE). Everything below works in that UE numeric space with ordinary rotation matrices; handedness
never has to be thought about again because every vector, including Manny's own reference geometry, lives in it.
"""
from __future__ import annotations
import math
from dataclasses import dataclass
import numpy as np
from scipy.spatial.transform import Rotation as Rot
from . import mathutil as mu
from .skeleton import Profile

HEAD_TO_UE = np.diag([-1.0, 1.0, 1.0])
SIDES = ("l", "r")
SGN = {"l": 1.0, "r": -1.0}                        # direction of the child offset along the bone's local X
FINGERS = ("thumb", "index", "middle", "ring", "pinky")
LM_FIRST = {"thumb": 1, "index": 5, "middle": 9, "ring": 13, "pinky": 17}   # MediaPipe landmark of the first joint
EYE_OFFSET_CM = np.array([0.0, 9.0, 9.0])           # Manny's eyes relative to the head bone origin (+Y forward, +Z up)
POLE_HEAD = np.array([0.3, 0.0, -1.0])             # elbow pole in the head frame; x is negated for the left arm
CLAVICLE_MAX_DEG = 15.0
CLAVICLE_START, CLAVICLE_FULL = 0.85, 1.05          # fraction of the arm's reach where the clavicle starts / fully helps
FOREARM_TWIST_SHARE = 0.5                           # share of the hand's pronation/supination put on lowerarm (rest: hand)
FLEX_RANGE_DEG = (-10.0, 110.0)
ABD_RANGE_DEG = {1: 25.0, 2: 8.0, 3: 8.0}           # +- from the reference; the distal joints are hinges
THUMB_CONE_DEG = {1: 60.0, 2: 80.0, 3: 90.0}        # thumb: swing limit from the reference direction per joint
TIP_LEN_CM = 2.3


def finger_bones(side):
    return [f"{f}_0{n}_{side}" for f in FINGERS for n in (1, 2, 3)]


def side_bones(side):
    return [f"clavicle_{side}", f"upperarm_{side}", f"lowerarm_{side}", f"hand_{side}"] + finger_bones(side)


POV_BONES = side_bones("l") + side_bones("r")


class PovRig:
    """Reference geometry of Manny's arms, hands and fingers plus forward kinematics for POV motions."""

    def __init__(self, profile: Profile):
        self.profile = profile
        self.bones = list(POV_BONES)
        self.idx = {b: i for i, b in enumerate(self.bones)}
        self._fk_order = []                         # parent-first, with the (undriven) metacarpals that carry the fingers
        for s in SIDES:
            self._fk_order += side_bones(s)[:4] + [f"{f}_metacarpal_{s}" for f in FINGERS[1:]] + finger_bones(s)
        g = profile.index
        self.g = g
        self.R = {n: mu.q_to_mat(profile.ref_comp_q[i]) for n, i in g.items()}      # reference world rotations
        self.P = {n: profile.ref_comp_t[i] for n, i in g.items()}                    # reference component positions
        self.Rl = {n: mu.q_to_mat(profile.ref_q[i]) for n, i in g.items()}           # reference local rotations
        self.eyes = self.P["head"] + EYE_OFFSET_CM
        self.arm = {}
        for s in SIDES:
            C, S, E, W = (self.P[f"{n}_{s}"] for n in ("clavicle", "upperarm", "lowerarm", "hand"))
            self.arm[s] = dict(C=C, S=S, E=E, W=W, l1=float(np.linalg.norm(E - S)), l2=float(np.linalg.norm(W - E)),
                               d_c=mu.normalize(S - C), d_u=mu.normalize(E - S), d_l=mu.normalize(W - E))

    @property
    def arm_length_cm(self):
        a = self.arm["l"]
        return a["l1"] + a["l2"]

    # -- forward kinematics (all other bones at the reference pose) --------------------------------------------
    def fk(self, local_q):
        """local_q (F,38,4) for POV_BONES -> component rotations (F,38,4) and positions (F,38,3) in cm."""
        local_q = np.asarray(local_q, np.float64)
        F = len(local_q)
        prof = self.profile
        done = {}                                   # bone name -> (world q, world t); non-driven bones in between stay at ref
        for name in self._fk_order:
            gi = self.g[name]
            p = prof.parent[gi]
            pname = prof.names[p]
            if pname in done:
                pq, pt = done[pname]
            else:
                pq, pt = np.broadcast_to(prof.ref_comp_q[p], (F, 4)), np.broadcast_to(prof.ref_comp_t[p], (F, 3))
            lq = local_q[:, self.idx[name]] if name in self.idx else np.broadcast_to(prof.ref_q[gi], (F, 4))
            q = mu.qmul(pq, lq)
            done[name] = (q, pt + mu.qrot(pq, prof.ref_t[gi]))
        wq = np.stack([done[b][0] for b in self.bones], 1)
        wt = np.stack([done[b][1] for b in self.bones], 1)
        return wq, wt

    def reference_local_q(self, F=1):
        return np.tile(np.array([self.profile.ref_q[self.g[b]] for b in self.bones]), (F, 1, 1))

    def landmarks_ue(self, wq, wt, side):
        """MediaPipe-like 21 landmarks (F,21,3), UE cm, from FK output (tips extrapolated along the last bone)."""
        F = len(wt)
        out = np.zeros((F, 21, 3))
        out[:, 0] = wt[:, self.idx[f"hand_{side}"]]
        for f in FINGERS:
            k0 = LM_FIRST[f]
            for n in (1, 2, 3):
                out[:, k0 + n - 1] = wt[:, self.idx[f"{f}_0{n}_{side}"]]
            last = self.idx[f"{f}_03_{side}"]
            out[:, k0 + 3] = wt[:, last] + mu.qrot(wq[:, last], np.array([SGN[side], 0, 0.0])) * TIP_LEN_CM
        return out

    def reference_hand_head(self, side):
        """Manny's reference hand as head-frame landmarks relative to the wrist, metres, shape (21,3)."""
        q = self.reference_local_q()
        wq, wt = self.fk(q)
        lm = self.landmarks_ue(wq, wt, side)[0]
        return (lm - lm[0]) @ HEAD_TO_UE.T / 100.0

    # -- retargeting ------------------------------------------------------------------------------------------------
    def retarget_side(self, side, wrist_head, rel_head, k, pole_head=POLE_HEAD):
        """wrist_head (F,3) and rel_head (F,21,3): head-frame metres of one hand. k scales wrist targets (Manny arm /
        performer arm). Returns (local_q (F,19,4) in side_bones(side) order, diagnostics dict)."""
        a = self.arm[side]
        sgn = SGN[side]
        F = len(wrist_head)
        T = self.eyes + k * 100.0 * (np.asarray(wrist_head, float) @ HEAD_TO_UE.T)
        rel = np.asarray(rel_head, float) @ HEAD_TO_UE.T

        # clavicle: lift / protract towards targets near or beyond the reach
        reach = a["l1"] + a["l2"]
        d0 = np.linalg.norm(T - a["S"], axis=1)
        need = np.clip((d0 / reach - CLAVICLE_START) / (CLAVICLE_FULL - CLAVICLE_START), 0, 1)
        need = need * need * (3 - 2 * need)
        full = Rot.from_matrix(mu.swing_matrix(np.broadcast_to(a["d_c"], (F, 3)), mu.normalize(T - a["C"]))).as_rotvec()
        ang = np.linalg.norm(full, axis=1)
        lim = np.radians(CLAVICLE_MAX_DEG) * need
        rv = full * (np.minimum(1.0, lim / np.maximum(ang, 1e-9)))[:, None]
        Dc = Rot.from_rotvec(rv).as_matrix()
        R_clav = Dc @ self.R[f"clavicle_{side}"]
        S = a["C"] + np.einsum("fij,j->fi", Dc, a["S"] - a["C"])

        # two-bone IK with the pole pointing down and out
        pole = np.array([sgn * abs(pole_head[0]), pole_head[1], pole_head[2]])        # out = +X (left) / -X (right) in UE
        elbow, wrist, stats = two_bone_ik(S, T, a["l1"], a["l2"], pole)
        R_up = mu.swing_matrix(np.broadcast_to(a["d_u"], (F, 3)), mu.normalize(elbow - S)) @ self.R[f"upperarm_{side}"]
        R_lo0 = mu.swing_matrix(np.broadcast_to(a["d_l"], (F, 3)), mu.normalize(wrist - elbow)) @ self.R[f"lowerarm_{side}"]

        # hand: palm frame (wrist, MCP line) of the observed landmarks vs Manny's reference hand
        R_hand = self.palm_rotation(side, rel)
        # share of the pronation / supination goes to the forearm (twist about its own axis)
        axis = mu.normalize(self.R[f"lowerarm_{side}"].T @ a["d_l"])
        q = Rot.from_matrix(np.swapaxes(R_lo0, 1, 2) @ R_hand).as_quat()
        tw = 2.0 * np.arctan2(q[:, :3] @ axis, q[:, 3])
        tw = (tw + np.pi) % (2 * np.pi) - np.pi
        R_lo = R_lo0 @ Rot.from_rotvec(axis[None] * (FOREARM_TWIST_SHARE * tw)[:, None]).as_matrix()

        W = {f"clavicle_{side}": R_clav, f"upperarm_{side}": R_up, f"lowerarm_{side}": R_lo, f"hand_{side}": R_hand}
        fingers, fstats = self.solve_fingers(side, R_hand, rel)
        parent_world = {f"clavicle_{side}": self.R["spine_05"], f"upperarm_{side}": R_clav, f"lowerarm_{side}": R_up,
                        f"hand_{side}": R_lo}
        loc = []
        for name in side_bones(side)[:4]:
            R = W[name]
            Rp = np.broadcast_to(parent_world[name], R.shape)
            loc.append(mu.mat_to_q(np.swapaxes(Rp, 1, 2) @ R))
        loc = np.stack(loc + fingers, 1)
        loc = mu.make_continuous(loc, axis=0)
        stats.update(fstats)
        stats["clavicle_max_deg"] = float(np.degrees(np.linalg.norm(rv, axis=1)).max())
        return mu.qnorm(loc), stats

    def palm_rotation(self, side, rel_ue):
        """World rotation (F,3,3) of the hand bone from the palm frame (wrist, MCP line) of rel_ue (F,21,3), UE space."""
        mc = [f"{f}_01_{side}" for f in ("index", "middle", "ring", "pinky")]
        fwd_ref = np.mean([self.P[m] for m in mc], 0) - self.P[f"hand_{side}"]
        acr_ref = self.P[f"index_01_{side}"] - self.P[f"pinky_01_{side}"]
        fwd = rel_ue[:, [5, 9, 13, 17]].mean(1) - rel_ue[:, 0]
        acr = rel_ue[:, 5] - rel_ue[:, 17]
        return mu.triad(fwd, acr) @ (mu.triad(fwd_ref, acr_ref).T @ self.R[f"hand_{side}"])[None]

    def solve_fingers(self, side, R_hand, rel_ue):
        """Finger joints by aligning each bone's direction with the landmark segment (see module docstring). Returns the
        15 local quaternions (list of (F,4) in finger_bones order) and diagnostics."""
        sgn = SGN[side]
        a_b = np.array([sgn, 0, 0.0])
        out, clamped = {}, 0
        total = 0
        Rh_ref = self.R[f"hand_{side}"]
        for f in FINGERS:
            k0 = LM_FIRST[f]
            parent = self.profile.names[self.profile.parent[self.g[f"{f}_01_{side}"]]]
            Rp = R_hand @ (Rh_ref.T @ self.R[parent])[None]           # metacarpals / hand stay at the reference
            for n in (1, 2, 3):
                name = f"{f}_0{n}_{side}"
                d_obs = mu.normalize(rel_ue[:, k0 + n] - rel_ue[:, k0 + n - 1])
                d_p = np.einsum("fji,fj->fi", Rp, d_obs)
                Rl = self.Rl[name]
                r = Rl @ a_b
                if f == "thumb":
                    d_new = _cone_clamp(r, d_p, math.radians(THUMB_CONE_DEG[n]))
                else:
                    d_new = _hinge_clamp(r, d_p, sgn, n)
                clamped += int((np.linalg.norm(d_new - d_p, axis=1) > 1e-6).sum()); total += len(d_p)
                Rloc = mu.swing_matrix(np.broadcast_to(r, d_new.shape), d_new) @ Rl
                out[name] = mu.mat_to_q(Rloc)
                Rp = Rp @ Rloc
        return [out[b] for b in finger_bones(side)], {"finger_clamped_fraction": clamped / max(total, 1)}


def two_bone_ik(S, T, l1, l2, pole):
    """Elbow and reached wrist (F,3) for shoulder S, targets T. Out of reach: the arm straightens towards the target and
    the wrist stops at full extension. `pole`: direction the elbow points to (projected perpendicular to the arm)."""
    v = T - S
    d = np.linalg.norm(v, axis=1)
    u = v / np.maximum(d, 1e-9)[:, None]
    dmax, dmin = (l1 + l2) * 0.9999, max(abs(l1 - l2) * 1.0001, 1e-3)
    dc = np.clip(d, dmin, dmax)
    a = (l1 * l1 - l2 * l2 + dc * dc) / (2 * dc)
    h = np.sqrt(np.maximum(l1 * l1 - a * a, 0.0))
    pole = np.broadcast_to(np.asarray(pole, float), u.shape)
    perp = pole - (pole * u).sum(1, keepdims=True) * u
    bad = np.linalg.norm(perp, axis=1) < 1e-6
    if bad.any():
        alt = np.broadcast_to(np.array([1.0 if pole[0, 0] >= 0 else -1.0, 0, 0]), u.shape)
        perp[bad] = (alt - (alt * u).sum(1, keepdims=True) * u)[bad]
    perp = mu.normalize(perp)
    elbow = S + u * a[:, None] + perp * h[:, None]
    wrist = S + u * dc[:, None]
    return elbow, wrist, {"out_of_reach_fraction": float((d > dmax).mean()), "max_reach_error_cm": float(np.maximum(d - dmax, 0).max())}


def _hinge_clamp(r, d_p, sgn, joint):
    """Clamp directions d_p (F,3), given in the parent frame, to a finger joint: flexion about the parent's Z (positive
    = curling, which is -Z rotation on Manny) within -10..110 deg, abduction (about Y) within +-25 deg of the
    reference direction `r` (+-8 deg for the distal joints)."""
    th = np.arctan2(sgn * d_p[:, 1], sgn * d_p[:, 0])
    ph = np.arcsin(np.clip(-sgn * d_p[:, 2], -1, 1))
    ph_r = math.asin(max(-1.0, min(1.0, -sgn * r[2])))
    flex = np.clip(-th, math.radians(FLEX_RANGE_DEG[0]), math.radians(FLEX_RANGE_DEG[1]))
    lim = math.radians(ABD_RANGE_DEG[joint])
    ph = np.clip(ph, ph_r - lim, ph_r + lim)
    th = -flex
    return sgn * np.stack([np.cos(ph) * np.cos(th), np.cos(ph) * np.sin(th), -np.sin(ph)], 1)


def _cone_clamp(r, d_p, max_angle):
    """Limit the angle between d_p (F,3) and the reference direction r to max_angle."""
    r = mu.normalize(r)
    c = np.clip(d_p @ r, -1, 1)
    ang = np.arccos(c)
    perp = d_p - c[:, None] * r
    pn = np.linalg.norm(perp, axis=1, keepdims=True)
    t = perp / np.maximum(pn, 1e-9)
    lim = np.minimum(ang, max_angle)
    new = np.cos(lim)[:, None] * r + np.sin(lim)[:, None] * t
    return np.where((ang > max_angle)[:, None], new, d_p)


@dataclass
class PovMotion:
    local_q: np.ndarray        # (F,38,4) for POV_BONES
    stats: dict


def retarget_pov(rig: PovRig, hands: dict, k: float):
    """hands = {"l": (wrist (F,3), rel (F,21,3)), "r": ...} on the 60 fps grid, head frame, metres."""
    parts, stats = [], {}
    for s in SIDES:
        q, st = rig.retarget_side(s, hands[s][0], hands[s][1], k)
        parts.append(q)
        stats[s] = st
    return PovMotion(np.concatenate(parts, 1), stats)
