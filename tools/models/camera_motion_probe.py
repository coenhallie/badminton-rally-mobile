#!/usr/bin/env python3
"""Prepares a real handheld recording for the camera-tracker corpus test.

Task 5 of docs/plans/2026-09-27-camera-motion-design.md's implementation plan
(.superpowers/sdd/2026-09-27-camera-motion-plan/). The Kotlin tracker
(analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera/) has
only ever run against synthetic textures; this script feeds it a real 72.7 s
handheld clip (~/shuttl-corpus/IMG_5892/source.mov) by producing, once,
everything CameraMotionCorpusTest.kt needs so the JVM test itself does no
video decoding:

  - `y/<index>.y`: the raw Y (luma) plane of every frame the tracker's own
    10 Hz sampling rule selects, at full source resolution (1920x1080, one
    byte per pixel, video range, exactly as ffmpeg decodes it - no range
    conversion). `<index>` is the frame's 0-based decode index, so it is
    also the key into frame_times.json.
  - `samples.json`: `[{"index": int, "timestamp": float}, ...]`, the frames
    chosen, in decode order.
  - `frame_times.json`: `{"timestamps": [float, ...]}`, EVERY decoded
    frame's presentation timestamp (from ffprobe), indexed by list position.
  - `paint.json`: `{"points": [[x, y], ...]}`, 24 points in court metres on
    the near short service line, the near baseline, and the near long
    service line - the painted lines CameraMotionCorpusTest.kt measures
    against.
  - `opencv_motion.json`: `{"samples": [{"timestamp": float, "M": [[3
    floats], [3 floats], [3 floats]]}, ...]}`, source-pixel homographies
    from a second, independent implementation of the same anchored
    Lucas-Kanade tracker: backend/camera_motion.py in the sibling
    badminton-tracker checkout, run here through OpenCV rather than the
    from-scratch Kotlin port. That module is imported, not copied or
    edited (controller ruling): its `CameraMotionTracker._offer_grey` takes
    an already-downsampled grey image directly, which is exactly the shape
    of input this probe has after resizing the Y plane with INTER_AREA, and
    its `_track` already fails any LK point that lands outside
    [0, w-1] x [0, h-1] at tracker scale before RANSAC ever sees it (the
    same rule LucasKanade.kt enforces, and the one the controller ruling
    requires of this probe).

Also prints the OpenCV tracker's own painted-line error (median, p95) so the
Kotlin test's bounds are grounded in what the same measurement gets on the
same video, independent of the Kotlin implementation. If the Kotlin test's
painted-line bound ever fails, compare against this number before touching
the tracker.

Run: python3 tools/models/camera_motion_probe.py
Needs: ffmpeg, ffprobe, opencv-python, numpy on PATH / importable, and the
sibling checkout at ../badminton-tracker (overridable with
--tracker-repo).
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
import time
from pathlib import Path

import cv2
import numpy as np

# Design doc section 4: the source video and its known-good court fit.
SOURCE_WIDTH = 1920
SOURCE_HEIGHT = 1080
FRAME_BYTES = SOURCE_WIDTH * SOURCE_HEIGHT
MARKING_TIME_S = 0.1
TRACKER_WIDTH = 480
TRACKER_HEIGHT = 270

# The court's own dimensions, metres (design doc section 1 / court_geometry).
COURT_LENGTH_M = 13.4
SHORT_SERVICE_LINE_M = 1.98
LONG_SERVICE_LINE_FROM_BASELINE_M = 0.76


def default_corpus_dir() -> Path:
    return Path.home() / "shuttl-corpus" / "IMG_5892"


def default_tracker_repo() -> Path:
    return Path(__file__).resolve().parents[2].parent / "badminton-tracker"


def ffprobe_frame_times(source: Path) -> list[float]:
    """Every decoded frame's presentation timestamp, in decode order."""
    result = subprocess.run(
        [
            "ffprobe", "-v", "error", "-select_streams", "v",
            "-show_entries", "frame=pts_time", "-of", "csv=p=0", str(source),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    # csv=p=0 drops the section-name column but, on at least this file's
    # first frame, still emits a trailing comma (an empty extra field ffprobe
    # adds for that one frame only) - so parse only the first field.
    times = [float(line.split(",")[0]) for line in result.stdout.splitlines() if line.strip()]
    if not times:
        raise RuntimeError(f"ffprobe found no frames in {source}")
    return times


def decode_y_planes(source: Path):
    """Yields each decoded frame's raw Y plane (1920x1080 bytes), in order.

    `extractplanes=y` extracts the luma plane as ffmpeg decoded it - no
    colour-range or bit-depth conversion - and `-pix_fmt gray` keeps the
    rawvideo muxer from doing any either. One decode pass, sequential (no
    seeking): this corpus is variable-frame-rate, on which a seeked frame
    does not reliably land where its timestamp says, and ffmpeg's raw pipe
    never seeks in the first place.
    """
    process = subprocess.Popen(
        [
            "ffmpeg", "-v", "error", "-i", str(source),
            "-vf", "extractplanes=y", "-f", "rawvideo", "-pix_fmt", "gray", "-",
        ],
        stdout=subprocess.PIPE,
    )
    assert process.stdout is not None
    try:
        while True:
            raw = process.stdout.read(FRAME_BYTES)
            if len(raw) < FRAME_BYTES:
                break
            yield raw
    finally:
        process.stdout.close()
        return_code = process.wait()
        if return_code != 0:
            raise RuntimeError(f"ffmpeg exited {return_code} decoding {source}")


def paint_court_points() -> list[tuple[float, float]]:
    """24 court-metre points on the near short service line, the near
    baseline, and the near long service line. Design doc section 8 / the
    plan's Task 5: `x` in `linspace(0.8, 5.3, 8)` for each of the three
    lines. Matches /tmp/hm/cost/acc.py's LINES and badminton-tracker's
    verify_camera_motion.py's PAINT_COURT_POINTS exactly, including order.
    """
    xs = np.linspace(0.8, 5.3, 8)
    near_short_service = [(float(x), COURT_LENGTH_M / 2 + SHORT_SERVICE_LINE_M) for x in xs]
    near_baseline = [(float(x), COURT_LENGTH_M) for x in xs]
    near_long_service = [(float(x), COURT_LENGTH_M - LONG_SERVICE_LINE_FROM_BASELINE_M) for x in xs]
    return near_short_service + near_baseline + near_long_service


def paint_line_vertical_error(grey: np.ndarray, x_px: float, y_px: float) -> float | None:
    """The vertical distance from (x_px, y_px) to the painted line's centre
    nearby in `grey` (a full-resolution grey frame, float dtype), or None
    when there is no line to find there.

    A direct port of /tmp/hm/cost/acc.py's `line_err`, which the plan's
    Task 5 brief names as this probe's own grounding check, and which
    badminton-tracker's verify_camera_motion.py also ports faithfully as
    `paint_line_vertical_error` for its own parity gate: a grey-weighted
    centroid of the brightest pixels in a +-12 px vertical window, counted
    only when that window's peak clearly beats its wider surroundings (the
    same column's own median over a +-40 px window, plus 40 grey levels). A
    point with no such peak contributes NOTHING to the aggregate (None, not
    a fixed penalty); a point too close to the image edge for a full window
    is penalised at 99 px so it cannot silently vanish from the gate; a
    window with a peak but a degenerate weighted centroid is penalised at
    12 px, the edge of the search window.
    """
    height, width = grey.shape
    if not (0 <= x_px < width and 12 <= y_px < height - 13):
        return 99.0
    x = int(round(x_px))
    offsets = np.arange(-12, 13)
    rows = np.clip((y_px + offsets).astype(int), 0, height - 1)
    column = grey[rows, x]
    wide_lo = max(0, int(y_px) - 40)
    wide_hi = int(y_px) + 40
    wide_median = np.median(grey[wide_lo:wide_hi, x])
    if column.max() < wide_median + 40:
        return None
    weights = np.clip(column - np.median(column), 0.0, None)
    if weights.max() <= 0:
        return 12.0
    weights = np.where(weights < 0.5 * weights.max(), 0.0, weights)
    return abs(float((weights * offsets).sum() / weights.sum()))


def project_court_point(m: np.ndarray, h_ideal_inv: np.ndarray, cx: float, cy: float) -> tuple[float, float]:
    """A court point (metres) into frame `m`'s pixels: court -> marking-frame
    pixels via `h_ideal_inv` (H_ideal maps source pixels of the marking
    frame -> court metres, per the corpus README), then marking-frame ->
    this frame's pixels via `m` (source pixels, `CameraSample.referenceToFrame`
    in the Kotlin tracker's own terms).
    """
    v = m @ (h_ideal_inv @ np.array([cx, cy, 1.0]))
    return v[0] / v[2], v[1] / v[2]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", type=Path, default=default_corpus_dir())
    parser.add_argument("--tracker-repo", type=Path, default=default_tracker_repo())
    args = parser.parse_args()

    corpus_dir: Path = args.corpus_dir
    source = corpus_dir / "source.mov"
    h_ideal_path = corpus_dir / "H_ideal.json"
    if not source.exists():
        print(f"ERROR: {source} does not exist", file=sys.stderr)
        return 1
    if not h_ideal_path.exists():
        print(f"ERROR: {h_ideal_path} does not exist", file=sys.stderr)
        return 1

    backend_dir = str(args.tracker_repo / "backend")
    if backend_dir not in sys.path:
        sys.path.insert(0, backend_dir)
    try:
        from camera_motion import CameraMotionTracker  # noqa: E402
    except ImportError as exc:
        print(f"ERROR: could not import camera_motion from {backend_dir}: {exc}", file=sys.stderr)
        return 1

    y_dir = corpus_dir / "y"
    y_dir.mkdir(parents=True, exist_ok=True)
    for stale in y_dir.glob("*.y"):
        stale.unlink()

    print(f"reading frame timestamps from {source} with ffprobe...")
    frame_times = ffprobe_frame_times(source)
    (corpus_dir / "frame_times.json").write_text(json.dumps({"timestamps": frame_times}))
    print(f"ffprobe reports {len(frame_times)} decoded frames")

    tracker = CameraMotionTracker(MARKING_TIME_S, SOURCE_WIDTH, SOURCE_HEIGHT)
    samples: list[dict] = []

    print("decoding Y planes sequentially and sampling at 10 Hz...")
    start = time.perf_counter()
    frame_index = 0
    for raw in decode_y_planes(source):
        if frame_index >= len(frame_times):
            print(
                f"WARNING: ffmpeg produced a frame past ffprobe's {len(frame_times)}; stopping",
                file=sys.stderr,
            )
            break
        t = frame_times[frame_index]
        if tracker.wants(t):
            plane = np.frombuffer(raw, dtype=np.uint8).reshape(SOURCE_HEIGHT, SOURCE_WIDTH)
            (y_dir / f"{frame_index}.y").write_bytes(raw)
            samples.append({"index": frame_index, "timestamp": t})
            resized = cv2.resize(plane, (TRACKER_WIDTH, TRACKER_HEIGHT), interpolation=cv2.INTER_AREA)
            tracker._offer_grey(t, resized)  # noqa: SLF001 - see module docstring.
        frame_index += 1
    elapsed = time.perf_counter() - start
    print(f"decoded {frame_index} frames in {elapsed:.1f}s, selected {len(samples)} samples")

    if frame_index != len(frame_times):
        print(
            f"WARNING: ffmpeg decoded {frame_index} frames but ffprobe listed {len(frame_times)}; "
            "using the shorter of the two",
            file=sys.stderr,
        )

    (corpus_dir / "samples.json").write_text(json.dumps(samples))

    motion = tracker.finish()
    if motion is None:
        print("WARNING: the OpenCV tracker's finish() is None (never reached the marking frame)", file=sys.stderr)
        opencv_samples: list[dict] = []
    else:
        opencv_samples = [{"timestamp": ts, "M": m.tolist()} for ts, m in motion.samples]
    (corpus_dir / "opencv_motion.json").write_text(json.dumps({"samples": opencv_samples}))
    print(
        f"opencv_motion.json: {len(opencv_samples)} samples, "
        f"accepted={tracker.accepted} dropped={tracker.dropped}"
    )

    points = paint_court_points()
    (corpus_dir / "paint.json").write_text(json.dumps({"points": [[x, y] for x, y in points]}))
    print(f"paint.json: {len(points)} points")

    if motion is not None:
        h_ideal = np.array(json.loads(h_ideal_path.read_text())["H"], dtype=np.float64)
        h_ideal_inv = np.linalg.inv(h_ideal)
        index_by_timestamp = {s["timestamp"]: s["index"] for s in samples}
        errors: list[float] = []
        for ts, m in motion.samples:
            index = index_by_timestamp.get(ts)
            if index is None:
                continue
            plane_bytes = (y_dir / f"{index}.y").read_bytes()
            grey = np.frombuffer(plane_bytes, dtype=np.uint8).reshape(SOURCE_HEIGHT, SOURCE_WIDTH).astype(np.float64)
            for cx, cy in points:
                px, py = project_court_point(m, h_ideal_inv, cx, cy)
                error = paint_line_vertical_error(grey, px, py)
                if error is not None:
                    errors.append(error)
        if errors:
            median = float(np.median(errors))
            p95 = float(np.percentile(errors, 95))
            print(
                f"OpenCV painted-line vertical error: median {median:.3f} px, "
                f"p95 {p95:.3f} px, over {len(errors)} (sample, point) pairs"
            )
        else:
            print("WARNING: no painted-line measurement landed on a line", file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
