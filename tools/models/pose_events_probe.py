#!/usr/bin/env python3
"""Shot events against the near player, from a pose_dump.py file and the tracker's shot detector.

Assigns each shuttle direction reversal to the near player or to someone else by
whichever wrist comes nearest the shuttle within +-10 frames, and reports the
contact frame, the wrist, the contact height class and the stance at contact.
This is the measurement behind docs/plans/2026-09-08-more-pose-metrics-research.md.

    python tools/models/pose_events_probe.py <corpus dir with results.json and video.json> <dump.json> \
        [--tracker-repo ../badminton-tracker] [--out contacts.json]

Run it with the tracker repo's backend venv, which has the shot detector's
dependencies. The tracker's shot_detection.py is the oracle on purpose: the
Kotlin port is checked against it, not the other way round.
"""
import argparse
import json
import os
import sys
from collections import Counter

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from pose_metrics_probe import L, W, angle, resolved_homography, to_court  # noqa: E402

LSHO, RSHO, LELB, RELB, LWRI, RWRI, LHIP, RHIP, LANK, RANK = 5, 6, 7, 8, 9, 10, 11, 12, 15, 16
WINDOW = 10  # frames either side of the reversal to look for a wrist


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus")
    ap.add_argument("dump")
    ap.add_argument("--tracker-repo", default=os.path.join(HERE, "..", "..", "..", "badminton-tracker"))
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    sys.path.insert(0, os.path.join(a.tracker_repo, "backend"))
    from shot_detection import detect_shuttle_shots

    d = json.load(open(os.path.join(a.corpus, "results.json")))
    marks = json.load(open(os.path.join(a.corpus, "video.json")))["manual_court_keypoints"]
    h, below_net = resolved_homography(marks)
    fps = d["fps"]
    sp = d["shuttle_positions"]

    frames = [{"frame": int(k), "timestamp": int(k) / fps,
               "shuttle_position": ({"x": sp[k]["x"], "y": sp[k]["y"]} if sp[k]["visible"] else None)}
              for k in sorted(sp, key=int)]
    shots = [{"frame": s["frame"], "x": s["shuttle_position"]["x"], "y": s["shuttle_position"]["y"]}
             for s in detect_shuttle_shots(frames, fps, require_players=False)]

    def mpp(p):
        p = np.asarray(p, float)
        return np.linalg.norm(to_court(h, p) - to_court(h, p + (4, 0))) / 4

    # Near player per frame, the way NearPlayerSelector picks: confident ankles,
    # camera side of the net, on court, plausible torso, best box confidence.
    dump = json.load(open(a.dump))
    allp, near = {}, {}
    for fr in dump:
        ps = [np.array(p["k"]) for p in fr["people"]]
        allp[fr["frame"]] = ps
        best = None
        for p, k in zip(fr["people"], ps):
            if k[LANK, 2] < .5 or k[RANK, 2] < .5:
                continue
            mid = (k[LANK, :2] + k[RANK, :2]) / 2
            if not below_net(mid):
                continue
            cx, cy = to_court(h, mid)
            if not (0 <= cx <= W and 0 <= cy <= L):
                continue
            if all(k[i, 2] >= .5 for i in (LSHO, RSHO, LHIP, RHIP)) and \
                    np.linalg.norm((k[LSHO, :2] + k[RSHO, :2]) / 2 - (k[LHIP, :2] + k[RHIP, :2]) / 2) * mpp(mid) > 0.9:
                continue
            if best is None or p["box"] > best[0]:
                best = (p["box"], k)
        if best is not None:
            near[fr["frame"]] = best[1]
    f0, f1 = dump[0]["frame"], dump[-1]["frame"]

    def nearest_wrist(persons, sh):
        best = None
        for k in persons:
            for wi, nm in ((LWRI, "L"), (RWRI, "R")):
                if k[wi, 2] < .5:
                    continue
                dd = np.linalg.norm(k[wi, :2] - sh)
                if best is None or dd < best[0]:
                    best = (dd, nm, wi, k)
        return best

    rallies = [r for r in d["rallies"] if r["start_frame"] >= f0 and r["end_frame"] <= f1]

    def rally_of(f):
        for r in rallies:
            if r["start_frame"] - 15 <= f <= r["end_frame"] + 15:
                return r["id"]

    rows = []
    for s in shots:
        if rally_of(s["frame"]) is None:
            continue
        bn = bo = None
        for f in range(s["frame"] - WINDOW, s["frame"] + WINDOW + 1):
            if f not in allp or not sp[str(f)]["visible"]:
                continue
            sh = np.array([sp[str(f)]["x"], sp[str(f)]["y"]])
            if f in near:
                c = nearest_wrist([near[f]], sh)
                if c and (bn is None or c[0] < bn[0]):
                    bn = (c[0], f, c[1], c[2], c[3], sh)
            c = nearest_wrist([k for k in allp[f] if f not in near or k is not near[f]], sh)
            if c and (bo is None or c[0] < bo[0]):
                bo = (c[0], f)
        who = "near" if bn and (bo is None or bn[0] < bo[0]) else ("far" if bo else "?")
        row = {"rally": rally_of(s["frame"]), "frame": s["frame"], "who": who}
        if who == "near":
            dist, f, nm, wi, k, sh = bn
            sho_y = (k[LSHO, 1] + k[RSHO, 1]) / 2
            hip_y = (k[LHIP, 1] + k[RHIP, 1]) / 2
            torso = hip_y - sho_y
            ei, si = (LELB, LSHO) if nm == "L" else (RELB, RSHO)
            row.update(
                contact=f, off=f - s["frame"], wrist=nm, dist=float(dist), other=float(bo[0]) if bo else None,
                rel=float((sho_y - sh[1]) / torso),
                cls="overhead" if sh[1] < sho_y - 0.3 * torso else ("underarm" if sh[1] > hip_y else "mid"),
                elbow=float(angle(k[si, :2], k[ei, :2], k[wi, :2])) if k[ei, 2] >= .5 else None,
                stance=float(np.linalg.norm(to_court(h, k[LANK, :2]) - to_court(h, k[RANK, :2]))),
                court=to_court(h, (k[LANK, :2] + k[RANK, :2]) / 2).tolist(),
            )
        rows.append(row)

    name = os.path.basename(a.corpus.rstrip("/"))[:8]
    print(f"{name} dump frames {f0}-{f1}, near player in {len(near)}, rallies covered {[r['id'] for r in rallies]}")
    viol = pairs = 0
    for r in rallies:
        rs = [x for x in rows if x["rally"] == r["id"]]
        seq = "".join(x["who"][0].upper() for x in rs)
        v = sum(1 for p, q in zip(seq, seq[1:]) if p == q and p in "NF")
        viol += v
        pairs += max(0, len(seq) - 1)
        gaps = np.diff([x["frame"] for x in rs]) / fps
        print(f"  rally {r['id']:2d} {r['duration_seconds']:5.1f}s shots {len(rs):2d} seq {seq:14s} same-side pairs {v} "
              f"gap median {np.median(gaps) if len(gaps) else float('nan'):.2f}s")
    ns = [x for x in rows if x["who"] == "near"]
    print(f"  same-side consecutive pairs {viol}/{pairs}; near shots {len(ns)}: "
          f"wrist R {sum(x['wrist'] == 'R' for x in ns)} L {sum(x['wrist'] == 'L' for x in ns)}")
    print("  contact-vs-reversal offset frames:", " ".join(f"{x['off']:+d}" for x in ns))
    print("  wrist-shuttle px:", " ".join(f"{x['dist']:.0f}" for x in ns),
          "| other person's wrist px:", " ".join(f"{x['other']:.0f}" if x["other"] else "-" for x in ns))
    print("  class:", dict(Counter(x["cls"] for x in ns)), "| height above shoulders (torso units):",
          " ".join(f"{x['rel']:.2f}" for x in ns))
    print("  elbow at contact:", " ".join(f"{x['elbow']:.0f}" if x["elbow"] is not None else "-" for x in ns),
          "| stance:", " ".join(f"{x['stance']:.2f}" for x in ns))
    out = a.out or f"contacts-{name}.json"
    json.dump(rows, open(out, "w"))
    print(f"  wrote {out}")


if __name__ == "__main__":
    main()
