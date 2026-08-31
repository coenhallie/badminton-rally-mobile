#!/usr/bin/env python3
"""TrackNetV3 to ONNX at fp16.

Input is (1, 27, 288, 512): seq_len=8 frames plus one background frame, three
channels each, at the 512x288 the model was trained on. Output is
(1, 8, 288, 512), one heatmap per input frame.

Static shapes deliberately. The mobile runtimes prefer them, and the sequence
length is fixed by the checkpoint anyway.
"""
import argparse, sys
from pathlib import Path

import torch


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--out", default="tools/models/onnx/tracknet.onnx")
    args = ap.parse_args()

    sys.path.insert(0, str(Path(args.tracker_repo) / "backend"))
    from tracknet.model import TrackNet

    # weights_only=False: torch 2.6+ defaults to True, which refuses to
    # unpickle this checkpoint because it carries param_dict (seq_len,
    # bg_mode) alongside the state dict, not just tensors. Do not drop this.
    ckpt = torch.load(
        "tools/models/weights/tracknet.pt", map_location="cpu", weights_only=False
    )
    params = ckpt.get("param_dict", {})
    seq_len = params.get("seq_len", 8)
    bg_mode = params.get("bg_mode", "concat")
    in_dim = (seq_len + 1) * 3 if bg_mode == "concat" else seq_len * 3
    print(f"seq_len={seq_len} bg_mode={bg_mode} in_dim={in_dim}")

    model = TrackNet(in_dim=in_dim, out_dim=seq_len)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()

    dummy = torch.randn(1, in_dim, 288, 512)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model, dummy, str(out),
        input_names=["frames"], output_names=["heatmaps"],
        opset_version=17, dynamic_axes=None,
    )

    # fp16 as a separate pass so the fp32 graph exists for the parity check.
    import onnx
    from onnxconverter_common import float16
    m16 = float16.convert_float_to_float16(onnx.load(str(out)), keep_io_types=True)
    onnx.save(m16, str(out.with_suffix(".fp16.onnx")))
    print(f"wrote {out} and {out.with_suffix('.fp16.onnx')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
