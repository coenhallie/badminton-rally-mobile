#!/usr/bin/env python3
"""A device-style pose file: every person the device's nano graph finds, unfiltered.

For docs/plans/2026-09-29-two-player-tracking-design.md section 5's trigger. A
device run hands selectPlayers every person its pose graph detects, with no
worker filters in between, so this is where a per-frame pick is most exposed
to a line judge or a coach on the near side. Only the Near side is read from
it: device runs keep Near only.

    python tools/models/device_style_poses.py --video-id <corpus dir name>

Same decode, letterbox and graph as pose_dump.py (posen.960.fp16.onnx), every
frame from 0, sequentially. The frames are numbered and timed like the local
cloud run's (cloud-local/baseline-frames.json, from
`./gradlew :analysis:exportCorpusRun`), and carry that run's camera as one
sample per frame, so the phone reads the court through the same camera at the
same frame in both files. Encoded through badminton-tracker's
raw_inference_codec, into <corpus dir>/device-style/poses.raw.
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2] / "badminton-tracker" / "backend"))
from pose_metrics_probe import STRIDE, decode, letterbox  # noqa: E402
from raw_inference_codec import RawBox, RawFrame, RawHeader, RawKeypoint, RawPerson, encode  # noqa: E402

CORPUS = Path.home() / "shuttl-corpus"


class _Camera:
    """The duck-typed camera raw_inference_codec.encode reads."""

    def __init__(self, reference_timestamp, samples):
        self.reference_timestamp = reference_timestamp
        self.samples = samples


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video-id", required=True)
    ap.add_argument("--onnx", default=str(HERE / "onnx" / "posen.960.fp16.onnx"))
    a = ap.parse_args()
    d = CORPUS / a.video_id
    src = next(p for p in (d / "source.mov", d / "source.mp4") if p.exists())
    run = json.loads((d / "cloud-local" / "baseline-frames.json").read_text())
    by_k = {fr["k"]: fr for fr in run["frames"]}

    sess = ort.InferenceSession(a.onnx, providers=["CPUExecutionProvider"])
    dtype = np.float16 if "float16" in sess.get_inputs()[0].type else np.float32
    name = sess.get_inputs()[0].name
    cap = cv2.VideoCapture(str(src))
    fps = cap.get(cv2.CAP_PROP_FPS)
    width, height = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    frames, samples = [], []
    i = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        k = i + 1
        cloud = by_k.get(k)
        if cloud is None:
            break
        canvas, scale, px, py = letterbox(frame)
        x = (canvas.astype(np.float32) / 255.0).transpose(2, 0, 1)[None].astype(dtype)
        rows = np.asarray(sess.run(None, {name: x})[0], np.float32).reshape(-1, STRIDE)
        persons = []
        for confidence, kpts in decode(rows, scale, px, py):
            xs, ys = kpts[:, 0], kpts[:, 1]
            box = RawBox(0, float(confidence), float(xs.min()), float(ys.min()), float(xs.max()), float(ys.max()))
            persons.append(RawPerson(box, [RawKeypoint(float(p[0]), float(p[1]), float(p[2])) for p in kpts]))
        frames.append(RawFrame(frame=k, timestamp=float(cloud["timestamp"]), persons=persons))
        if cloud["camera"] is not None:
            samples.append((float(cloud["timestamp"]), np.array(cloud["camera"], dtype=np.float64)))
        i += 1
        if i % 500 == 0:
            print(f"frame {i}", file=sys.stderr, flush=True)
    cap.release()

    camera = None
    if samples:
        # One sample per frame; frames sharing a timestamp (the cloud clock's
        # first two) keep the first, as CameraMotion.of does.
        kept = []
        for t, m in samples:
            if not kept or t > kept[-1][0]:
                kept.append((t, m))
        camera = _Camera(run.get("cameraReferenceTimestamp") or 0.0, kept)
    header = RawHeader(fps=fps, total_frames=len(frames), video_width=width, video_height=height,
                       model_version="device-style:posen.960.fp16")
    out = d / "device-style" / "poses.raw"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(encode(header, frames, camera=camera))
    print(f"wrote {out}: {len(frames)} frames, {sum(len(f.persons) for f in frames)} people")


if __name__ == "__main__":
    main()
