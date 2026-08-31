#!/usr/bin/env python3
"""Run converted TrackNet over a real video, compare coverage to the cloud's.

The number that matters is the fraction of frames carrying a visible shuttle.
The shot-gap detector rejects a candidate rally outright when fewer than 25%
of frames in its window are visible, so a coverage collapse costs whole
rallies rather than precision.
"""
import argparse, json
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

WIDTH, HEIGHT, SEQ = 512, 288, 8
# The cloud thresholds a sigmoid heatmap at 0.5 to decide visibility; matching
# it here keeps the comparison about conversion, not about a new threshold.
VIS_THRESHOLD = 0.5


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video", help="path to the source mp4")
    ap.add_argument("--corpus", required=True, help="corpus/<video_id> directory")
    args = ap.parse_args()

    results = json.loads((Path(args.corpus) / "results.json").read_text())
    cloud = {int(k): v for k, v in results.get("shuttle_positions", {}).items()}

    sess = ort.InferenceSession("tools/models/onnx/tracknet.fp16.onnx",
                                providers=["CPUExecutionProvider"])

    cap = cv2.VideoCapture(args.video)
    local: dict[int, dict] = {}
    buf: list[np.ndarray] = []
    first: np.ndarray | None = None
    frame_idx = 0

    def flush(start: int, count=None) -> None:
        # Background-concat mode: the first decoded frame stands in as the
        # background plane, matching how the checkpoint was trained.
        stack = np.concatenate([first] + buf, axis=0)[None].astype(np.float32)
        hm = sess.run(None, {"frames": stack})[0]
        for i in range(count if count is not None else len(buf)):
            plane = hm[0, i]
            flat = int(np.argmax(plane))
            peak = float(plane.flat[flat])
            local[start + i] = {
                "x": (flat % WIDTH) * (orig_w / WIDTH),
                "y": (flat // WIDTH) * (orig_h / HEIGHT),
                "visible": peak >= VIS_THRESHOLD,
            }

    orig_w = cap.get(cv2.CAP_PROP_FRAME_WIDTH) or WIDTH
    orig_h = cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or HEIGHT
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        small = cv2.cvtColor(cv2.resize(frame, (WIDTH, HEIGHT)), cv2.COLOR_BGR2RGB)
        chw = small.transpose(2, 0, 1) / 255.0
        if first is None:
            first = chw
        buf.append(chw)
        if len(buf) == SEQ:
            flush(frame_idx - SEQ + 1)
            buf = []
        frame_idx += 1

    if buf:
        # Trailing partial buffer: the static-shape graph needs exactly SEQ
        # frames, so pad with the last decoded frame to reach that length,
        # but only keep outputs for the frames that were actually decoded
        # (the heatmap still has 8 output planes; only len(buf) of them
        # correspond to real input frames).
        start = frame_idx - len(buf)
        real = len(buf)
        while len(buf) < SEQ:
            buf.append(buf[-1])
        flush(start, count=real)

    cap.release()

    shared = sorted(set(local) & set(cloud))
    cloud_vis = sum(1 for f in shared if cloud[f].get("visible"))
    local_vis = sum(1 for f in shared if local[f]["visible"])
    both = [f for f in shared if cloud[f].get("visible") and local[f]["visible"]]
    deltas = [
        ((local[f]["x"] - cloud[f]["x"]) ** 2 + (local[f]["y"] - cloud[f]["y"]) ** 2) ** 0.5
        for f in both
    ]
    deltas.sort()

    report = {
        "frames_compared": len(shared),
        "cloud_visible": cloud_vis,
        "local_visible": local_vis,
        "cloud_coverage": cloud_vis / max(len(shared), 1),
        "local_coverage": local_vis / max(len(shared), 1),
        "both_visible": len(both),
        "median_delta_px": deltas[len(deltas) // 2] if deltas else None,
        "p95_delta_px": deltas[int(len(deltas) * 0.95)] if deltas else None,
    }
    for k, v in report.items():
        print(f"{k:20}: {v}")

    Path("tools/models/reports").mkdir(parents=True, exist_ok=True)
    name = Path(args.corpus).name
    Path(f"tools/models/reports/coverage-{name}.json").write_text(json.dumps(report, indent=2))

    ratio = report["local_coverage"] / max(report["cloud_coverage"], 1e-9)
    print(f"coverage ratio      : {ratio:.3f}")
    ok = ratio >= 0.90
    print("GATE PASS" if ok else "GATE FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
