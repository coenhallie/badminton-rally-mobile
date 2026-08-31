#!/usr/bin/env python3
"""The 0b measurement: sustained pose throughput, bucketed by minute.

Runs the exported pose graph on a fixed input for a long stretch and reports
the median ms/frame for each MINUTE separately, plus the ratio of the last
minute to the first.

The bucketing is the whole point. A mean over ten minutes hides thermal
throttling, and throttling is the thing being measured: a phone that starts
at 40ms/frame and settles at 110 is a different product decision from one
that holds 45 throughout, and both average out to something that looks
survivable. Only the settled figure predicts a 30-minute video, so the
projection below uses the LAST minute, never the mean.

Scope: this is the measurement and reporting core, and running it on a
laptop gives a desktop baseline only. The number the design's section 5.6
routing threshold needs is from a phone - the oldest supported device and a
current flagship - which needs a host app embedding ONNX Runtime. This script
is what that harness should reproduce, and it is directly runnable anywhere
onnxruntime is installed.
"""
import argparse
import json
import statistics
import sys
import time
from pathlib import Path

# A 30-minute video at 30fps. The wall-clock projection is against this.
FRAMES_IN_30_MIN = 30 * 60 * 30


def summarise(samples, seconds_per_bucket=60.0):
    """Bucket (elapsed_seconds, duration_ms) samples into per-minute medians.

    Returns the per-bucket medians, the last/first ratio, and the projected
    wall clock for a 30-minute video at the LAST bucket's rate.

    Buckets are keyed off each sample's elapsed time rather than its index, so
    a slow minute holds fewer inferences and still counts as one minute. Index
    bucketing would stretch a throttled minute across two of them and flatten
    exactly the curve this exists to show.
    """
    buckets = {}
    for elapsed, ms in samples:
        buckets.setdefault(int(elapsed // seconds_per_bucket), []).append(ms)
    if not buckets:
        return {"buckets": [], "last_over_first": None, "projected_seconds_30min": None}

    ordered = [
        {"minute": k + 1, "inferences": len(buckets[k]), "median_ms": statistics.median(buckets[k])}
        for k in sorted(buckets)
    ]
    first, last = ordered[0]["median_ms"], ordered[-1]["median_ms"]
    return {
        "buckets": ordered,
        "last_over_first": (last / first) if first > 0 else None,
        "projected_seconds_30min": last * FRAMES_IN_30_MIN / 1000.0,
    }


def measure(session, feed, minutes):
    """Run inference back to back, returning (elapsed_s, duration_ms) samples."""
    samples = []
    started = time.perf_counter()
    deadline = started + minutes * 60.0
    while True:
        now = time.perf_counter()
        if now >= deadline:
            break
        t0 = time.perf_counter()
        session.run(None, feed)
        t1 = time.perf_counter()
        samples.append((t0 - started, (t1 - t0) * 1000.0))
    return samples


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", required=True,
                    help="label for the machine, used in the report filename")
    ap.add_argument("--onnx-dir", default="tools/models/onnx")
    ap.add_argument("--out", default="tools/models/reports")
    ap.add_argument("--minutes", type=float, default=10.0)
    ap.add_argument("--sizes", type=int, nargs="+", default=[960, 640])
    args = ap.parse_args()

    try:
        import numpy as np
        import onnxruntime as ort
    except ImportError as e:
        print(f"needs numpy and onnxruntime: {e}", file=sys.stderr)
        return 2

    onnx_dir = Path(args.onnx_dir)
    configs = {}
    for size in args.sizes:
        # export_yolo.py writes the first (default) size unsuffixed.
        candidates = [onnx_dir / f"pose.{size}.fp16.onnx", onnx_dir / "pose.fp16.onnx"]
        model = next((c for c in candidates if c.exists()), None)
        if model is None:
            print(f"no exported pose graph for {size} in {onnx_dir}; "
                  f"run tools/models/export_yolo.py first", file=sys.stderr)
            return 2

        sess = ort.InferenceSession(str(model), providers=ort.get_available_providers())
        spec = sess.get_inputs()[0]

        # Take the shape from the graph rather than assuming (1, 3, size,
        # size). It also catches the fallback above resolving to the wrong
        # file: asking for a size that was never exported lands on the default
        # graph, and without this check that surfaces as an opaque onnxruntime
        # shape error instead of saying which export is missing.
        shape = [1 if not isinstance(d, int) else d for d in spec.shape]
        if len(shape) == 4 and (shape[2], shape[3]) != (size, size):
            print(f"{model.name} takes {shape[2]}x{shape[3]}, not {size}x{size}; "
                  f"no {size} export exists in {onnx_dir}", file=sys.stderr)
            return 2

        # A fixed input on purpose: this measures the model and the thermal
        # envelope, not the decoder. Real frames would add decode time and
        # per-frame variance to a number meant to isolate inference.
        feed = {spec.name: np.zeros(shape, dtype=np.float32)}

        print(f"measuring {model.name} at {size} for {args.minutes:g} min "
              f"on {sess.get_providers()[0]} ...")
        summary = summarise(measure(sess, feed, args.minutes))
        summary["model"] = model.name
        summary["providers"] = sess.get_providers()
        configs[str(size)] = summary

        for b in summary["buckets"]:
            print(f"  minute {b['minute']:2d}: {b['median_ms']:7.1f} ms/frame "
                  f"({b['inferences']} inferences)")
        if summary["last_over_first"] is not None:
            print(f"  last/first: {summary['last_over_first']:.2f}x")
            print(f"  projected for a 30-min 30fps video "
                  f"({FRAMES_IN_30_MIN:,} frames): "
                  f"{summary['projected_seconds_30min'] / 60:.1f} min")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    report = out_dir / f"throughput-{args.device}.json"
    report.write_text(json.dumps(
        {"device": args.device, "minutes": args.minutes, "configs": configs}, indent=2))
    print(f"\nwrote {report}")
    print("The section 5.6 routing threshold is a multiple of video duration: "
          "divide the projected wall clock by 30 minutes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
