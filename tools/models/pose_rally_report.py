#!/usr/bin/env python3
"""Per-rally movement aggregates from a pose_dump.py file: how much they depend on method.

For each rally in results.json covered by the dump: coverage, path length at three
resamplings (every frame, 0.25 s, 0.5 s), top and p90 speed over 0.5 s windows,
wide-stance count, median position and its spread. Optionally the recovery time
after the near player's own shots from a pose_events_probe.py output.

    python tools/models/pose_rally_report.py <corpus dir> <dump.json> [--contacts contacts.json]
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pose_metrics_probe import L, W, resolved_homography, to_court  # noqa: E402

LSHO, RSHO, LHIP, RHIP, LANK, RANK = 5, 6, 11, 12, 15, 16


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus")
    ap.add_argument("dump")
    ap.add_argument("--contacts", default=None)
    ap.add_argument("--base-radius", type=float, default=0.75)
    a = ap.parse_args()
    d = json.load(open(os.path.join(a.corpus, "results.json")))
    marks = json.load(open(os.path.join(a.corpus, "video.json")))["manual_court_keypoints"]
    h, below_net = resolved_homography(marks)
    fps = d["fps"]

    def mpp(p):
        p = np.asarray(p, float)
        return np.linalg.norm(to_court(h, p) - to_court(h, p + (4, 0))) / 4

    near = {}
    dump = json.load(open(a.dump))
    for fr in dump:
        best = None
        for p in fr["people"]:
            k = np.array(p["k"])
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

    def positions(frames):
        out = {}
        for f in frames:
            k = near[f]
            p, q = to_court(h, k[LANK, :2]), to_court(h, k[RANK, :2])
            out[f] = ((p + q) / 2, float(np.linalg.norm(p - q)))
        return out

    def path(pos, step):
        fs = sorted(pos)
        pts = np.array([pos[f][0] for f in fs])
        t = np.array(fs) / fps
        ts = np.arange(t[0], t[-1], step)
        idx = np.clip(np.searchsorted(t, ts), 0, len(t) - 1)
        seg = np.hypot(*np.diff(pts[idx], axis=0).T)
        return seg.sum(), seg / step

    print(f"{'rally':>5} {'cover':>6} {'path 1fr':>8} {'path .25s':>9} {'path .5s':>8} {'vmax .5s':>8} {'v p90':>6} {'wide':>4} {'median x,y':>12} {'y sd':>5}")
    rallies = [r for r in d["rallies"] if r["start_frame"] >= f0 and r["end_frame"] <= f1]
    for r in rallies:
        frames = [f for f in near if r["start_frame"] <= f <= r["end_frame"]]
        n = r["end_frame"] - r["start_frame"] + 1
        if len(frames) < 10:
            print(f"{r['id']:>5} {len(frames) / n:6.2f}  too thin")
            continue
        pos = positions(frames)
        p1, _ = path(pos, 1 / fps)
        p25, _ = path(pos, 0.25)
        p5, v = path(pos, 0.5)
        st = np.array([pos[f][1] for f in sorted(pos)])
        wide = int(np.sum((st[1:] > 1.2) & (st[:-1] <= 1.2)))
        xy = np.array([pos[f][0] for f in pos])
        med = np.median(xy, axis=0)
        print(f"{r['id']:>5} {len(pos) / n:6.2f} {p1:8.1f} {p25:9.1f} {p5:8.1f} {v.max():8.2f} {np.percentile(v, 90):6.2f} {wide:4d} {med[0]:6.2f},{med[1]:5.2f} {xy[:, 1].std():5.2f}")

    if a.contacts:
        rows = [x for x in json.load(open(a.contacts)) if x["who"] == "near"]
        print(f"\nrecovery after own shot: seconds until the ankle midpoint is back within {a.base_radius} m of the rally's median position")
        for r in rallies:
            frames = [f for f in near if r["start_frame"] <= f <= r["end_frame"]]
            if len(frames) < 10:
                continue
            pos = positions(frames)
            base = np.median(np.array([pos[f][0] for f in pos]), axis=0)
            for x in rows:
                s = x["contact"]
                if not (r["start_frame"] <= s <= r["end_frame"]):
                    continue
                away = None
                verdict = "never left base"
                for f in range(s, r["end_frame"]):
                    if f not in pos:
                        continue
                    dd = np.linalg.norm(pos[f][0] - base)
                    if away is None and dd > a.base_radius:
                        away = f
                        verdict = "did not return before the rally ended"
                    if away is not None and dd <= a.base_radius:
                        verdict = f"left base at +{(away - s) / fps:.2f}s, back at +{(f - s) / fps:.2f}s"
                        break
                at = np.linalg.norm(pos[s][0] - base) if s in pos else float("nan")
                print(f"  rally {r['id']} shot at frame {s}: {at:.2f} m from base at contact, {verdict}")


if __name__ == "__main__":
    main()
