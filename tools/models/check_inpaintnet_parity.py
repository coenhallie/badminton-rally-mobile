#!/usr/bin/env python3
"""PyTorch versus ONNX Runtime for InpaintNet, on synthetic trajectory input.

A sibling to check_tracknet_parity.py rather than an extension of it, because
InpaintNet's real input is not raw video frames - it is an (x, y, visibility)
trajectory chunk already produced by running TrackNet plus blob detection
over a real video (backend/tracknet/inference.py:338-430, _run_inpaintnet).
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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--onnx", default="tools/models/onnx/inpaintnet.fp16.onnx",
                     help="path to the exported InpaintNet fp16 ONNX model")
    ap.add_argument("--weights", default="tools/models/weights/inpaintnet.pt")
    ap.add_argument("--trials", type=int, default=32, help="synthetic-trajectory trials")
    ap.add_argument("--length", type=int, default=256,
                     help="trajectory chunk length per trial; production's chunk_size is "
                          "256, but any multiple of 8 is valid since the ONNX export uses "
                          "a dynamic length axis")
    args = ap.parse_args()

    model = _load_torch_inpaintnet(args.tracker_repo, args.weights)
    sess = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name

    rng = np.random.default_rng(0)
    max_shift = 0.0
    for _ in range(args.trials):
        inp = _synthetic_trajectory(rng, args.length)
        with torch.no_grad():
            pred_ref = model(torch.from_numpy(inp)).numpy()
        pred_got = sess.run(None, {input_name: inp})[0]
        max_shift = max(max_shift, _shift_px(pred_ref, pred_got))

    print("input source      : synthetic trajectory (no real-input mode; see module docstring)")
    print(f"trials             : {args.trials}, length={args.length}")
    print(f"largest shift      : {max_shift:.3f} px-equivalent (at 512x288)")
    print("PARITY INCONCLUSIVE (synthetic input only; there is no --video-equivalent "
          "real mode for InpaintNet, see module docstring for why)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
