#!/usr/bin/env python3
"""Quantize TrackNet to int8, calibrated on real frames.

Design section 8 defers int8 "until fp16 numbers exist to compare against",
calling it "the change that would trigger ref section 8.1's distance
inflation". Those numbers now exist - fp16 moves zero heatmap peaks over 256
real frames on two videos - so the comparison this was waiting for can be made.

Why it is worth trying: TrackNet is 18 convolutions and inference is 69% of the
on-device Phase 1 cost, and int8 convolution kernels are where ONNX Runtime's
CPU provider is fastest.

Why it might not work: the model ends in a sigmoid and the postprocessing
thresholds that output at 0.5. Quantization error near the threshold flips a
blob in or out of existence, which is a visibility change rather than a
position change - exactly the failure the coverage gate's
`visibility_agreement` term is built to catch.

Calibration uses production's own preprocessing and its median background, so
the calibration distribution is the deployment distribution. Static rather than
dynamic quantization because dynamic quantizes only weights and leaves
convolution activations in float, which is most of the win here.

Usage:
    python tools/models/quantize_tracknet.py <video> [--sequences 24]
"""
import argparse
import sys
from pathlib import Path

import numpy as np


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--tracker-repo", default="../badminton-tracker")
    ap.add_argument("--model", default="tools/models/onnx/tracknet.onnx")
    ap.add_argument("--out", default="tools/models/onnx/tracknet.int8.onnx")
    ap.add_argument("--sequences", type=int, default=24,
                    help="calibration sequences, 8 frames each")
    ap.add_argument("--keep-head-float", action="store_true",
                    help="leave the predictor convolution and the final "
                         "up-block in float precision")
    args = ap.parse_args()

    sys.path.insert(0, str(Path(args.tracker_repo).resolve() / "backend"))
    import cv2
    from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static
    from tracknet.inference import TrackNetInference

    WIDTH, HEIGHT, SEQ = 512, 288, 8

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        print(f"could not open {args.video}", file=sys.stderr)
        return 2
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    cap.release()

    # Production's own background, so calibration sees the same extra three
    # channels deployment will.
    inf = TrackNetInference.__new__(TrackNetInference)
    bg = TrackNetInference._compute_median_background(inf, args.video, total, 300)
    bg_chw = bg.astype(np.float32).transpose(2, 0, 1) / 255.0

    # Sequences spread across the video rather than from the start: rally and
    # idle frames have very different activation ranges, and calibrating on
    # only one of them sets the scales wrong for the other.
    starts = np.linspace(0, max(0, total - SEQ - 1), args.sequences, dtype=int)

    cap = cv2.VideoCapture(args.video)
    inputs = []
    for start in starts:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(start))
        planes = [bg_chw]
        ok_all = True
        for _ in range(SEQ):
            ok, frame = cap.read()
            if not ok:
                ok_all = False
                break
            resized = cv2.resize(frame, (WIDTH, HEIGHT))
            rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
            planes.append(rgb.astype(np.float32).transpose(2, 0, 1) / 255.0)
        if ok_all:
            inputs.append(np.concatenate(planes, axis=0)[None].astype(np.float32))
    cap.release()
    print(f"calibration sequences: {len(inputs)} ({len(inputs) * SEQ} frames)")

    class Reader(CalibrationDataReader):
        def __init__(self, data):
            self.it = iter(data)

        def get_next(self):
            x = next(self.it, None)
            return None if x is None else {"frames": x}

    # The output head decides the heatmap values the postprocessing thresholds
    # at 0.5 and weights a centroid by. uint8 activations give 256 levels
    # across the whole range, and the difference between a blob existing and
    # not existing lives in a few of them.
    exclude = [
        "/predictor/Conv",
        "/up_block_3/conv_1/conv/Conv",
        "/up_block_3/conv_2/conv/Conv",
    ] if args.keep_head_float else []
    if exclude:
        print(f"keeping in float: {', '.join(exclude)}")

    out = Path(args.out)
    quantize_static(
        model_input=args.model,
        model_output=str(out),
        calibration_data_reader=Reader(inputs),
        quant_format=QuantFormat.QDQ,
        # Convolution weights signed, activations unsigned: the standard pairing
        # for CPU int8 convolution kernels, and the one ONNX Runtime optimises.
        weight_type=QuantType.QInt8,
        activation_type=QuantType.QUInt8,
        per_channel=True,
        nodes_to_exclude=exclude,
    )
    print(f"wrote {out} ({out.stat().st_size:,} bytes)")
    src = Path(args.model)
    print(f"  fp32 was {src.stat().st_size:,} bytes "
          f"({src.stat().st_size / out.stat().st_size:.1f}x larger)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
