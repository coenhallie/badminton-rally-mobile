#!/usr/bin/env python3
"""What body measurements the stored skeleton can support, measured.

Runs the deployed pose graph over the first N frames of a corpus video, picks
the near player the way NearPlayerSelector does (confident ankles, camera side
of the net line, on court, highest box confidence), and computes candidate
per-frame metrics. Reports for each: availability, typical value, and
frame-to-frame jitter raw and after a centred 5-frame median.
"""
import argparse
import json
import sys

import cv2
import numpy as np
import onnxruntime as ort

SIZE, PAD, CONF, PERSON, STRIDE = 960, 114, 0.25, 0, 6 + 17 * 3
MIN_KP = 0.5
L, W, S = 13.4, 6.1, 1.98
NOSE, LSHO, RSHO, LELB, RELB, LWRI, RWRI, LHIP, RHIP, LKNE, RKNE, LANK, RANK = 0, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16


def letterbox(frame):
    h, w = frame.shape[:2]
    scale = min(SIZE / w, SIZE / h)
    fw, fh = int(w * scale), int(h * scale)
    px, py = (SIZE - fw) // 2, (SIZE - fh) // 2
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    canvas = np.full((SIZE, SIZE, 3), PAD, np.uint8)
    canvas[py:py + fh, px:px + fw] = cv2.resize(rgb, (fw, fh), interpolation=cv2.INTER_LINEAR)
    return canvas, scale, px, py


def decode(rows, scale, px, py):
    people = []
    for r in rows:
        if r[4] < CONF:
            break
        if int(r[5]) != PERSON:
            continue
        k = r[6:].reshape(17, 3).copy()
        k[:, 0] = (k[:, 0] - px) / scale
        k[:, 1] = (k[:, 1] - py) / scale
        people.append((float(r[4]), k))
    return people


def resolved_homography(marks):
    nl, nr = np.array(marks["net_left"]), np.array(marks["net_right"])

    def below_net(p):
        p = np.array(p)
        return np.cross(nr - nl, p - nl) > 0  # image y grows downward: positive cross = below

    def y_for(p):
        return L / 2 + S if below_net(p) else L / 2 - S

    src = [marks["top_left"], marks["top_right"], marks["bottom_right"], marks["bottom_left"],
           nl, nr,
           marks["service_line_near_left"], marks["service_line_near_right"],
           marks["service_line_far_left"], marks["service_line_far_right"],
           marks["center_near"], marks["center_far"]]
    dst = [(0, 0), (W, 0), (W, L), (0, L), (0, L / 2), (W, L / 2),
           (0, y_for(src[6])), (W, y_for(src[7])), (0, y_for(src[8])), (W, y_for(src[9])),
           (W / 2, y_for(src[10])), (W / 2, y_for(src[11]))]
    h, _ = cv2.findHomography(np.array(src, np.float64), np.array(dst, np.float64), 0)
    return h, below_net


def to_court(h, p):
    v = h @ np.array([p[0], p[1], 1.0])
    return v[:2] / v[2]


def to_pixel(hinv, c):
    v = hinv @ np.array([c[0], c[1], 1.0])
    return v[:2] / v[2]


def angle(a, b, c):
    """Angle at b, degrees, between ba and bc, in the image plane."""
    u, v = a - b, c - b
    cosang = np.dot(u, v) / (np.linalg.norm(u) * np.linalg.norm(v) + 1e-9)
    return float(np.degrees(np.arccos(np.clip(cosang, -1, 1))))


