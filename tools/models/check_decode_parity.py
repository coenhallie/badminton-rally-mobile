#!/usr/bin/env python3
"""Record what production's decode and preprocessing produce, frame by frame.

Design section 5.4 names this a fidelity surface and admits it is unmeasured:
"Different interpolation, different colour conversion, and on some containers a
different frame count, all feeding EVERY model on EVERY frame. This is upstream
of the conversion question and is not covered by any gate in this plan."

This writes the reference half. The device half is an instrumented test that
decodes the same frames and diffs against it.

Production's path, which this reproduces exactly:
  - OpenCV VideoCapture, so FFmpeg's decode and its colour handling
  - cv2.resize to 512x288 at the default INTER_LINEAR (inference.py:23-24, 263)
  - cv2.cvtColor BGR to RGB (inference.py:264)
  - scale by 1/255 into CHW at the tensor step (inference.py:471-474)

The reference stores uint8 RGB BEFORE the 1/255 scaling: the division is exact
in both languages and storing bytes keeps the file a quarter the size.

Output format, little-endian to match RawInference:
  magic "DPAR", int32 version, int32 count, int32 width, int32 height,
  then per frame: int32 frame_index, height*width*3 uint8 RGB.
"""
import argparse
import struct
import sys
from pathlib import Path

import cv2
import numpy as np

WIDTH, HEIGHT = 512, 288
MAGIC = b"DPAR"
VERSION = 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--out", default="tools/models/reports/decode-reference.bin")
    ap.add_argument("--samples", type=int, default=24)
    args = ap.parse_args()

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        print(f"could not open {args.video}", file=sys.stderr)
        return 2
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

    # Spread across the video, not the first N. Decoder differences concentrate
    # at keyframe boundaries and after seeks, so sampling the opening would
    # measure the easiest part of the file and call it representative.
    indices = sorted(set(np.linspace(0, total - 1, args.samples, dtype=int).tolist()))

    frames = {}
    wanted = set(indices)
    idx = 0
    while wanted:
        ok, frame = cap.read()
        if not ok:
            break
        if idx in wanted:
            # Exactly production's order: resize first, then BGR to RGB.
            # Swapping them changes which channel the interpolation mixes.
            resized = cv2.resize(frame, (WIDTH, HEIGHT))
            frames[idx] = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
            wanted.discard(idx)
        idx += 1
    cap.release()

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<iiii", VERSION, len(frames), WIDTH, HEIGHT))
        for i in sorted(frames):
            f.write(struct.pack("<i", i))
            f.write(frames[i].astype(np.uint8).tobytes())

    print(f"video          : {args.video}")
    print(f"total frames   : {total}")
    print(f"sampled        : {len(frames)} of {len(indices)} requested")
    print(f"frame indices  : {sorted(frames)[:8]}{' ...' if len(frames) > 8 else ''}")
    print(f"wrote          : {out} ({out.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
