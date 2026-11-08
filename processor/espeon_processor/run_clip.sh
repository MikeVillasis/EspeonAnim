#!/bin/bash
# usage: run_clip.sh ClipName   (run from processor/)
c=$1
PY=.venv/Scripts/python.exe
$PY -m espeon_processor.video /c/GameDev/Espeon/testdata/raw/$c.mp4 >/dev/null
s=$(date +%s)
$PY -m espeon_processor.run_gem motion-video --task body --input out/$c/prepared.mp4 --config configs/body_video_native77.yaml --device cuda --camera-mode static --postprocess --soma-assets models/soma --output out/$c/motion.pt > out/$c/run.log 2>&1
echo "wall_s=$(( $(date +%s)-s ))" >> out/$c/run.log
$PY -m espeon_processor.gem_export out/$c/motion.pt 2>&1 | tail -1
$PY -m espeon_processor.sanity out/$c