def metrics(k, h, hinv):
    xy, c = k[:, :2], k[:, 2]
    ok = c >= MIN_KP
    m = {}

    def need(*idx):
        return all(ok[i] for i in idx)

    ank_mid = (xy[LANK] + xy[RANK]) / 2
    ground = to_court(h, ank_mid)
    m["court_x"], m["court_y"] = ground
    # Stance width on the court plane: both ankles are on the ground, so the
    # homography gives this in real metres.
    m["stance_m"] = float(np.linalg.norm(to_court(h, xy[LANK]) - to_court(h, xy[RANK])))
    m["stance_px"] = float(np.linalg.norm(xy[LANK] - xy[RANK]))
    # Local vertical scale: px per metre across the court at the player's depth.
    left = to_pixel(hinv, ground + (-0.5, 0))
    right = to_pixel(hinv, ground + (0.5, 0))
    px_per_m = float(np.linalg.norm(right - left))
    m["px_per_m"] = px_per_m
    if need(LELB, LSHO, LWRI):
        m["elbow_L"] = angle(xy[LSHO], xy[LELB], xy[LWRI])
    if need(RELB, RSHO, RWRI):
        m["elbow_R"] = angle(xy[RSHO], xy[RELB], xy[RWRI])
    if need(LKNE, LHIP, LANK):
        m["knee_L"] = angle(xy[LHIP], xy[LKNE], xy[LANK])
    if need(RKNE, RHIP, RANK):
        m["knee_R"] = angle(xy[RHIP], xy[RKNE], xy[RANK])
    if need(LSHO, LELB, LHIP):
        m["shoulder_L"] = angle(xy[LELB], xy[LSHO], xy[LHIP])
    if need(RSHO, RELB, RHIP):
        m["shoulder_R"] = angle(xy[RELB], xy[RSHO], xy[RHIP])
    if need(LHIP, LSHO, LKNE):
        m["hip_L"] = angle(xy[LSHO], xy[LHIP], xy[LKNE])
    if need(RHIP, RSHO, RKNE):
        m["hip_R"] = angle(xy[RSHO], xy[RHIP], xy[RKNE])
    if need(LSHO, RSHO, LHIP, RHIP):
        sho = (xy[LSHO] + xy[RSHO]) / 2
        hip = (xy[LHIP] + xy[RHIP]) / 2
        d = sho - hip
        m["trunk_lean"] = float(np.degrees(np.arctan2(d[0], -d[1])))  # 0 = upright, +ve leaning right
        m["shoulder_h_m"] = float((ank_mid[1] - sho[1]) / px_per_m)
        m["hip_h_m"] = float((ank_mid[1] - hip[1]) / px_per_m)
        m["shoulder_w_px"] = float(np.linalg.norm(xy[LSHO] - xy[RSHO]))
    if ok[NOSE]:
        m["nose_h_m"] = float((ank_mid[1] - xy[NOSE][1]) / px_per_m)
    for name, i in (("wristL", LWRI), ("wristR", RWRI)):
        if ok[i]:
            m[f"{name}_h_m"] = float((ank_mid[1] - xy[i][1]) / px_per_m)
    m["kp_ok"] = int(ok.sum())
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", required=True)
    ap.add_argument("--marks", required=True, help="video.json with manual_court_keypoints")
    ap.add_argument("--onnx", default="tools/models/onnx/posen.960.fp16.onnx")
    ap.add_argument("--frames", type=int, default=1300)
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    marks = json.load(open(a.marks))["manual_court_keypoints"]
    h, below_net = resolved_homography(marks)
    hinv = np.linalg.inv(h)
    sess = ort.InferenceSession(a.onnx, providers=["CPUExecutionProvider"])
    dtype = np.float16 if "float16" in sess.get_inputs()[0].type else np.float32
    name = sess.get_inputs()[0].name

    cap = cv2.VideoCapture(a.video)
    for _ in range(a.start):
        cap.grab()
    rows = []
    kp_ok_counts = np.zeros(17)
    frames_with_player = 0
    for i in range(a.start, a.start + a.frames):
        ok, frame = cap.read()
        if not ok:
            break
        t = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        canvas, scale, px, py = letterbox(frame)
        x = (canvas.astype(np.float32) / 255.0).transpose(2, 0, 1)[None].astype(dtype)
        out = np.asarray(sess.run(None, {name: x})[0], np.float32).reshape(-1, STRIDE)
        best = None
        for bc, k in decode(out, scale, px, py):
            if k[LANK, 2] < MIN_KP or k[RANK, 2] < MIN_KP:
                continue
            mid = (k[LANK, :2] + k[RANK, :2]) / 2
            if not below_net(mid):
                continue
            cx, cy = to_court(h, mid)
            if not (0 <= cx <= W and 0 <= cy <= L):
                continue
            if best is None or bc > best[0]:
                best = (bc, k)
        if best is None:
            rows.append({"frame": i, "t": t})
            continue
        frames_with_player += 1
        kp_ok_counts += best[1][:, 2] >= MIN_KP
        m = metrics(best[1], h, hinv)
        m.update({"frame": i, "t": t, "box": best[0]})
        rows.append(m)
        if i % 100 == 0:
            print(f"frame {i}", file=sys.stderr)
    cap.release()
    json.dump({"rows": rows, "kp_ok_counts": kp_ok_counts.tolist(),
               "frames_with_player": frames_with_player}, open(a.out, "w"))
    print(f"wrote {a.out}: {len(rows)} frames, {frames_with_player} with near player")


if __name__ == "__main__":
    main()
