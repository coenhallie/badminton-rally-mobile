#!/usr/bin/env python3
"""Dump every person the deployed pose graph finds, per frame, in source pixels.

Same decode, letterbox and graph as pose_metrics_probe.py. Selection is left
to the reader (pose_events_probe.py, pose_rally_report.py) so near/far and
racket-arm questions can be asked offline without re-running inference.

    python tools/models/pose_dump.py --video <source.mp4> --start 100 --frames 1160 --out dump.json

Frames are decoded sequentially from 0. Do not replace this with a
CAP_PROP_POS_FRAMES seek: on the corpus' variable-frame-rate mp4 a seek lands
several frames off, and a pose drawn on the seeked frame looks like a phantom
detection when it is only misaligned.
"""
import argparse
import json
import os
import sys

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pose_metrics_probe import STRIDE, decode, letterbox  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--onnx", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "onnx", "posen.960.fp16.onnx"))
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--frames", type=int, required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sess = ort.InferenceSession(a.onnx, providers=["CPUExecutionProvider"])
    dtype = np.float16 if "float16" in sess.get_inputs()[0].type else np.float32
    name = sess.get_inputs()[0].name
    cap = cv2.VideoCapture(a.video)
    for _ in range(a.start):
        cap.grab()
    out = []
    for i in range(a.start, a.start + a.frames):
        ok, frame = cap.read()
        if not ok:
            break
        t = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        canvas, scale, px, py = letterbox(frame)
        x = (canvas.astype(np.float32) / 255.0).transpose(2, 0, 1)[None].astype(dtype)
        rows = np.asarray(sess.run(None, {name: x})[0], np.float32).reshape(-1, STRIDE)
        people = [{"box": bc, "k": np.round(k, 2).tolist()} for bc, k in decode(rows, scale, px, py)]
        out.append({"frame": i, "t": t, "people": people})
        if i % 200 == 0:
            print(f"frame {i}", file=sys.stderr, flush=True)
    json.dump(out, open(a.out, "w"))
    print(f"wrote {a.out}: {len(out)} frames")


if __name__ == "__main__":
    main()
