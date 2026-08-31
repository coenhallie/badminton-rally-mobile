#!/usr/bin/env python3
"""PyTorch versus ONNX Runtime on identical input.

Compares heatmap PEAK LOCATIONS, not raw tensors. The pipeline only ever uses
the argmax of each heatmap, so a small activation difference that leaves the
peak in the same pixel is harmless, and a large one that moves it is not.
Comparing tensors directly would flag the first and could hide the second.
"""
import argparse, sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch


def peaks(hm: np.ndarray) -> list[tuple[int, int]]:
    out = []
    for i in range(hm.shape[1]):
        flat = int(np.argmax(hm[0, i]))
        out.append((flat % hm.shape[3], flat // hm.shape[3]))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--trials", type=int, default=32)
    args = ap.parse_args()

    sys.path.insert(0, str(Path(args.tracker_repo) / "backend"))
    from tracknet.model import TrackNet

    ckpt = torch.load("tools/models/weights/tracknet.pt", map_location="cpu")
    params = ckpt.get("param_dict", {})
    seq_len = params.get("seq_len", 8)
    in_dim = (seq_len + 1) * 3 if params.get("bg_mode", "concat") == "concat" else seq_len * 3
    model = TrackNet(in_dim=in_dim, out_dim=seq_len)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()

    sess = ort.InferenceSession("tools/models/onnx/tracknet.fp16.onnx",
                                providers=["CPUExecutionProvider"])

    moved, total, max_shift = 0, 0, 0
    rng = np.random.default_rng(0)
    for _ in range(args.trials):
        x = rng.random((1, in_dim, 288, 512), dtype=np.float32)
        with torch.no_grad():
            ref = model(torch.from_numpy(x)).numpy()
        got = sess.run(None, {"frames": x})[0]
        for (rx, ry), (gx, gy) in zip(peaks(ref), peaks(got)):
            total += 1
            shift = max(abs(rx - gx), abs(ry - gy))
            if shift:
                moved += 1
                max_shift = max(max_shift, shift)

    print(f"heatmaps compared : {total}")
    print(f"peaks moved       : {moved} ({100 * moved / total:.2f}%)")
    print(f"largest shift     : {max_shift} px (at 512x288)")
    # A peak that moves by 1px at 512x288 is well inside the noise the static
    # cluster filter already tolerates. Anything larger changes shuttle
    # trajectories and therefore shot detection.
    ok = max_shift <= 1
    print("PARITY OK" if ok else "PARITY FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
