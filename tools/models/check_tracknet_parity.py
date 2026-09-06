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
    """Peak agreement between two heatmap batches, plus non-finite counts.

    The non-finite counts are not decoration. This comparison is built on
    argmax, and argmax over a heatmap that is entirely NaN returns an index
    anyway - the same index on both sides - so a graph whose output has
    collapsed to NaN reports zero peaks moved and a largest shift of 0, which
    reads as perfect parity. The caller has to be able to reject that, and it
    cannot see it from the peak numbers alone.

    That is not hypothetical for this converter: the fp16 InpaintNet graph
    from the same onnxconverter-common path returns NaN on roughly a third of
    chunks at production's length.
    """
    moved, total, max_shift = 0, 0, 0
    for (rx, ry), (gx, gy) in zip(peaks(hm_ref), peaks(hm_got)):
        total += 1
        shift = max(abs(rx - gx), abs(ry - gy))
        if shift:
            moved += 1
            max_shift = max(max_shift, shift)
    nonfinite_ref = int((~np.isfinite(hm_ref)).sum())
    nonfinite_got = int((~np.isfinite(hm_got)).sum())
    return moved, total, max_shift, nonfinite_ref, nonfinite_got


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
    nonfinite_ref, nonfinite_got = 0, 0

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
                m, t, s, nf_ref, nf_got = compare(hm_ref, hm_got)
                moved += m
                total += t
                max_shift = max(max_shift, s)
                nonfinite_ref += nf_ref
                nonfinite_got += nf_got
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
            m, t, s, nf_ref, nf_got = compare(hm_ref, hm_got)
            moved += m
            total += t
            max_shift = max(max_shift, s)
            nonfinite_ref += nf_ref
            nonfinite_got += nf_got
        source = "synthetic noise (fallback, no --video given)"

    print(f"input source      : {source}")
    print(f"heatmaps compared : {total}")
    print(f"peaks moved       : {moved} ({100 * moved / max(total, 1):.2f}%)")
    print(f"largest shift     : {max_shift} px (at 512x288)")
    print(f"non-finite outputs: torch {nonfinite_ref}, onnx {nonfinite_got}")

    # Checked BEFORE the peak bound, and independently of --video, because a
    # non-finite heatmap makes the peak numbers meaningless rather than good:
    # argmax over an all-NaN heatmap still returns an index, the same one on
    # both sides, so this run would otherwise print "0 peaks moved" and pass.
    # Unlike the peak bound, this needs no real video to be trustworthy - a
    # graph that emits NaN on any input at all is broken, and noise is a
    # perfectly good input for asking that question.
    if nonfinite_got:
        print(f"PARITY FAILED - the ONNX graph returned {nonfinite_got} non-finite output "
              f"values. Peak agreement is not meaningful across a NaN heatmap and is not "
              f"reported as a pass here. Do not bundle this graph.")
        return 1
    if nonfinite_ref:
        print(f"UNMEASURABLE - the torch reference itself returned {nonfinite_ref} "
              f"non-finite values, so there is nothing to compare the export against.",
              file=sys.stderr)
        return 2

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
