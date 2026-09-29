#!/usr/bin/env python3
"""Montages for auditing the two-player reference by eye.

docs/plans/2026-09-29-two-player-tracking-design.md section 3.2: the oracle
shares ideas with what it scores, so agreement proves little, and the audit is
aimed where a wrong reference would change a figure:

  * DISAGREE: both have somebody, more than 1 m apart (a wrong-person count
    rests on the reference being right);
  * OFF PATH: the system has somebody, the reference does not, and no
    reference position within 5 frames lies within 1 m of it. The scorer
    calls these unknown, which would hide a wrong pick. A system-only sample
    that does sit beside the reference path a few frames away is the same
    player seen in a frame the oracle missed, and is counted, not shown;
  * CONTROL: evenly spaced frames per side where the two agree.

Frames where only the reference has somebody change no wrong-person count
(the system produced nothing to be wrong about) and are not shown.

    python tools/models/players_reference_audit.py --video-id <id> [--run poses-frames.json]

Each crop: the reference's pick in green, the system's in magenta, the side's
other oracle candidates in grey, the frame, side and kind printed on it.
Written to <corpus dir>/audit/<side>-<n>.png, twelve crops each. Corrections
go into players_reference.json's "audit" list by hand
({"frame", "side", "action": "clear"|"choose", "candidate", "note"}) and are
replayed with players_reference.py --apply-audit.
"""
import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np

CORPUS = Path.home() / "shuttl-corpus"
RIGHT_M = 1.0
PATH_WINDOW = 5
CONTROLS = 8
CROP_W, CROP_H = 480, 300
GRID_COLS, GRID_ROWS = 3, 4


def reference_px(ref_side, frame):
    """The reference position's ankle pixel, read off the candidate it came from."""
    pos = ref_side["positions"].get(str(frame))
    if pos is None:
        return None
    for c in ref_side["candidates"].get(str(frame), []):
        if abs(c[0] - pos[0]) < 1e-6 and abs(c[1] - pos[1]) < 1e-6:
            return c[4]
    return None


def pick_frames(ref_side, sys_samples):
    """(frame, kind) pairs to show, in frame order; frame is the 0-based reference index."""
    sys_by_ref = {s["frame"] - 1: s for s in sys_samples}
    positions = {int(k): v for k, v in ref_side["positions"].items()}
    shown, agree = [], []
    for f, s in sys_by_ref.items():
        pos = positions.get(f)
        if pos is None:
            beside = any(
                g in positions and math.hypot(s["x"] - positions[g][0], s["y"] - positions[g][1]) <= RIGHT_M
                for g in range(f - PATH_WINDOW, f + PATH_WINDOW + 1)
            )
            if not beside:
                shown.append((f, "OFF PATH"))
        elif math.hypot(s["x"] - pos[0], s["y"] - pos[1]) > RIGHT_M:
            shown.append((f, "DISAGREE"))
        else:
            agree.append(f)
    agree.sort()
    if agree:
        step = max(1, len(agree) // CONTROLS)
        shown += [(f, "CONTROL") for f in agree[::step][:CONTROLS]]
    return sorted(shown)


GREY = (160, 160, 160)


def crop(frame_img, points, label):
    # Centred on the picks being judged (reference and system), never on the
    # other candidates: an umpire across the frame would pull the crop off both.
    judged = [p for p, colour in points if colour != GREY] or [p for p, _ in points]
    xs = [p[0] for p in judged]
    ys = [p[1] for p in judged]
    cx, cy = (min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0 - 60
    h, w = frame_img.shape[:2]
    x0 = int(min(max(0, cx - CROP_W / 2), w - CROP_W))
    y0 = int(min(max(0, cy - CROP_H / 2), h - CROP_H))
    img = frame_img.copy()
    for (x, y), colour in points:
        cv2.circle(img, (int(x), int(y)), 9 if colour != GREY else 6, colour, 2)
    out = img[y0:y0 + CROP_H, x0:x0 + CROP_W].copy()
    cv2.rectangle(out, (0, 0), (CROP_W, 22), (0, 0, 0), -1)
    cv2.putText(out, label, (6, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video-id", required=True)
    ap.add_argument("--run", default="poses-frames.json", help="a cloud-local/*-frames.json export")
    a = ap.parse_args()
    d = CORPUS / a.video_id
    ref = json.loads((d / "players_reference.json").read_text())
    run = json.loads((d / "cloud-local" / a.run).read_text())
    wanted = {}
    for side in ("NEAR", "FAR"):
        ref_side = ref["sides"][side]
        sys_samples = run["sides"][side]["samples"]
        sys_by_ref = {s["frame"] - 1: s for s in sys_samples}
        for f, kind in pick_frames(ref_side, sys_samples):
            pts = [(tuple(c[4]), GREY) for c in ref_side["candidates"].get(str(f), [])]
            rp = reference_px(ref_side, f)
            if rp is not None:
                pts.append((tuple(rp), (0, 200, 0)))
            pts.append((tuple(sys_by_ref[f]["ankle"]), (255, 0, 255)))
            wanted.setdefault(f, []).append((side, kind, pts))
    src = next(p for p in (d / "source.mov", d / "source.mp4") if p.exists())
    cap = cv2.VideoCapture(str(src))
    crops = {"NEAR": [], "FAR": []}
    last = max(wanted) if wanted else -1
    i = 0
    while i <= last:
        ok, img = cap.read()
        if not ok:
            break
        for side, kind, pts in wanted.get(i, []):
            crops[side].append(crop(img, pts, f"{side} frame {i} {kind}"))
        i += 1
    out_dir = d / "audit"
    out_dir.mkdir(exist_ok=True)
    per = GRID_COLS * GRID_ROWS
    for side, items in crops.items():
        kinds = [k for f in sorted(wanted) for s, k, _ in wanted[f] if s == side]
        print(f"{side}: {len(items)} crops ({kinds.count('DISAGREE')} disagree, "
              f"{kinds.count('OFF PATH')} off path, {kinds.count('CONTROL')} control)")
        for n in range(0, len(items), per):
            tiles = items[n:n + per] + [np.zeros((CROP_H, CROP_W, 3), np.uint8)] * (per - len(items[n:n + per]))
            rows = [np.hstack(tiles[r * GRID_COLS:(r + 1) * GRID_COLS]) for r in range(GRID_ROWS)]
            path = out_dir / f"{side}-{n // per:02d}.png"
            cv2.imwrite(str(path), np.vstack(rows))
    print(f"montages in {out_dir}")


if __name__ == "__main__":
    main()
