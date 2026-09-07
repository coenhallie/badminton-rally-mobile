#!/usr/bin/env python3
import json
import sys

import numpy as np

d = json.load(open(sys.argv[1]))
rows = d["rows"]
n = len(rows)
present = d["frames_with_player"]
print(f"frames {n}, near player with both ankles confident in {present} ({100*present/n:.0f}%)")
names = ["nose", "leye", "reye", "lear", "rear", "lsho", "rsho", "lelb", "relb", "lwri", "rwri",
         "lhip", "rhip", "lkne", "rkne", "lank", "rank"]
print("keypoint confident (>=0.5) fraction of player frames:")
print("  " + "  ".join(f"{nm}={c/present:.2f}" for nm, c in zip(names, d["kp_ok_counts"])))

metrics = ["stance_m", "stance_px", "elbow_L", "elbow_R", "knee_L", "knee_R", "shoulder_L", "shoulder_R",
           "hip_L", "hip_R", "trunk_lean", "shoulder_h_m", "hip_h_m", "nose_h_m", "wristL_h_m", "wristR_h_m",
           "px_per_m", "shoulder_w_px"]


def series(name):
    return np.array([r.get(name, np.nan) for r in rows], dtype=float)


def median5(x):
    y = x.copy()
    for i in range(len(x)):
        w = x[max(0, i - 2):i + 3]
        w = w[~np.isnan(w)]
        y[i] = np.median(w) if len(w) else np.nan
    return y


def jitter(x):
    dx = np.abs(np.diff(x))
    dx = dx[~np.isnan(dx)]
    return (np.median(dx), np.percentile(dx, 90)) if len(dx) else (np.nan, np.nan)


print(f"\n{'metric':14s} {'avail':>6s} {'median':>8s} {'p10':>8s} {'p90':>8s} | {'jit med':>8s} {'jit p90':>8s} | {'med5 jit':>8s} {'med5 p90':>8s}")
for m in metrics:
    x = series(m)
    ok = ~np.isnan(x)
    if ok.sum() == 0:
        continue
    j = jitter(x)
    js = jitter(median5(x))
    print(f"{m:14s} {ok.sum()/present:6.2f} {np.nanmedian(x):8.2f} {np.nanpercentile(x,10):8.2f} {np.nanpercentile(x,90):8.2f} | "
          f"{j[0]:8.2f} {j[1]:8.2f} | {js[0]:8.2f} {js[1]:8.2f}")

# Serve windows: rally starts from clips.json, serve just before.
starts = [4.23733, 19.4379, 37.9342]
t = series("t")
for s in starts:
    idx = np.where((t > s - 1.2) & (t < s + 0.5))[0]
    if len(idx) == 0:
        continue
    print(f"\n--- around rally start t={s:.2f}s (frames {idx[0]}..{idx[-1]}) ---")
    print(f"{'frame':>5s} {'t':>6s} {'stance':>6s} {'elbL':>5s} {'elbR':>5s} {'kneeL':>5s} {'kneeR':>5s} {'shoR':>5s} {'lean':>5s} {'wrR_h':>5s} {'wrL_h':>5s} {'sho_h':>5s} {'kp':>2s}")
    for i in idx:
        r = rows[i]
        f = lambda k, w=5, p=0: (f"{r[k]:{w}.{p}f}" if k in r else " " * (w - 1) + "-")
        print(f"{r['frame']:5d} {r['t']:6.2f} {f('stance_m',6,2)} {f('elbow_L')} {f('elbow_R')} {f('knee_L')} {f('knee_R')} {f('shoulder_R')} {f('trunk_lean')} {f('wristR_h_m',5,2)} {f('wristL_h_m',5,2)} {f('shoulder_h_m',5,2)} {r.get('kp_ok','-'):>2}")
