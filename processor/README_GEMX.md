# GEM-X Body Motion on native Windows 11 (RTX 5070, Blackwell sm_120)

Status: works end to end. No third_party edits were needed (`patches/` is empty).

## Versions
- Python 3.12.16 (uv venv `.venv`), torch 2.11.0+cu128, torchvision 0.26.0+cu128 (sm_120 OK)
- gem-x commit 4aef1452e5df171deff4f293c08e643eb450981e (NVlabs/gem-x, shallow clone in third_party/gem-x)
- py-soma-x 0.3.3, SOMA-X assets revision v0.3.3, warp-lang 1.19.0, transformers 5.3.0, onnxruntime 1.31.1
- mediapipe 1.2.2 (person boxes), matplotlib 3.11.2

## Install (from C:\GameDev\Espeon\processor)
```
uv venv .venv --python 3.12
git clone --depth 1 https://github.com/NVlabs/gem-x third_party/gem-x
uv pip install --python .venv\Scripts\python.exe torch torchvision --index-url https://download.pytorch.org/whl/cu128
cd third_party/gem-x
uv pip install --python ..\..\.venv\Scripts\python.exe -e ".[video]" --extra-index-url https://download.pytorch.org/whl/cu128
cd ../..
uv pip install --python .venv\Scripts\python.exe mediapipe matplotlib
.venv\Scripts\hf.exe download nvidia/GEM-X body_s240000.ckpt body_config.json vitpose.pth motion_native77_e774_s292175.ckpt motion_stats_raw.npz motion_native77.yaml body_video_native77.yaml --local-dir models/gem-x
.venv\Scripts\hf.exe download nvidia/SOMA-X SOMA_neutral.npz SOMA_template_rig.usda SOMA_procedural_transforms.json --revision v0.3.3 --local-dir models/soma
curl -L -o models/pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
```
`configs/body_video_native77.yaml` = the HF yaml with paths pointed at `../models/gem-x/` and `camera.mode: static`.
(`models/soma/correctives_model.pt` is NOT downloaded: only needed for mesh rendering.)

## Run
```
.venv\Scripts\python.exe -m espeon_processor.video  <raw.mp4>            # -> out/<clip>/prepared.mp4 (upright, H.264)
bash espeon_processor/run_clip.sh Jump1                                   # prepare + infer + export + plots
# or just inference:
.venv\Scripts\python.exe -m espeon_processor.run_gem motion-video --task body --input out/Jump1/prepared.mp4 ^
  --config configs/body_video_native77.yaml --device cuda --camera-mode static --postprocess ^
  --soma-assets models/soma --output out/Jump1/motion.pt
.venv\Scripts\python.exe -m espeon_processor.gem_export out/Jump1/motion.pt   # -> gem_world.npz
.venv\Scripts\python.exe -m espeon_processor.sanity out/Jump1                 # -> root_height.png, sticks.png
```
`motion-video` has no `--boxes` option (only pose-image/pose-video do). `espeon_processor/run_gem.py` therefore
monkeypatches `gem.inference.body_video.detect_track` / `YOLOXONNX` at runtime to use MediaPipe Pose Landmarker (heavy,
Apache-2.0) boxes (`boxes.py`: landmark bbox + 15% pad, gap interpolation, 9-frame smoothing, then GEM's own
smooth_boxes/square_crop). No YOLOX HumanArt is used or downloaded.

## Weights / licenses
| file | size | source / license |
|---|---|---|
| body_s240000.ckpt | 3.54 GB | nvidia/GEM-X, NVIDIA Open Model License (HF card: "ready for commercial use"; code Apache-2.0) |
| vitpose.pth | 3.39 GB | nvidia/GEM-X, same |
| motion_native77_e774_s292175.ckpt | 187 MB | nvidia/GEM-X, same |
| motion_stats_raw.npz / body_config.json / *.yaml | 21 KB / 1 KB / ~1 KB | nvidia/GEM-X |
| SOMA_neutral.npz | 26.6 MB | nvidia/SOMA-X @v0.3.3, Apache-2.0 (per HF metadata) |
| SOMA_template_rig.usda | 28.3 MB | same |
| SOMA_procedural_transforms.json | 20 KB | same |
| pose_landmarker_heavy.task | 30.7 MB | Google MediaPipe, Apache-2.0 |
Notes: body backbone is DINOv3 ViT-H/16+ (weights embedded in the GEM-X checkpoint; the GEM-X card does not state a
separate DINOv3 license; upstream DINOv3 is under Meta's custom DINOv3 License - verify before shipping).
GEM-X card says training data is NVIDIA in-house mocap/synthetic plus commercially-licensed real data. The unused
YOLOX HumanArt detector is the part with unclear training-data licensing; we avoid it.

## Timings (RTX 5070 12 GB, 30 fps normalized frames)
Jump1 207 frames: 68 s (first run incl. one-off Warp kernel compile); Jump2 53 s, Jump3 46 s, Jump4 43 s, APoseTurn (481 frames) 83 s.
Peak VRAM (nvidia-smi total used, baseline ~1.1 GB from desktop): ~5.1 GB, i.e. ~4 GB for the pipeline.

## Known issues
- Video is resampled to 30 fps by GEM (fps=30 filter); output is 30 Hz regardless of the source 59.94.
- Contact/IK pins the lowest joint to y=0 globally; feet float 4-10 cm above y=0 for much of the clips and jump
  amplitude is small (hips rise ~0.1-0.2 m). Horizontal root drifts ~1 m over a clip for in-place jumps.
- Contact probabilities are high (~76% of frames "in contact") on Jump1; low-light/dark clothing may degrade 2D keypoints.
- Warnings from soma (sparse tensor beta) and mediapipe (TFLite feedback manager) are harmless. mediapipe pulls opencv-contrib alongside opencv-headless.
