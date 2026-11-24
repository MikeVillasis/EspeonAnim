"""Debug PNG for POV takes: wrist trajectories in the head frame and FK stick figures of the arms and fingers."""
from __future__ import annotations
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from .pov_retarget import POV_BONES, PovRig, SIDES

COLORS = {"l": "tab:red", "r": "tab:blue"}


def pov_debug_png(path, sol, rig: PovRig, title="", n_frames=6):
    wq, wt = rig.fk(sol.local_q)
    F = sol.frames
    t = np.arange(F) / 60.0
    fig = plt.figure(figsize=(2.6 * n_frames, 9.5))
    gs = fig.add_gridspec(3, n_frames)
    labels = ("x right", "y forward", "z up")
    for k in range(3):
        ax = fig.add_subplot(gs[0, 2 * k:2 * k + 2])
        for s in SIDES:
            h = sol.hands[s]
            ax.plot(t, h.wrist[:, k], color=COLORS[s], lw=1.2, label={"l": "left", "r": "right"}[s])
            ax.plot(t[~h.seen], h.wrist[~h.seen, k], ".", color="gray", ms=2)
        ax.set_title(f"wrist {labels[k]} (m, head frame; grey = no detection)", fontsize=8)
        ax.set_xlabel("s")
        ax.grid(alpha=0.3)
        if k == 0:
            ax.legend(fontsize=7)
    sel = np.linspace(0, F - 1, n_frames).astype(int)
    for row, (hx, name) in enumerate(((0, "front (X)"), (1, "side (Y = forward)"))):
        for col, f in enumerate(sel):
            a = fig.add_subplot(gs[1 + row, col])
            for b, bone in enumerate(POV_BONES):
                p = rig.profile.parent[rig.g[bone]]
                pname = rig.profile.names[p]
                s = bone[-1]
                if "metacarpal" in pname:           # undriven helper between hand and finger: draw from the hand bone
                    pname = f"hand_{s}"
                pp = wt[f, rig.idx[pname]] if pname in rig.idx else rig.profile.ref_comp_t[p]
                lw = 2.0 if bone.split("_")[0] in ("clavicle", "upperarm", "lowerarm", "hand") else 1.0
                a.plot([pp[hx], wt[f, b, hx]], [pp[2], wt[f, b, 2]], "-", color=COLORS[s], lw=lw)
            a.plot(rig.eyes[hx], rig.eyes[2], "k+", ms=8)
            a.set_aspect("equal")
            a.set_xlim(-75, 75) if hx == 0 else a.set_xlim(-30, 120)
            a.set_ylim(70, 190)
            a.set_xticks([])
            a.set_title(f"f{f}  t={f / 60:.2f}s" if row == 0 else name, fontsize=8)
    fig.suptitle(f"{title}  (red = left, blue = right; + = eyes; UE space, left = +X)", fontsize=9)
    fig.tight_layout()
    fig.savefig(path, dpi=70)
    plt.close(fig)
