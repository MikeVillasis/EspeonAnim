"""Small numpy rotation helpers. Quaternions are [x, y, z, w]; matrices act on column vectors.

Conventions are purely algebraic (q v q^-1), which is also how Unreal's FQuat works, so the same
functions serve SOMA (right-handed) and UE (left-handed) data as long as data is converted with a
consistent coordinate change (see retarget.SOMA_TO_UE).
"""
from __future__ import annotations
import numpy as np
from scipy.spatial.transform import Rotation as Rot


def normalize(v, axis=-1, eps=1e-12):
    v = np.asarray(v, np.float64)
    n = np.linalg.norm(v, axis=axis, keepdims=True)
    return v / np.maximum(n, eps)


def qnorm(q):
    return normalize(np.asarray(q, np.float64))


def qmul(a, b):
    ax, ay, az, aw = np.moveaxis(np.asarray(a, np.float64), -1, 0)
    bx, by, bz, bw = np.moveaxis(np.asarray(b, np.float64), -1, 0)
    return np.stack([aw * bx + ax * bw + ay * bz - az * by,
                     aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw,
                     aw * bw - ax * bx - ay * by - az * bz], -1)


def qconj(q):
    q = np.asarray(q, np.float64)
    return q * np.array([-1, -1, -1, 1.0])


def qrot(q, v):
    """Rotate vectors v (...,3) by quaternions q (...,4)."""
    q = np.asarray(q, np.float64)
    v = np.asarray(v, np.float64)
    u = q[..., :3]
    w = q[..., 3:4]
    t = 2 * np.cross(u, v)
    return v + w * t + np.cross(u, t)


def mat_to_q(R):
    R = np.asarray(R, np.float64)
    return Rot.from_matrix(R.reshape(-1, 3, 3)).as_quat().reshape(*R.shape[:-2], 4)


def q_to_mat(q):
    q = qnorm(q)
    return Rot.from_quat(q.reshape(-1, 4)).as_matrix().reshape(*q.shape[:-1], 3, 3)


def make_continuous(q, axis=0):
    """Flip signs so consecutive quaternions along `axis` have non-negative dot product."""
    q = np.moveaxis(np.array(q, np.float64), axis, 0)
    for i in range(1, len(q)):
        flip = (q[i] * q[i - 1]).sum(-1) < 0
        q[i] = np.where(flip[..., None], -q[i], q[i])
    return np.moveaxis(q, 0, axis)


def slerp(q0, q1, t):
    q0 = np.asarray(q0, np.float64)
    q1 = np.asarray(q1, np.float64)
    t = np.asarray(t, np.float64)[..., None]
    d = (q0 * q1).sum(-1, keepdims=True)
    q1 = np.where(d < 0, -q1, q1)
    d = np.abs(d).clip(0, 1)
    th = np.arccos(d)
    s = np.sin(th)
    small = s < 1e-6
    ss = np.where(small, 1.0, s)
    a = np.where(small, 1 - t, np.sin((1 - t) * th) / ss)
    b = np.where(small, t, np.sin(t * th) / ss)
    return qnorm(a * q0 + b * q1)


def swing_matrix(a, b):
    """Minimal rotation matrix taking unit vector(s) a onto b (broadcasting)."""
    a = normalize(a)
    b = normalize(b)
    a, b = np.broadcast_arrays(a, b)
    v = np.cross(a, b)
    c = (a * b).sum(-1)
    vx = np.zeros(a.shape[:-1] + (3, 3))
    vx[..., 0, 1], vx[..., 0, 2] = -v[..., 2], v[..., 1]
    vx[..., 1, 0], vx[..., 1, 2] = v[..., 2], -v[..., 0]
    vx[..., 2, 0], vx[..., 2, 1] = -v[..., 1], v[..., 0]
    eye = np.broadcast_to(np.eye(3), vx.shape)
    k = 1.0 / np.maximum(1.0 + c, 1e-9)
    out = eye + vx + (vx @ vx) * k[..., None, None]
    anti = c < -1 + 1e-7
    if np.any(anti):
        aa = a[anti]
        perp = np.cross(aa, np.array([1.0, 0, 0]))
        bad = np.linalg.norm(perp, axis=-1) < 1e-6
        perp[bad] = np.cross(aa[bad], np.array([0, 1.0, 0]))
        perp = normalize(perp)
        out[anti] = 2 * perp[..., :, None] * perp[..., None, :] - np.eye(3)
    return out


def triad(v1, v2):
    """Right-handed orthonormal frame (columns): first axis along v1, second in span(v1, v2)."""
    e1 = normalize(v1)
    e3 = normalize(np.cross(e1, v2))
    e2 = np.cross(e3, e1)
    return np.stack([e1, e2, e3], -1)


def rotz(theta):
    c, s = np.cos(theta), np.sin(theta)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1.0]])


def angle_between(a, b):
    """Angle in degrees between vectors (...,3)."""
    a, b = normalize(a), normalize(b)
    return np.degrees(np.arccos(np.clip((a * b).sum(-1), -1, 1)))
