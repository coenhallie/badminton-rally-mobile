#!/usr/bin/env python3
"""PyTorch versus ONNX Runtime on identical input.

Compares heatmap PEAK LOCATIONS, not raw tensors. The pipeline only ever uses
the argmax of each heatmap, so a small activation difference that leaves the
peak in the same pixel is harmless, and a large one that moves it is not.
Comparing tensors directly would flag the first and could hide the second.

Prefer --video: real decoded frames exercise the model over its actual
operating range. TrackNet ends in a sigmoid (backend/tracknet/model.py:120),
so on uniform random noise (the synthetic fallback below) the whole heatmap
comes out nearly flat, and the argmax of a near-flat field is maximally
sensitive to fp16 rounding - a shift far past what real, peaked activations
would ever show. That is the opposite of "if noise passes, real footage
will too": noise is the adversarial case here, not a conservative stand-in
for real footage. A synthetic-only run is therefore reported as
INCONCLUSIVE, never as a blocking PARITY FAILED.
"""
import argparse, sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common as common


def peaks(hm: np.ndarray) -> list:
    out = []
    for i in range(hm.shape[1]):
        flat = int(np.argmax(hm[0, i]))
        out.append((flat % hm.shape[3], flat // hm.shape[3]))
    return out


def compare(hm_ref: np.ndarray, hm_got: np.ndarray):
    moved, total, max_shift = 0, 0, 0
    for (rx, ry), (gx, gy) in zip(peaks(hm_ref), peaks(hm_got)):
        total += 1
        shift = max(abs(rx - gx), abs(ry - gy))
        if shift:
            moved += 1
            max_shift = max(max_shift, shift)
    return moved, total, max_shift


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--onnx", default=common.ONNX_DEFAULT, help="path to the exported fp16 ONNX model")
    ap.add_argument("--video", default=None,
                     help="real mp4 to decode frames from (preferred); falls back to "
                          "synthetic noise if omitted, which only gives an INCONCLUSIVE result")
    ap.add_argument("--trials", type=int, default=32,
                     help="video batches to compare if --video is given, else random-noise trials")
    args = ap.parse_args()

    model, in_dim, seq_len = common.load_torch_model(args.tracker_repo)
    sess = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])

    moved, total, max_shift = 0, 0, 0

    if args.video:
        try:
            cap = common.open_video(args.video)
        except RuntimeError as e:
            print(e, file=sys.stderr)
            return 2

        try:
            n_batches = 0
            for _start, stack, _count in common.iter_batches(cap, seq_len):
                if n_batches >= args.trials:
                    break
                hm_ref = common.run_torch(model, stack)
                hm_got = common.run_onnx(sess, stack)
                m, t, s = compare(hm_ref, hm_got)
                moved += m
                total += t
                max_shift = max(max_shift, s)
                n_batches += 1
        finally:
            cap.release()
        if total == 0:
            print(f"no frames decoded from --video: {args.video}", file=sys.stderr)
            return 2
        source = f"real video ({args.video})"
    else:
        rng = np.random.default_rng(0)
        for _ in range(args.trials):
            x = rng.random((1, in_dim, 288, 512), dtype=np.float32)
            hm_ref = common.run_torch(model, x)
            hm_got = common.run_onnx(sess, x)
            m, t, s = compare(hm_ref, hm_got)
            moved += m
            total += t
            max_shift = max(max_shift, s)
        source = "synthetic noise (fallback, no --video given)"

    print(f"input source      : {source}")
    print(f"heatmaps compared : {total}")
    print(f"peaks moved       : {moved} ({100 * moved / max(total, 1):.2f}%)")
    print(f"largest shift     : {max_shift} px (at 512x288)")

    # A peak that moves by 1px at 512x288 is well inside the noise the static
    # cluster filter already tolerates. Anything larger changes shuttle
    # trajectories and therefore shot detection. This bound is only a
    # pass/fail gate when it was measured on real video; see the module
    # docstring for why noise cannot be graded against it.
    ok = max_shift <= 1

    if not args.video:
        print("PARITY INCONCLUSIVE (synthetic input only; rerun with --video for a real gate)")
        return 0

    print("PARITY OK" if ok else "PARITY FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
