"""Convert GEM-X motion.pt (SOMA params) -> gem_world.npz (skeleton, FK joints, local rotations, contacts).

World frame (verified): metres, Y-up, right-handed, floor at y~0 after contact/IK. Frame rate 30 Hz.
Joint 0 is Hips (SOMA's virtual 'Root' joint, which is identity, is dropped); 77 joints.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import numpy as np
import torch
from scipy.spatial.transform import Rotation as Rot

SOMA_ASSETS = Path(__file__).resolve().parents[1] / "models" / "soma"
CONTACT_NAMES = ["left_ankle", "left_foot", "right_ankle", "right_foot", "left_wrist", "right_wrist"]
CONTACT_JOINTS = [69, 70, 74, 75, 14, 42]  # indices in the 77-joint list


def _quat_wxyz(R):
    q = Rot.from_matrix(R.reshape(-1, 3, 3)).as_quat()  # xyzw
    q = q[:, [3, 0, 1, 2]]
    q[q[:, 0] < 0] *= -1
    return q.reshape(*R.shape[:-2], 4).astype(np.float32)


def export(motion_pt, out_npz, soma_assets=SOMA_ASSETS, device="cuda"):
    from gem.common.soma import SomaFixedGs1BodyAdapter, SomaRuntimeConfig
    d = torch.load(motion_pt, map_location="cpu", weights_only=False)
    body = SomaFixedGs1BodyAdapter(SomaRuntimeConfig(data_root=str(soma_assets), device=device, lod="xlo", mode="warp",
                                                     apply_correctives=False, enable_procedural_transforms=True,
                                                     procedural_reference="runtime"))
    rt = body.runtime
    names = list(rt.public_joint_names)[1:]
    parents = (rt.output_joint_parent_ids.cpu().numpy().astype(int)[1:] - 1)  # Hips -> -1
    P = {k: v.to(device) for k, v in d["pred_soma_params_global"].items()}
    with torch.no_grad():
        o = body(**P, return_joints_only=True)
        z = {k: (v[:, :1] * 0 if k in ("body_pose", "global_orient", "transl") else v[:, :1]) for k, v in P.items()}
        oz = body(**z, return_joints_only=True)
    joints = o["joints"][0].cpu().double().numpy()            # (T,77,3)
    Tw = o["transforms"][0, :, 1:].cpu().double().numpy()      # (T,77,4,4) world
    Rw, J = Tw[..., :3, :3], joints
    T, N = joints.shape[:2]
    Rl = Rw.copy()
    offs = np.zeros((T, N, 3))
    for j in range(1, N):
        p = parents[j]
        Rl[:, j] = np.einsum("tba,tbc->tac", Rw[:, p], Rw[:, j])               # R_p^T R_j
        offs[:, j] = np.einsum("tba,tb->ta", Rw[:, p], J[:, j] - J[:, p]) if False else \
            np.einsum("tba,tb->ta", Rw[:, p], J[:, j] - J[:, p])
    rest_offs = np.median(offs, 0)
    off_dev = float(np.abs(offs - rest_offs).max())
    # FK check from exported quantities
    Rw2 = np.zeros_like(Rw); Pw2 = np.zeros_like(J)
    Rw2[:, 0], Pw2[:, 0] = Rl[:, 0], J[:, 0]
    for j in range(1, N):
        p = parents[j]
        Rw2[:, j] = Rw2[:, p] @ Rl[:, j]
        Pw2[:, j] = Pw2[:, p] + np.einsum("tab,b->ta", Rw2[:, p], rest_offs[j])
    fk_err = float(np.abs(Pw2 - J).max())
    zj = oz["joints"][0, 0].cpu().double().numpy(); zT = oz["transforms"][0, 0, 1:].cpu().double().numpy()
    logits = d["static_conf_logits"][0].float().numpy()
    out = dict(
        fps=np.float32(30.0), joint_names=np.array(names), parents=parents.astype(np.int32),
        rest_local_offsets=rest_offs.astype(np.float32),             # (77,3) offset in parent-local frame, metres
        rest_joint_positions=zj.astype(np.float32),                  # zero-pose (T-pose) joints, Hips at origin
        rest_world_rotations=zT[:, :3, :3].astype(np.float32),
        root_translation=J[:, 0].astype(np.float32),                 # Hips world position (T,3) m
        root_orientation_quat_wxyz=_quat_wxyz(Rw[:, 0]),             # Hips world orientation
        local_rotations_quat_wxyz=_quat_wxyz(Rl),                    # (T,77,4); [:,0] == world root orientation
        local_rotations_matrix=Rl.astype(np.float32),
        world_rotations_matrix=Rw.astype(np.float32),
        joint_positions_world=J.astype(np.float32),                  # (T,77,3) m
        contact_prob=(1 / (1 + np.exp(-logits))).astype(np.float32), contact_logits=logits,
        contact_names=np.array(CONTACT_NAMES), contact_joint_indices=np.array(CONTACT_JOINTS, np.int32),
        global_orient_axis_angle=d["pred_soma_params_global"]["global_orient"][0].numpy(),
        transl_param=d["pred_soma_params_global"]["transl"][0].numpy(),
        body_pose_axis_angle=d["pred_soma_params_global"]["body_pose"][0].numpy().reshape(T, 76, 3),
        identity_coeffs_mean=d["pred_soma_params_global"]["identity_coeffs"][0].mean(0).numpy(),
        scale_params_mean=d["pred_soma_params_global"]["scale_params"][0].mean(0).numpy(),
        coordinate_system=np.array("world: metres, Y-up, right-handed, floor y=0; 30 Hz; quats wxyz; local rot = R_parent^T R_child"),
        fk_max_err_m=np.float32(fk_err), offset_const_max_dev_m=np.float32(off_dev),
    )
    out_npz = Path(out_npz); out_npz.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out_npz, **out)
    print(f"saved {out_npz}: T={T} fk_err={fk_err:.2e} offset_dev={off_dev:.2e}")
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("motion_pt"); ap.add_argument("out_npz", nargs="?")
    a = ap.parse_args()
    export(a.motion_pt, a.out_npz or Path(a.motion_pt).with_name("gem_world.npz"))
