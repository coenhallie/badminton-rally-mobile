#!/usr/bin/env python3
"""How much court accuracy each pose model size actually costs.

The question "is a smaller YOLO good enough" is usually answered from COCO AP,
which is the wrong metric here. What this pipeline needs is one number per
player per frame: the ankle midpoint, projected through the court homography.
COCO AP averages over 17 keypoints and every object scale in a general dataset;
it says nothing about ankle localisation on a badminton court, and nothing at
all about the far player, who is small in frame and whose error the homography
amplifies.

So this measures the thing that matters directly: run each model over the same
real frames, project every ankle midpoint into court metres, and report the
disagreement against the largest model in centimetres, split near and far.
Centimetres of court error is what a heatmap is made of.

The largest model is the reference rather than ground truth. This measures
agreement, not correctness - all sizes share an architecture and a training
set, so a bias they share is invisible here. It still answers the question
asked, which is what is LOST by going smaller.
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

# COCO-17 ankle indices, which is the layout every YOLO pose model emits.
L_ANKLE, R_ANKLE = 15, 16
L_HIP, R_HIP = 11, 12

COURT_LENGTH_M = 13.4
COURT_WIDTH_M = 6.1

# How far outside the court a player may legitimately be, in metres. Players
# lunge past the baseline and wide of the tramlines; spectators, officials and
# players on the next court do not come this close. Without this gate the far
# side of the net line includes the crowd, and since models rank those
# detections differently, the comparison measures which bystander each model
# preferred rather than how well it finds ankles.
OUT_OF_COURT_MARGIN_M = 2.0


def homography(kp: dict) -> np.ndarray:
    """Court corners to a 6.1 x 13.4 m rectangle, as speed_calc.py does it."""
    src = np.float32([kp["top_left"], kp["top_right"], kp["bottom_right"], kp["bottom_left"]])
    dst = np.float32([[0, 0], [COURT_WIDTH_M, 0], [COURT_WIDTH_M, COURT_LENGTH_M], [0, COURT_LENGTH_M]])
    return cv2.getPerspectiveTransform(src, dst)


def to_court(h: np.ndarray, pt) -> np.ndarray:
    p = np.array([[[float(pt[0]), float(pt[1])]]], dtype=np.float32)
    return cv2.perspectiveTransform(p, h)[0][0]


def on_court(court_xy) -> bool:
    x, y = float(court_xy[0]), float(court_xy[1])
    m = OUT_OF_COURT_MARGIN_M
    return -m <= x <= COURT_WIDTH_M + m and -m <= y <= COURT_LENGTH_M + m


def is_far(kp: dict, x: float, y: float) -> bool:
    """Side of the net, by the net's y interpolated at this x.

    Not a pixel midline: PlayerIdentityTracker uses the interpolated net line
    because a midline misclassifies play near the net on an angled camera.
    """
    (x1, y1), (x2, y2) = kp["net_left"], kp["net_right"]
    if abs(x2 - x1) < 1e-6:
        return y < (y1 + y2) / 2
    t = (x - x1) / (x2 - x1)
    return y < y1 + t * (y2 - y1)


def ankle_midpoint(kps: np.ndarray, conf: np.ndarray, min_conf: float):
    """Ankle midpoint, falling back to the hips, as speed_calc.py does.

    Ankles put the point on the court plane, which is what makes the
    homography correct; a hip is about a metre above it and projects long.
    """
    for a, b in ((L_ANKLE, R_ANKLE), (L_HIP, R_HIP)):
        if conf[a] >= min_conf and conf[b] >= min_conf:
            return (kps[a] + kps[b]) / 2.0, ("ankle" if a == L_ANKLE else "hip")
    return None, None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--keypoints", required=True, help="video.json with manual_court_keypoints")
    ap.add_argument("--frames", type=int, default=60)
    ap.add_argument("--stride", type=int, default=25)
    ap.add_argument("--imgsz", type=int, default=960,
                    help="default input size; override per model with 'weights.pt@640'")
    ap.add_argument("--min-conf", type=float, default=0.5)
    ap.add_argument("--models", nargs="+",
                    default=["yolo26n-pose.pt", "yolo26s-pose.pt", "yolo26m-pose.pt", "yolo26l-pose.pt"])
    ap.add_argument("--weights-dir", default="tools/models/weights")
    args = ap.parse_args()

    kp = json.loads(Path(args.keypoints).read_text())["manual_court_keypoints"]
    h = homography(kp)

    cap = cv2.VideoCapture(args.video)
    frames, idx = [], 0
    while len(frames) < args.frames:
        ok, frame = cap.read()
        if not ok:
            break
        if idx % args.stride == 0:
            frames.append(frame)
        idx += 1
    cap.release()
    if not frames:
        print("no frames read", file=sys.stderr)
        return 2
    print(f"{len(frames)} frames, every {args.stride}th, imgsz={args.imgsz}")

    from ultralytics import YOLO

    # positions[model][(frame, side)] = court xy
    positions: dict[str, dict] = {}
    all_skeletons: dict[str, dict] = {}
    fallbacks: dict[str, int] = {}
    specs = []
    for spec in args.models:
        weights, _, size = spec.partition("@")
        specs.append((spec, weights, int(size) if size else args.imgsz))

    for name, weights, imgsz in specs:
        model = YOLO(str(Path(args.weights_dir) / weights))
        found, fell_back, rejected = {}, 0, 0
        skeletons: dict = {}
        for fi, frame in enumerate(frames):
            res = model.predict(frame, imgsz=imgsz, verbose=False)[0]
            if res.keypoints is None or res.keypoints.xy is None:
                continue
            xy = res.keypoints.xy.cpu().numpy()
            cf = res.keypoints.conf
            cf = cf.cpu().numpy() if cf is not None else np.ones(xy.shape[:2])
            boxconf = res.boxes.conf.cpu().numpy() if res.boxes is not None else np.ones(xy.shape[0])
            # Best detection per side, not the first: the frame contains
            # spectators and officials, and taking whichever the model happened
            # to emit first compares different people across models.
            best: dict = {}
            for person in range(xy.shape[0]):
                pt, kind = ankle_midpoint(xy[person], cf[person], args.min_conf)
                if pt is None:
                    continue
                if kind == "hip":
                    fell_back += 1
                court = to_court(h, pt)
                if not on_court(court):
                    rejected += 1
                    continue
                side = "far" if is_far(kp, pt[0], pt[1]) else "near"
                if side not in best or boxconf[person] > best[side][0]:
                    # Whole skeleton kept alongside the court point: the ankle
                    # midpoint prices the heatmap, but a skeleton VIEW is only
                    # as good as its worst visible joint, and a model can hold
                    # ankles while losing wrists.
                    best[side] = (boxconf[person], court, kind, xy[person], cf[person])
            for side, (_, court, kind, joints, jconf) in best.items():
                found[(fi, side)] = (court, kind)
                skeletons[(fi, side)] = (joints, jconf)
        positions[name] = found
        all_skeletons[name] = skeletons
        fallbacks[name] = fell_back
        print(f"  {name:22s} {len(found):4d} positions, {fell_back} hip fallbacks, "
              f"{rejected} off-court detections rejected")

    labels = [spec for spec, _, _ in specs]
    reference = labels[-1]
    print(f"\ncourt error vs {reference}, centimetres")
    print(f"  {'model':22s} {'side':5s} {'n':>4s} {'median':>8s} {'p90':>8s} {'max':>8s}")
    for name in labels[:-1]:
        for side in ("near", "far"):
            errs = []
            for key, (ref, ref_kind) in positions[reference].items():
                if key[1] != side:
                    continue
                got = positions[name].get(key)
                if got is None:
                    continue
                court, kind = got
                # Ankle against ankle only. A hip sits about a metre above the
                # court plane and projects long, so mixing the two measures the
                # fallback rather than the model, and swamps the real
                # difference by an order of magnitude.
                if kind != "ankle" or ref_kind != "ankle":
                    continue
                errs.append(float(np.hypot(*(court - ref))) * 100.0)
            if not errs:
                print(f"  {name:22s} {side:5s}    0  (no overlap)")
                continue
            e = np.array(errs)
            print(f"  {name:22s} {side:5s} {len(e):4d} {np.median(e):8.1f} "
                  f"{np.percentile(e, 90):8.1f} {e.max():8.1f}")

    # Skeleton-view quality, which is a different requirement from the heatmap:
    # the heatmap needs one point on the ground plane, a rendered overlay needs
    # every joint to sit on the limb it belongs to.
    print(f"\nwhole-skeleton agreement vs {reference}, near player, pixels")
    print(f"  {'model':22s} {'joints':>7s} {'median':>8s} {'p90':>8s}  worst joint")
    names = ["nose", "eyeL", "eyeR", "earL", "earR", "shldL", "shldR", "elbL", "elbR",
             "wriL", "wriR", "hipL", "hipR", "kneeL", "kneeR", "ankL", "ankR"]
    for name in labels[:-1]:
        errs, per_joint = [], {i: [] for i in range(17)}
        for key, (ref_j, ref_c) in all_skeletons[reference].items():
            if key[1] != "near":
                continue
            got = all_skeletons[name].get(key)
            if got is None:
                continue
            j, c = got
            for i in range(17):
                # Only joints both models call visible: a joint one model has
                # given up on is a coverage question, not a precision one.
                if c[i] < args.min_conf or ref_c[i] < args.min_conf:
                    continue
                d = float(np.hypot(*(j[i] - ref_j[i])))
                errs.append(d)
                per_joint[i].append(d)
        if not errs:
            print(f"  {name:22s}       0  (no overlap)")
            continue
        e = np.array(errs)
        worst = max((i for i in per_joint if per_joint[i]),
                    key=lambda i: np.median(per_joint[i]))
        print(f"  {name:22s} {len(e):7d} {np.median(e):8.1f} {np.percentile(e,90):8.1f}"
              f"  {names[worst]} {np.median(per_joint[worst]):.1f}px")

    # The headline is often here rather than above: an ankle that is not
    # confidently found falls back to the hip, which is off the court plane, so
    # a high fallback rate is a systematic court error no model size fixes.
    print(f"\ncoverage and ankle confidence (out of {len(frames)} frames per side)")
    print(f"  {'model':22s} {'side':5s} {'found':>6s} {'on ankles':>10s}")
    for name in labels:
        for side in ("near", "far"):
            got = [v for k, v in positions[name].items() if k[1] == side]
            ank = sum(1 for _, kind in got if kind == "ankle")
            pct = f"{100*ank/len(got):.0f}%" if got else "n/a"
            print(f"  {name:22s} {side:5s} {len(got):6d} {pct:>10s}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
