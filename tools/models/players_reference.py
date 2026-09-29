#!/usr/bin/env python3
"""Where each player really was, frame by frame: the two-player reference.

The yardstick for docs/plans/2026-09-29-two-player-tracking-design.md section
3.2. An oracle much stronger than anything it scores: yolo26x-pose on the full
frame at 1920, plus a 2x crop of the far half of the court, every person through
the phone's gates, and each side's player chosen by one shortest path over the
whole video rather than frame by frame.

    python tools/models/players_reference.py --video-id <corpus dir name>
    python tools/models/players_reference.py --video-id <id> --apply-audit

Reads, from ~/shuttl-corpus/<id>/:
  source.mov or source.mp4, and the marks (marks_ideal.json, else video.json);
  cloud-local/baseline-frames.json (`./gradlew :analysis:exportCorpusRun`): the
  camera the phone reads at each pose-file frame. Oracle frame i (0-based
  decode order) uses pose-file frame i + 1's camera, the one join every tool
  here makes (the worker numbers frames from 1). A frame whose camera is null
  while the run followed a camera is camera-lost: the marks do not describe
  it, so it gets no reference.

Writes players_reference.json: per side, `positions` (frame -> [x, y] court
metres, the path) and `candidates` (frame -> every gated person, for the
audit to choose among), plus `camera_lost` and an `audit` list that
--apply-audit replays onto the path.

The path: a person contributes -REWARD + (-ln confidence) + OUTSIDE if their
ankles are outside that half's court lines; joining two people up to
MAX_SKIP_FRAMES apart costs SKIP per skipped frame plus MOVE per metre, and is
impossible above MAX_SPEED_MPS; starting afresh anywhere costs RESTART. The
OUTSIDE term is what keeps an umpire or a bystander who stands still, detected
every frame, from being a cheaper path than a moving player who drops out now
and then.

Frames are decoded sequentially from 0 (see pose_dump.py on seeking).
"""
import argparse
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pose_metrics_probe import L, W, resolved_homography  # noqa: E402

CORPUS = Path.home() / "shuttl-corpus"
WEIGHTS = Path(__file__).resolve().parent / "weights"

MIN_KP = 0.5
MARGIN_M = 2.0
MAX_TORSO_M = 0.9
HEADROOM_M = 2.5
CROP_ZOOM = 2.0
CROP_MAX_IMGSZ = 2560
FULL_IMGSZ = 1920
DEDUPE_IOU = 0.5

REWARD = 2.0
OUTSIDE = 3.0
SKIP = 0.5
MOVE = 1.0
RESTART = 5.0
MAX_SKIP_FRAMES = 15
MAX_SPEED_MPS = 8.0


def corpus_marks(d: Path) -> dict:
    for name in ("marks_ideal.json", "video.json"):
        if (d / name).exists():
            return json.loads((d / name).read_text())["manual_court_keypoints"]
    raise SystemExit(f"{d} has no marks")


def to_court(h, p):
    v = h @ np.array([p[0], p[1], 1.0])
    return v[:2] / v[2]


def apply(m, p):
    v = m @ np.array([p[0], p[1], 1.0])
    return v[:2] / v[2]


def half_region(hinv, side, cam, fw, fh):
    """The half plus MARGIN_M, with HEADROOM_M above its far edge, in this frame's pixels."""
    y0, y1 = (-MARGIN_M, L / 2) if side == "FAR" else (L / 2, L + MARGIN_M)
    court = [(-MARGIN_M, y0), (W + MARGIN_M, y0), (W + MARGIN_M, y1), (-MARGIN_M, y1)]
    px = np.array([apply(cam, to_court(hinv, c)) for c in court])
    a = apply(cam, to_court(hinv, (W / 2 - 0.5, y0)))
    b = apply(cam, to_court(hinv, (W / 2 + 0.5, y0)))
    headroom = HEADROOM_M * float(np.hypot(*(b - a)))
    x0, x1 = int(max(0, px[:, 0].min() - 20)), int(min(fw, px[:, 0].max() + 20))
    ya, yb = int(max(0, px[:, 1].min() - headroom - 20)), int(min(fh, px[:, 1].max() + 20))
    if x1 - x0 < 32 or yb - ya < 32:
        return None
    return x0, ya, x1, yb


def detect(model, img, imgsz, dx=0, dy=0):
    r = model.predict(img, conf=0.15, iou=0.5, imgsz=imgsz, verbose=False, device="mps")[0]
    out = []
    if r.keypoints is None or r.boxes is None or len(r.boxes) == 0:
        return out
    boxes = r.boxes.xyxy.cpu().numpy()
    xy = r.keypoints.xy.cpu().numpy()
    kc = r.keypoints.conf.cpu().numpy()
    for j in range(len(boxes)):
        k = xy[j].copy()
        k[:, 0] += dx
        k[:, 1] += dy
        b = boxes[j] + np.array([dx, dy, dx, dy])
        out.append({"box": b, "k": k, "c": kc[j]})
    return out


def iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def dedupe(people):
    """Keep one detection per person, the one with the more confident keypoints."""
    people = sorted(people, key=lambda p: -float(np.mean(p["c"])))
    kept = []
    for p in people:
        if all(iou(p["box"], q["box"]) <= DEDUPE_IOU for q in kept):
            kept.append(p)
    return kept


def gated(p, h, back, below_net):
    """The phone's four gates, on marking-frame ankles; None when rejected."""
    k, c = p["k"], p["c"]
    if c[15] < MIN_KP or c[16] < MIN_KP:
        return None
    ankle = apply(back, (k[15] + k[16]) / 2.0)
    side = "NEAR" if below_net(ankle) else "FAR"
    court = to_court(h, ankle)
    if not (-MARGIN_M <= court[0] <= W + MARGIN_M and -MARGIN_M <= court[1] <= L + MARGIN_M):
        return None
    if all(c[i] >= MIN_KP for i in (5, 6, 11, 12)):
        s = apply(back, (k[5] + k[6]) / 2.0)
        hp = apply(back, (k[11] + k[12]) / 2.0)
        mpp = np.hypot(*(to_court(h, ankle + (4, 0)) - to_court(h, ankle - (4, 0)))) / 8.0
        if np.hypot(*(s - hp)) * mpp > MAX_TORSO_M:
            return None
    lo, hi = (0.0, L / 2) if side == "FAR" else (L / 2, L)
    inside = bool(0.0 <= court[0] <= W and lo <= court[1] <= hi)
    px = (k[15] + k[16]) / 2.0
    return side, [round(float(court[0]), 4), round(float(court[1]), 4), round(float(np.mean(c)), 4),
                  inside, [round(float(px[0]), 1), round(float(px[1]), 1)]]


def path(cands, fps):
    """One shortest path over the whole video for one side; frame -> [x, y].

    Nodes are processed in frame order. A node's best cost is its own cost
    plus the cheaper of: continuing from a node up to MAX_SKIP_FRAMES earlier
    that is reachable at MAX_SPEED_MPS, or restarting (RESTART plus the best
    path that ended before this frame, or the empty path). Back-tracking
    follows both kinds of link, so a path is several stretches when the
    player was lost for longer than a skip allows.
    """
    frames = sorted(cands)
    best, link = {}, {}
    done = []  # nodes of earlier frames, in frame order
    best_before = (0.0, None)  # (cost, node) of the cheapest path ending before this frame
    for f in frames:
        here = []
        for j, (x, y, conf, inside, _) in enumerate(cands[f]):
            own = -REWARD - math.log(max(conf, 1e-3)) + (0.0 if inside else OUTSIDE)
            choice, parent = best_before[0] + RESTART, best_before[1]
            for q in reversed(done):
                gap = f - q[0]
                if gap > MAX_SKIP_FRAMES:
                    break
                qx, qy = cands[q[0]][q[1]][:2]
                d = math.hypot(x - qx, y - qy)
                if d * fps / gap > MAX_SPEED_MPS:
                    continue
                c = best[q] + SKIP * (gap - 1) + MOVE * d
                if c < choice:
                    choice, parent = c, q
            best[(f, j)] = own + choice
            link[(f, j)] = parent
            here.append((f, j))
        done.extend(here)
        cheapest = min(here, key=best.get)
        if best[cheapest] < best_before[0]:
            best_before = (best[cheapest], cheapest)
    if best_before[1] is None:
        return {}
    out = {}
    n = best_before[1]
    while n is not None:
        out[n[0]] = cands[n[0]][n[1]][:2]
        n = link[n]
    return out


