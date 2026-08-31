#!/usr/bin/env python3
"""PyTorch versus ONNX Runtime for InpaintNet, on synthetic trajectory input.

A sibling to check_tracknet_parity.py rather than an extension of it, because
InpaintNet's real input is not raw video frames - it is an (x, y, visibility)
trajectory chunk already produced by running TrackNet plus blob detection
over a real video (backend/tracknet/inference.py:338-469, _run_inpaintnet).
That means check_tracknet_parity.py's --video mode (decode frames, stack,
feed the model) has no InpaintNet equivalent without first trusting the very
TrackNet ONNX conversion that script exists to gate - bootstrapping that
dependency here would make this script's result depend on another script's
untested output, which is worse than being honest about not having a real
mode at all.

Consequently this script is synthetic-input only, and its result is always
reported as INCONCLUSIVE, never as a blocking PARITY FAILED - the same
reasoning check_tracknet_parity.py uses for its synthetic fallback applies
here too: InpaintNet also ends in a sigmoid (backend/tracknet/model.py,
InpaintNet.forward's final `torch.sigmoid(self.predictor(d1))`), so on
uniform random input the output is pushed toward a near-flat region that is
maximally sensitive to fp16 rounding, unlike the correlated, mostly-in-range
trajectories InpaintNet actually sees in production. A large measured shift
here is not evidence the conversion is broken; it is evidence this input is
adversarial, same as check_tracknet_parity.py's noise fallback.

Compares predicted [x, y] coordinates directly (there is no "peak" to find in
a continuous regression output), converted to a pixel-equivalent distance in
the 512x288 model space InpaintNet's normalized [0, 1] coordinates are scaled
into elsewhere in the pipeline.

SWEEPS LENGTHS BY DEFAULT, DOES NOT TEST ONLY ONE. export_tracknet.py's
_export_inpaintnet traces the graph at a single dummy length (256) and marks
the length axis dynamic, but whether torch's ONNX exporter actually emits a
length-agnostic Resize node (`scales`) for the model's three
nn.Upsample(scale_factor=2, mode="linear") calls (model.py:186, hit three
times in InpaintNet.forward), or instead bakes in a `sizes` constant derived
from the traced length, is version-dependent and not something tracing
itself reveals. If sizes got baked, every chunk whose length matches (or
happens to still divide out correctly from) 256 would pass and only a
shorter, real trailing chunk would fail - and it would fail at the Concat
node, not gracefully, because the `if d.shape[2] != e.shape[2]: d = d[:, :, :n]`
guards in InpaintNet.forward (model.py:202,208,214) are themselves traced
away for any length that is a multiple of 8 (see the comment in
export_tracknet.py's _export_inpaintnet for why). A single fixed --length
would therefore prove nothing about any other length. Production's own
range is confirmed by inference.py: chunks below 16 are dropped
(inference.py:379's `if end - start < 16: break`), chunk_size caps at 256
(inference.py:367), and every real chunk is rounded up to a multiple of 8
(inference.py:401) - so the default sweep below spans that range.
"""
import argparse, sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

MODEL_W, MODEL_H = 512, 288


def _load_torch_inpaintnet(tracker_repo, weights_path):
    sys.path.insert(0, str(Path(tracker_repo) / "backend"))
    from tracknet.model import InpaintNet

    # weights_only=False: same reason as export_tracknet.py - this checkpoint
    # carries more than tensors and torch 2.6+ refuses to unpickle it otherwise.
    ckpt = torch.load(str(weights_path), map_location="cpu", weights_only=False)
    model = InpaintNet()
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()
    return model


def _synthetic_trajectory(rng, length: int) -> np.ndarray:
    """(1, 3, length) float32: [x, y, visibility], values in [0, 1]."""
    x = rng.random((1, length), dtype=np.float32)
    y = rng.random((1, length), dtype=np.float32)
    vis = (rng.random((1, length), dtype=np.float32) > 0.3).astype(np.float32)
    return np.stack([x, y, vis], axis=1).astype(np.float32)  # (1, 3, length)


def _shift_px(pred_ref: np.ndarray, pred_got: np.ndarray) -> float:
    """Max pixel-equivalent distance between two (1, 2, L) predictions."""
    dx = (pred_ref[0, 0] - pred_got[0, 0]) * MODEL_W
    dy = (pred_ref[0, 1] - pred_got[0, 1]) * MODEL_H
    dist = np.sqrt(dx * dx + dy * dy)
    return float(dist.max())


# Production's real range: chunks under 16 frames are dropped (inference.py:379),
# chunk_size caps at 256 (inference.py:367), and every real chunk is rounded up
# to a multiple of 8 (inference.py:401). This sweep's boundary values (16, 24,
# 248, 256) are the ones most likely to expose a torch ONNX exporter that baked
# a `sizes` constant into the Resize nodes instead of emitting `scales` - see
# the module docstring - since 256 is what export_tracknet.py traces at.
DEFAULT_LENGTHS = "16,24,32,64,128,136,192,248,256"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--onnx", default="tools/models/onnx/inpaintnet.fp16.onnx",
                     help="path to the exported InpaintNet fp16 ONNX model")
    ap.add_argument("--weights", default="tools/models/weights/inpaintnet.pt")
    ap.add_argument("--trials", type=int, default=8, help="synthetic-trajectory trials per length")
    ap.add_argument("--lengths", default=DEFAULT_LENGTHS,
                     help="comma-separated trajectory chunk lengths to sweep. Defaults to "
                          f"'{DEFAULT_LENGTHS}', covering production's real range "
                          "(16 to 256, always a multiple of 8) including its boundaries. "
                          "A single --lengths 256 run only proves the exact length "
                          "export_tracknet.py traced at, which is not sufficient - see "
                          "the module docstring for why.")
    args = ap.parse_args()

    lengths = [int(v) for v in args.lengths.split(",") if v.strip()]

    model = _load_torch_inpaintnet(args.tracker_repo, args.weights)
    sess = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name

    rng = np.random.default_rng(0)
    print("input source      : synthetic trajectory (no real-input mode; see module docstring)")
    print(f"trials per length  : {args.trials}")
    overall_max_shift = 0.0
    for length in lengths:
        max_shift = 0.0
        for _ in range(args.trials):
            inp = _synthetic_trajectory(rng, length)
            with torch.no_grad():
                pred_ref = model(torch.from_numpy(inp)).numpy()
            pred_got = sess.run(None, {input_name: inp})[0]
            max_shift = max(max_shift, _shift_px(pred_ref, pred_got))
        overall_max_shift = max(overall_max_shift, max_shift)
        print(f"  length={length:4d}      : largest shift {max_shift:.3f} px-equivalent (at 512x288)")

    print(f"largest shift (any length): {overall_max_shift:.3f} px-equivalent (at 512x288)")
    print("PARITY INCONCLUSIVE (synthetic input only; there is no --video-equivalent "
          "real mode for InpaintNet, see module docstring for why)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
