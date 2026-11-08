"""Generate a synthetic motion.json from the Manny skeleton profile (standard library only).

Usage: python make_test_motion.py <ue5_manny.json> <out motion.json>
Rotations equal the reference pose, plus a sine wave on upperarm_l and spine_01;
root rises 0 -> 30 cm -> 0 on Z over 2 seconds at 60 fps.
"""
import json
import math
import sys

BODY = ("pelvis spine_01 spine_02 spine_03 spine_04 spine_05 neck_01 neck_02 head "
        "clavicle_l clavicle_r upperarm_l upperarm_r lowerarm_l lowerarm_r hand_l hand_r "
        "thigh_l thigh_r calf_l calf_r foot_l foot_r ball_l ball_r").split()
FPS = 60
FRAMES = 2 * FPS
AMP_DEG = 35.0


def qmul(a, b):  # (x, y, z, w) Hamilton product a*b
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return [aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz]


def main(skel_path, out_path):
    skel = json.load(open(skel_path))
    ref = {b["name"]: b["ref_local"] for b in skel["bones"]}
    bones = [n for n in ["root"] + BODY if n in ref]
    pelvis_t = ref["pelvis"]["t"]
    frames, root_t, pelvis_ts = [], [], []
    for f in range(FRAMES):
        phase = f / (FRAMES - 1)
        ang = math.radians(AMP_DEG) * math.sin(2 * math.pi * 2 * phase)
        delta = [math.sin(ang / 2), 0, 0, math.cos(ang / 2)]  # about local X
        row = []
        for n in bones:
            q = list(ref[n]["r"])
            if n in ("upperarm_l", "spine_01"):
                q = qmul(q, delta)  # local-space offset applied after ref rotation
            row.append(q)
        frames.append(row)
        root_t.append([0, 0, 30.0 * math.sin(math.pi * phase)])
        pelvis_ts.append(list(pelvis_t))
    json.dump({"format": "espeon-motion", "version": "1.0", "skeleton": "ue5_manny",
               "fps": FPS, "frame_count": FRAMES, "bones": bones,
               "root_translation": root_t, "pelvis_translation": pelvis_ts,
               "local_rotations": frames}, open(out_path, "w"))
    print("wrote", out_path, len(bones), "bones", FRAMES, "frames")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
