"""Sanity plots from gem_world.npz: root height curve + stick-figure contact sheet."""
import sys
from pathlib import Path
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt


def run(clip_dir):
    clip_dir = Path(clip_dir); d = np.load(clip_dir / "gem_world.npz")
    J, par, names, fps = d["joint_positions_world"], d["parents"], list(d["joint_names"]), float(d["fps"])
    t = np.arange(len(J)) / fps
    hips = J[:, 0, 1]
    feet_idx = [names.index(n) for n in ("LeftFoot", "RightFoot", "LeftToeBase", "RightToeBase", "LeftToeEnd", "RightToeEnd")]
    low_foot = J[:, feet_idx, 1].min(1)
    lowest = J[:, :, 1].min(1)
    fig, ax = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
    ax[0].plot(t, hips, label="Hips (root) height"); ax[0].plot(t, low_foot, label="lowest foot joint")
    ax[0].set_ylabel("y (m)"); ax[0].legend(); ax[0].grid(alpha=.3); ax[0].set_title(clip_dir.name)
    cp = d["contact_prob"]
    for k, n in enumerate(d["contact_names"][:4]):
        ax[1].plot(t, cp[:, k], label=str(n))
    ax[1].set_ylabel("contact prob"); ax[1].set_xlabel("s"); ax[1].legend(ncol=4); ax[1].grid(alpha=.3)
    fig.tight_layout(); fig.savefig(clip_dir / "root_height.png", dpi=110); plt.close(fig)
    # stick sheet: skip fingers/face for clarity
    skip = ("Hand", "Eye", "Jaw", "End")
    keep = [i for i, n in enumerate(names) if not any(s in n for s in skip) or n in ("LeftHand", "RightHand", "HeadEnd", "LeftToeEnd", "RightToeEnd")]
    sel = np.linspace(0, len(J) - 1, 8).astype(int)
    c = J[:, 0, [0, 2]]; span = max(np.ptp(J[..., 1]), 1.0) * 0.8
    fig, axs = plt.subplots(2, 8, figsize=(20, 6.5))
    for col, f in enumerate(sel):
        for row, (hx, label) in enumerate(((0, "front (x-y)"), (2, "side (z-y)"))):
            a = axs[row, col]
            for i in keep:
                p = par[i]
                if p >= 0 and p in keep:
                    a.plot([J[f, p, hx], J[f, i, hx]], [J[f, p, 1], J[f, i, 1]], "k-", lw=1.5)
            a.axhline(0, color="g", lw=.8)
            cx = J[f, 0, hx]; a.set_xlim(cx - span / 1.5, cx + span / 1.5); a.set_ylim(-0.1, 2.3); a.set_aspect("equal")
            a.set_xticks([]); a.set_title(f"f{f} t={f/fps:.2f}s" if row == 0 else label, fontsize=8)
    fig.tight_layout(); fig.savefig(clip_dir / "sticks.png", dpi=80); plt.close(fig)
    # numbers
    stand = np.median(hips[:15]); pk = hips.max(); ip = hips.argmax()
    air = (low_foot > low_foot[:15].mean() + 0.05)
    print(f"{clip_dir.name}: standing hips y~{stand:.3f} m, peak {pk:.3f} m at t={ip/fps:.2f}s (rise {pk-stand:.3f}); "
          f"lowest-foot baseline {np.median(low_foot[:15]):.3f}, max {low_foot.max():.3f}; airborne frames(foot>5cm) {air.sum()} = {air.sum()/fps:.2f}s; "
          f"min joint y {lowest.min():.3f}")


if __name__ == "__main__":
    for p in sys.argv[1:]: run(p)
