"""Run `gem-infer` with MediaPipe boxes instead of YOLOX (motion-video has no --boxes option).

Monkeypatches gem.inference.body_video.{YOLOXONNX,detect_track} at runtime; no third_party edits.
Usage: python -m espeon_processor.run_gem motion-video --task body --config ... [gem-infer args]
"""
from __future__ import annotations
import sys
import numpy as np


class _NoDetector:
    def __init__(self, *a, **k): pass


def mediapipe_detect_track(frames_rgb, detector, *, bbox_enlarge=1.2, tracker_options=None):
    from gem.common.detectors.tracks import TrackResult, smooth_boxes, square_crops
    from .boxes import person_boxes
    xyxy, observed = person_boxes(frames_rgb, fps=30.0, return_observed=True)
    T = len(xyxy)
    idx = np.flatnonzero(observed)
    empty = [np.zeros((0, 4), np.float32) for _ in range(T)]
    return TrackResult(np.arange(T), xyxy, square_crops(smooth_boxes(xyxy), bbox_enlarge),
                       np.zeros(T, np.int32), observed, (int(idx[0]), int(idx[-1])),
                       [xyxy[i:i + 1] for i in range(T)], empty, [xyxy[i:i + 1] for i in range(T)],
                       [np.ones(1, np.float32) for _ in range(T)],
                       ["primary_track" if o else "primary_interpolated" for o in observed])


def main(argv=None):
    import gem.inference.body_video as bv
    bv.YOLOXONNX = _NoDetector
    bv.detect_track = mediapipe_detect_track
    from gem.inference.cli import main as gem_main
    return gem_main(argv if argv is not None else sys.argv[1:])


if __name__ == "__main__":
    main()
