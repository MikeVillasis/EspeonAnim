"""Validation of a finished motion against the SOMA reconstruction it came from."""
from __future__ import annotations
import numpy as np
from .motion_io import fk_from_motion
from .retarget import direction_errors


def direction_error(motion, solved, soma_names, profile=None):
    """Mean angle (deg) between Manny bone directions (FK of motion.json) and the matching SOMA bone
    directions after conversion to UE space, over all output frames. Returns (mean, {segment: mean})."""
    rig, wq, wt = fk_from_motion(motion, profile)
    P = solved.debug["soma_ue30"].copy()
    P[..., :2] -= solved.debug["origin"]
    t30 = np.arange(len(P)) / 30.0
    t60 = np.arange(len(wt)) / float(motion["fps"])
    P60 = np.stack([np.stack([np.interp(t60, t30, P[:, j, k]) for k in range(3)], 1) for j in range(P.shape[1])], 1)
    err, labels = direction_errors(rig, wt, P60, soma_names)
    return float(err.mean()), {l: float(v) for l, v in zip(labels, err.mean(0))}
