"""Debug PNGs rendered from the final motion.json: FK stick-figure contact sheet and height/contact plot."""
from __future__ import annotations
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from .cleanup import ANKLE_H, BALL_H
from .motion_io import fk_from_motion
from .analysis import runs


def contact_sheet(motion, path, n=10, title=""):
    rig, wq, wt = fk_from_motion(motion)
    F = len(wt)
    sel = np.linspace(0, F - 1, n).astype(int)
    fps = motion["fps"]
    fig, axs = plt.subplots(2, n, figsize=(2.2 * n, 6.2))
    for col, f in enumerate(sel):
        for row, (hx, label) in enumerate(((0, "front (X)"), (1, "side (Y = forward)"))):
            a = axs[row, col]
            for b in range(1, rig.n):
                p = rig.parent[b]
                if p >= 1:
                    color = "tab:red" if rig.bones[b].endswith("_l") else ("tab:blue" if rig.bones[b].endswith("_r") else "k")
                    a.plot([wt[f, p, hx], wt[f, b, hx]], [wt[f, p, 2], wt[f, b, 2]], "-", color=color, lw=2)
            a.plot(wt[f, 1:, hx], wt[f, 1:, 2], "k.", ms=2)
            a.axhline(0, color="g", lw=1)
            cx = wt[f, rig.idx["pelvis"], hx]
            a.set_xlim(cx - 110, cx + 110)
            a.set_ylim(-10, 230)
            a.set_aspect("equal")
            a.set_xticks([])
            a.set_title(f"f{f}  t={f / fps:.2f}s" if row == 0 else label, fontsize=8)
    fig.suptitle(f"{title}  (red = left, blue = right; front view from +Y looking at the character, X right)", fontsize=9)
    fig.tight_layout()
    fig.savefig(path, dpi=75)
    plt.close(fig)


def height_plot(motion, on60, path, title="", info=None):
    rig, wq, wt = fk_from_motion(motion)
    fps = motion["fps"]
    t = np.arange(len(wt)) / fps
    root = np.array(motion["root_translation"])
    pel_z = wt[:, rig.idx["pelvis"], 2]
    sole = []
    for s in ("l", "r"):
        sole.append(np.minimum(wt[:, rig.idx[f"foot_{s}"], 2] - ANKLE_H, wt[:, rig.idx[f"ball_{s}"], 2] - BALL_H))
    fig, ax = plt.subplots(2, 1, figsize=(10, 6.5), sharex=True)
    ax[0].plot(t, pel_z, label="pelvis height (cm)")
    ax[0].plot(t, root[:, 2], label="root Z lift (cm)")
    ax[0].plot(t, sole[0], label="lowest left sole point (cm)", lw=1)
    ax[0].plot(t, sole[1], label="lowest right sole point (cm)", lw=1)
    for k, (c, nm) in enumerate((("tab:red", "left"), ("tab:blue", "right"))):
        for s, e in runs(on60[:, k]):
            ax[1].fill_between([t[s], t[min(e, len(t) - 1)]], k, k + 0.8, color=c, alpha=0.5)
    ax[1].set_yticks([0.4, 1.4]); ax[1].set_yticklabels(["left foot contact", "right foot contact"])
    for a in ax:
        a.grid(alpha=0.3)
        if info:
            for k, v in info.items():
                a.axvline(v, color="gray", ls="--", lw=0.8)
    if info:
        for k, v in info.items():
            ax[0].text(v, ax[0].get_ylim()[1] * 0.95, k, rotation=90, va="top", ha="right", fontsize=8)
    ax[0].set_ylabel("cm"); ax[0].legend(ncol=2, fontsize=8); ax[0].set_title(title)
    ax[1].set_xlabel("s")
    fig.tight_layout()
    fig.savefig(path, dpi=100)
    plt.close(fig)
