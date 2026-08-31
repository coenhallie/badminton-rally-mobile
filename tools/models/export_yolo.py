#!/usr/bin/env python3
"""Detector and pose model to ONNX fp16, at the sizes the cloud actually uses.

The cloud runs pose at imgsz=960 and the detector at the Ultralytics default
of 640. Both sizes are exported for pose so the 960-versus-640 trade can be
measured by measure_pose_throughput.py rather than assumed.

fp16 comes from Ultralytics' own `half=True`, and each model is exported
twice: once plain to `<name>.onnx` and once half to `<name>.fp16.onnx`, so a
parity check has both graphs to compare.

An earlier version instead ran `onnxconverter_common.float16` over the fp32
graph, on the assumption that `half=True` needed CUDA. It does not - it works
on CPU - and the converter route does not work here at all: on every one of
these graphs it produces a model ONNX Runtime refuses to load, with
"Type (tensor(float)) of output arg (/model.10/Resize_output_0) ... does not
match expected type (tensor(float16))". `keep_io_types`, `disable_shape_infer`
and blocking Resize in `op_block_list` all fail the same way. Ultralytics'
export produces fp16 weights with fp32 I/O, which is what `keep_io_types` was
wanted for anyway.
"""
import argparse
import sys
from pathlib import Path

# Size the cloud runs each model at. The FIRST entry is the default and gets
# the unsuffixed filename; the rest are written with a ".<size>" infix.
# Pinned, not inherited. Ultralytics follows the installed torch and picked
# opset 22 here, which onnxruntime 1.19.2 refuses outright: "Current official
# support for domain ai.onnx is till opset 21." A default that moves with a
# library version is exactly what a conversion pipeline must not have, and 17
# is what export_tracknet.py already targets, so the two halves of the model
# set agree.
OPSET = 17

MODELS = [
    ("badminton", "badminton.pt", [640]),
    ("pose", "pose.pt", [960, 640]),
]


def _export_one(weight: Path, dest_fp32: Path, dest_fp16: Path, size: int) -> None:
    """Export one weight at one size, leaving no half-complete pair behind.

    Either both files exist and are from this run, or neither does. Without
    the cleanup a failure in the fp16 pass (a missing onnxconverter-common,
    say) leaves a stale fp32 file that a later run's success message would sit
    beside, with no way to tell which run produced it.
    """
    from ultralytics import YOLO

    dest_fp32.parent.mkdir(parents=True, exist_ok=True)
    produced = None
    try:
        for half, dest in ((False, dest_fp32), (True, dest_fp16)):
            # A fresh YOLO per export: export() mutates the model in place, so
            # reusing one across sizes or precisions silently exports the same
            # graph twice.
            produced = Path(
                YOLO(str(weight)).export(
                    format="onnx", imgsz=size, simplify=True, opset=OPSET, half=half
                )
            )
            produced.replace(dest)
            produced = None
    except Exception:
        if produced is not None:
            produced.unlink(missing_ok=True)
        dest_fp32.unlink(missing_ok=True)
        dest_fp16.unlink(missing_ok=True)
        raise


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", default="tools/models/weights",
                    help="directory holding badminton.pt and pose.pt")
    ap.add_argument("--out", default="tools/models/onnx")
    args = ap.parse_args()

    weights = Path(args.weights)
    out = Path(args.out)

    missing = [w for _, w, _ in MODELS if not (weights / w).exists()]
    if missing:
        print(f"missing weights in {weights}: {', '.join(missing)}", file=sys.stderr)
        print("run tools/models/pull_weights.py first", file=sys.stderr)
        return 2

    written = []
    for name, weight, sizes in MODELS:
        for size in sizes:
            infix = "" if size == sizes[0] else f".{size}"
            fp32 = out / f"{name}{infix}.onnx"
            fp16 = out / f"{name}{infix}.fp16.onnx"
            _export_one(weights / weight, fp32, fp16, size)
            written.append((name, size, fp16))
            print(f"{name} @ {size} -> {fp16} ({fp16.stat().st_size:,} bytes)")

    # The byte sizes decide bundle-versus-download (design section 5.4), so
    # print the total rather than making someone add up the lines above.
    total = sum(p.stat().st_size for _, _, p in written)
    print(f"\nfp16 total: {total:,} bytes ({total / 1e6:.1f} MB) across {len(written)} files")
    print("Record these in tools/models/README.md.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
