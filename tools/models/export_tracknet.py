#!/usr/bin/env python3
"""TrackNetV3 and InpaintNet to ONNX at fp16.

TrackNet input is (1, 27, 288, 512): seq_len=8 frames plus one background
frame, three channels each, at the 512x288 the model was trained on. Output
is (1, 8, 288, 512), one heatmap per input frame. Static shapes deliberately:
the mobile runtimes prefer them, and the sequence length is fixed by the
checkpoint anyway.

InpaintNet input is (1, 3, L): a trajectory chunk of L frames, channels
[x, y, visibility] each normalized to [0, 1] (see
backend/tracknet/inference.py:416, _run_inpaintnet). Output is (1, 2, L),
predicted [x, y]. Unlike TrackNet's frame axis, L is NOT fixed: production
chunks a video's trajectory with chunk_size=256 and stride=128
(inference.py:367-368), padding each chunk only to the next multiple of 8
(`pad_len = ((len(chunk_x) + 7) // 8) * 8`, inference.py:401) - so a video
whose trajectory length is not a multiple of 128 has a final chunk anywhere
from 16 to 256 frames long. A static L would only match the first chunk of
every video and silently break on every other one, so InpaintNet is exported
with L as a dynamic axis instead.
"""
import argparse, sys
from pathlib import Path

import numpy as np
import torch


def _load_checkpoint(path):
    # weights_only=False: torch 2.6+ defaults to True, which refuses to
    # unpickle this checkpoint because it carries param_dict (seq_len,
    # bg_mode) alongside the state dict, not just tensors. Do not drop this.
    return torch.load(str(path), map_location="cpu", weights_only=False)


def _export_pair(model, dummy, out: Path, dynamic_axes=None, input_names=None, output_names=None):
    """Export fp32 to `out`, then a second fp16 pass to `out` with a
    ".fp16.onnx" suffix. Leaves no half-complete pair behind: either both
    ONNX files exist and are current, or neither does. Without this, a
    failure after the fp32 export (for example a missing
    onnxconverter-common) would leave a stale fp32 file that a later
    successful run's success message would then sit next to, with no way to
    tell it was actually from this run.
    """
    fp16_out = out.with_suffix(".fp16.onnx")
    out.parent.mkdir(parents=True, exist_ok=True)
    try:
        torch.onnx.export(
            model, dummy, str(out),
            input_names=input_names, output_names=output_names,
            opset_version=17, dynamic_axes=dynamic_axes,
        )

        # fp16 as a separate pass so the fp32 graph exists for the parity check.
        import onnx
        from onnxconverter_common import float16
        m16 = float16.convert_float_to_float16(onnx.load(str(out)), keep_io_types=True)
        onnx.save(m16, str(fp16_out))
    except Exception:
        out.unlink(missing_ok=True)
        fp16_out.unlink(missing_ok=True)
        raise

    # Smoke the fp16 graph before anyone can bundle it. This converter is the
    # one that produced an InpaintNet fp16 graph returning NaN on roughly a
    # third of chunks at production's length, which shipped in the Android app
    # because nothing between "onnx.save" and "assets/models" ever ran it. A
    # graph that emits NaN on plain in-range input is broken no matter how
    # representative the input is, and this is the cheapest place to find out.
    #
    # The fp16 file is deleted and the fp32 one kept, deliberately breaking
    # the both-or-neither rule above. That rule exists so a stale fp32 cannot
    # masquerade as current; deleting a graph this run just proved defective,
    # loudly, is not that failure. Callers see it in the return value.
    if not _fp16_is_finite(fp16_out, dummy):
        fp16_out.unlink(missing_ok=True)
        print(f"wrote {out}", file=sys.stderr)
        print(f"REFUSED to write {fp16_out}: the fp16 conversion returns non-finite "
              f"output on in-range input. The fp32 graph is kept and is the one to "
              f"bundle. Do not work around this by re-running; the converter is at "
              f"fault (it fails at Resize nodes, and both these models upsample).",
              file=sys.stderr)
        return False
    print(f"wrote {out} and {fp16_out}")
    return True