def self_test():
    """The path's rules on synthetic candidates; no video, no model."""
    fps = 30.0
    # A player moving 2 cm a frame inside the far half, missing every fifth
    # frame; an umpire standing still 1.5 m outside the sideline every frame,
    # more confident than the player.
    cands = {}
    for f in range(90):
        cs = [[7.6, 3.0, 0.95, False, [0, 0]]]
        if f % 5:
            cs.append([2.0 + 0.02 * f, 3.0, 0.7, True, [0, 0]])
        cands[f] = cs
    got = path(cands, fps)
    assert all(abs(xy[0] - 7.6) > 1.0 for xy in got.values()), "the umpire must never be on the path"
    assert len(got) == 72, f"every frame the player was seen, got {len(got)}"
    # A one-frame detection 5 m away, inside the lines, in the middle of the run.
    cands = {f: [[2.0, 3.0, 0.8, True, [0, 0]]] for f in range(60)}
    cands[30] = [[2.0, 3.0, 0.8, True, [0, 0]], [5.5, 5.5, 0.99, True, [0, 0]]]
    got = path(cands, fps)
    assert got[30] == [2.0, 3.0], f"a teleport must not be taken, got {got[30]}"
    # Two stretches 3 s apart: both are kept, joined by a restart.
    cands = {f: [[2.0, 3.0, 0.8, True, [0, 0]]] for f in list(range(30)) + list(range(120, 150))}
    assert len(path(cands, fps)) == 60, "both stretches"
    # Nobody inside the lines at all: no path.
    assert path({f: [[7.6, 3.0, 0.95, False, [0, 0]]] for f in range(30)}, fps) == {}
    print("self-test: 4 cases pass")


def build(video_id: str):
    from ultralytics import YOLO

    d = CORPUS / video_id
    src = next(p for p in (d / "source.mov", d / "source.mp4") if p.exists())
    marks = corpus_marks(d)
    h, below_net_fn = resolved_homography(marks)
    hinv = np.linalg.inv(h)
    run = json.loads((d / "cloud-local" / "baseline-frames.json").read_text())
    cams = {fr["k"]: fr["camera"] for fr in run["frames"]}
    followed = any(c is not None for c in cams.values())
    model = YOLO(str(WEIGHTS / "yolo26x-pose.pt"))

    cap = cv2.VideoCapture(str(src))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    cands = {"NEAR": {}, "FAR": {}}
    camera_lost = []
    i = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        cam = cams.get(i + 1)
        if cam is None and followed:
            camera_lost.append(i)
            i += 1
            continue
        m = np.array(cam) if cam is not None else np.eye(3)
        back = np.linalg.inv(m)
        fh, fw = frame.shape[:2]
        people = detect(model, frame, FULL_IMGSZ)
        # Only the far half is cropped: the near player is already large at
        # 1920, and the near half is most of the frame, so its crop cost the
        # most and found nobody the full frame missed.
        for side in ("FAR",):
            r = half_region(hinv, side, m, fw, fh)
            if r is None:
                continue
            x0, y0, x1, y1 = r
            imgsz = min(CROP_MAX_IMGSZ, int(math.ceil(CROP_ZOOM * max(x1 - x0, y1 - y0) / 32.0) * 32))
            people += detect(model, frame[y0:y1, x0:x1], imgsz, x0, y0)
        for p in dedupe(people):
            g = gated(p, h, back, below_net_fn)
            if g is not None:
                cands[g[0]].setdefault(i, []).append(g[1])
        i += 1
        if i % 100 == 0:
            print(f"frame {i}", flush=True)
    cap.release()
    ref = {"video_id": video_id, "frames": i, "fps": fps, "camera_lost": camera_lost, "sides": {}, "audit": []}
    for side in ("NEAR", "FAR"):
        ref["sides"][side] = {
            "empty": False,
            "positions": {str(f): xy for f, xy in sorted(path(cands[side], fps).items())},
            "candidates": {str(f): cs for f, cs in sorted(cands[side].items())},
        }
    out = d / "players_reference.json"
    old = json.loads(out.read_text()) if out.exists() else None
    if old:
        ref["audit"] = old.get("audit", [])
    out.write_text(json.dumps(ref))
    for side in ("NEAR", "FAR"):
        print(f"{side}: {len(ref['sides'][side]['positions'])} reference positions over {i} frames "
              f"({len(camera_lost)} camera-lost)")
    return out


def apply_audit(video_id: str):
    """Replay the hand corrections onto the path: clear a frame, or choose another candidate."""
    out = CORPUS / video_id / "players_reference.json"
    ref = json.loads(out.read_text())
    for a in ref.get("audit", []):
        side = ref["sides"][a["side"]]
        key = str(a["frame"])
        if a["action"] == "clear":
            side["positions"].pop(key, None)
        elif a["action"] == "choose":
            side["positions"][key] = side["candidates"][key][a["candidate"]][:2]
        else:
            raise SystemExit(f"unknown audit action {a}")
    out.write_text(json.dumps(ref))
    print(f"applied {len(ref.get('audit', []))} corrections to {out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video-id")
    ap.add_argument("--apply-audit", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        self_test()
    elif a.apply_audit:
        apply_audit(a.video_id)
    else:
        build(a.video_id)


if __name__ == "__main__":
    main()
