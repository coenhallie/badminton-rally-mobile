#!/usr/bin/env python3
"""Does the device's pose decode agree with the same graph run on the host?

`PoseParityDumpTest` writes every person `PoseRunner` produced for the first N
frames of a corpus video, in source pixels. This decodes the same frames with
OpenCV, letterboxes them the way the runner does, runs the same ONNX graph
through onnxruntime, un-letterboxes, and matches people between the two sides
by keypoint centroid. It reports keypoint displacement in source pixels and
confidence deltas, plus any frame where the two sides found a different number
of people.

What this pins is the device's preprocessing and output layout. The model is
the same file on both sides, so a disagreement can only come from the decode
(MediaCodec vs OpenCV, measured at 1.56/255 mean), the letterbox, or the row
layout the runner assumes. Small residuals are the decode; anything of pixels
is a layout or letterbox slip.

    python tools/models/pose_parity.py --video <source.mp4> --dump pose-dump.csv \
        [--onnx tools/models/onnx/posen.960.fp16.onnx]
"""
import argparse
import csv
import sys
from collections import defaultdict

import cv2
import numpy as np
import onnxruntime as ort

SIZE = 960
PAD = 114
CONFIDENCE = 0.25
PERSON = 0
STRIDE = 6 + 17 * 3


def letterbox(frame_bgr):
    h, w = frame_bgr.shape[:2]
    scale = min(SIZE / w, SIZE / h)
    fit_w, fit_h = int(w * scale), int(h * scale)
    pad_x, pad_y = (SIZE - fit_w) // 2, (SIZE - fit_h) // 2
    rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(rgb, (fit_w, fit_h), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((SIZE, SIZE, 3), PAD, dtype=np.uint8)
    canvas[pad_y:pad_y + fit_h, pad_x:pad_x + fit_w] = resized
    return canvas, scale, pad_x, pad_y


def run(session, canvas, dtype):
    x = canvas.astype(np.float32) / 255.0
    x = np.transpose(x, (2, 0, 1))[None].astype(dtype)
    name = session.get_inputs()[0].name
    out = session.run(None, {name: x})[0]
    return np.asarray(out, dtype=np.float32).reshape(-1, STRIDE)


def decode(rows, scale, pad_x, pad_y):
    people = []
    for r in rows:
        if r[4] < CONFIDENCE:
            break
        if int(r[5]) != PERSON:
            continue
        kps = r[6:].reshape(17, 3).copy()
        kps[:, 0] = (kps[:, 0] - pad_x) / scale
        kps[:, 1] = (kps[:, 1] - pad_y) / scale
        people.append((float(r[4]), kps))
    return people


def load_dump(path):
    by_frame = defaultdict(list)
    with open(path) as f:
        for row in csv.DictReader(f):
            kps = np.array([[float(row[f"x{k}"]), float(row[f"y{k}"]), float(row[f"c{k}"])] for k in range(17)])
            by_frame[int(row["frame"])].append((float(row["box_confidence"]), kps))
    return by_frame


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--dump", required=True)
    ap.add_argument("--onnx", default="tools/models/onnx/posen.960.fp16.onnx")
    args = ap.parse_args()

    device = load_dump(args.dump)
    frames_wanted = max(device) + 1 if device else 0
    if not frames_wanted:
        print("empty dump", file=sys.stderr)
        return 2

    session = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])
    in_type = session.get_inputs()[0].type
    dtype = np.float16 if "float16" in in_type else np.float32

    cap = cv2.VideoCapture(args.video)
    host = {}
    for i in range(frames_wanted):
        ok, frame = cap.read()
        if not ok:
            break
        canvas, scale, px, py = letterbox(frame)
        host[i] = decode(run(session, canvas, dtype), scale, px, py)
    cap.release()

    displacements, conf_deltas, box_deltas = [], [], []
    count_mismatch = []
    unmatched = 0
    for i in sorted(device):
        d, h = device[i], host.get(i, [])
        if len(d) != len(h):
            count_mismatch.append((i, len(d), len(h)))
        used = set()
        for dconf, dk in d:
            best, best_dist = None, 1e9
            for j, (hconf, hk) in enumerate(h):
                if j in used:
                    continue
                dist = float(np.linalg.norm(dk[:, :2].mean(0) - hk[:, :2].mean(0)))
                if dist < best_dist:
                    best, best_dist = j, dist
            if best is None or best_dist > 50:
                unmatched += 1
                continue
            used.add(best)
            hconf, hk = h[best]
            vis = (dk[:, 2] >= 0.5) & (hk[:, 2] >= 0.5)
            displacements.extend(np.hypot(*(dk[vis, :2] - hk[vis, :2]).T).tolist())
            conf_deltas.extend(np.abs(dk[:, 2] - hk[:, 2]).tolist())
            box_deltas.append(abs(dconf - hconf))

    disp = np.array(displacements)
    print(f"frames compared: {len(device)}; people on device: {sum(len(v) for v in device.values())}; "
          f"on host: {sum(len(v) for v in host.values())}")
    print(f"frames where the person count differs: {len(count_mismatch)} {count_mismatch[:8]}")
    print(f"people with no host match within 50px: {unmatched}")
    if len(disp):
        print(f"keypoint displacement, source px (both sides confident, n={len(disp)}): "
              f"median {np.median(disp):.2f}, p90 {np.percentile(disp, 90):.2f}, max {disp.max():.2f}")
        print(f"keypoint confidence |delta|: median {np.median(conf_deltas):.3f}, max {max(conf_deltas):.3f}")
        print(f"box confidence |delta|: median {np.median(box_deltas):.3f}, max {max(box_deltas):.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