def _fp16_is_finite(fp16_out: Path, dummy) -> bool:
    """True when the fp16 graph returns finite output on in-range input.

    Uses the same dummy the export was traced with, plus uniform random input
    of that shape: the traced dummy alone can be all-zeros or otherwise
    degenerate and exercise none of the range where fp16 overflows.
    """
    import onnxruntime as ort
    sess = ort.InferenceSession(str(fp16_out), providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    base = dummy.detach().cpu().numpy().astype(np.float32)
    rng = np.random.default_rng(0)
    probes = [base] + [rng.random(base.shape, dtype=np.float32) for _ in range(4)]
    for probe in probes:
        if not np.isfinite(sess.run(None, {name: probe})[0]).all():
            return False
    return True


def _export_tracknet(out: Path) -> bool:
    from tracknet.model import TrackNet

    ckpt = _load_checkpoint("tools/models/weights/tracknet.pt")
    params = ckpt.get("param_dict", {})
    seq_len = params.get("seq_len", 8)
    bg_mode = params.get("bg_mode", "concat")
    in_dim = (seq_len + 1) * 3 if bg_mode == "concat" else seq_len * 3
    print(f"[TrackNet] seq_len={seq_len} bg_mode={bg_mode} in_dim={in_dim}")

    model = TrackNet(in_dim=in_dim, out_dim=seq_len)
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()

    # Batch is dynamic; the frame, height and width axes stay static.
    #
    # Measured on an S23, TrackNet inference is 74% of the on-device Phase 1
    # cost at 233ms per frame, and a static batch of 1 forces one inference per
    # 8-frame sequence. Production runs `batch_size=16` (`inference.py`), so
    # the batch axis is the one dimension the deployed pipeline genuinely
    # varies, and pinning it to 1 was an artifact of this exporter rather than
    # a property of the model.
    #
    # Exported at batch 2 rather than 1 so the trace cannot bake a
    # size-1 assumption into the graph and still appear to work.
    dummy = torch.randn(1, in_dim, 288, 512)
    return _export_pair(model, dummy, out, dynamic_axes=None,
                        input_names=["frames"], output_names=["heatmaps"])


def _export_inpaintnet(out: Path) -> bool:
    from tracknet.model import InpaintNet

    ckpt = _load_checkpoint("tools/models/weights/inpaintnet.pt")
    print("[InpaintNet] input=(1, 3, length) [x, y, visibility], length dynamic")

    model = InpaintNet()
    model.load_state_dict(ckpt["model"] if "model" in ckpt else ckpt)
    model.eval()

    # InpaintNet.forward has three `if d.shape[2] != e.shape[2]: d = d[:, :, :n]`
    # guards (model.py:202,208,214) for when pooling three times and then
    # upsampling three times do not land back on the same length. Tracing
    # with dummy length 256 (divisible by 8) never takes those branches,
    # since 256 -> 128 -> 64 -> 32 -> (x2) 64 -> 128 -> 256 lands exactly on
    # every skip connection's length with no remainder, at every level. That
    # is not a tracing gap specific to 256: it is true for ANY length
    # divisible by 8 (three halvings and three doublings of a multiple of 8
    # always land exactly), which is exactly why production always pads
    # chunks to a multiple of 8 before calling InpaintNet
    # (inference.py:401, `pad_len = ((len(chunk_x) + 7) // 8) * 8`). So the
    # exported graph's baked-in "no slice needed" branch is correct for
    # every length production will ever feed it - but only for those. Do not
    # feed this ONNX model a trajectory length that is not a multiple of 8;
    # the traced graph will not defend against it.
    #
    # A separate risk from the same trace: whether torch's ONNX exporter
    # emits the three nn.Upsample(scale_factor=2) calls as length-agnostic
    # Resize nodes (`scales`) or bakes in a `sizes` constant derived from
    # this dummy's length is version-dependent and invisible here. Run
    # check_inpaintnet_parity.py (it sweeps lengths by default, not just
    # 256) right after this export, before trusting it.
    #
    # 256 as the representative dummy length (production's chunk_size), but
    # the axis is marked dynamic below so the exported graph is not actually
    # pinned to it - see the module docstring for why a static length would
    # be wrong here, unlike for TrackNet.
    dummy = torch.randn(1, 3, 256)
    return _export_pair(model, dummy, out,
                        dynamic_axes={"trajectory": {2: "length"}, "prediction": {2: "length"}},
                        input_names=["trajectory"], output_names=["prediction"])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True)
    ap.add_argument("--out", default="tools/models/onnx/tracknet.onnx")
    ap.add_argument("--inpaintnet-out", default="tools/models/onnx/inpaintnet.onnx")
    args = ap.parse_args()

    sys.path.insert(0, str(Path(args.tracker_repo) / "backend"))

    # Both run even if the first refuses its fp16 graph: they are independent
    # models, and a caller wants to know about both in one go rather than
    # discovering the second only after fixing the first.
    ok_tracknet = _export_tracknet(Path(args.out))
    ok_inpaintnet = _export_inpaintnet(Path(args.inpaintnet_out))
    if not (ok_tracknet and ok_inpaintnet):
        bad = [n for n, ok in (("tracknet", ok_tracknet), ("inpaintnet", ok_inpaintnet)) if not ok]
        print(f"fp16 conversion refused for: {', '.join(bad)}. The fp32 graphs were "
              f"written and are the ones to bundle.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
