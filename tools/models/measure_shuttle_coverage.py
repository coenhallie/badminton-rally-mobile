#!/usr/bin/env python3
"""Two independent measurements against the on-device TrackNet conversion.

(a) THE GATE - conversion fidelity. Runs the SAME decoded video through the
    PyTorch TrackNet model and through the exported ONNX fp16 model, applying
    byte-identical postprocessing to both (tools/models/_common.py). Any
    divergence here can only come from the fp16 export, since both sides see
    the same frames and the same argmax/threshold logic. This needs no cloud
    data at all, and it alone drives GATE PASS/FAIL and the exit code.

(b) INFORMATIONAL - reimplementation fidelity, NOT a gate. Compares the local
    PyTorch output against the cloud's results.json["shuttle_positions"].
    That field is not raw model output: it is the result of
    _build_shuttle_positions_dict (backend/modal_supabase_processor.py:4073,
    assigned into the payload at :4193), which applies court-ROI polygon
    rejection, static-cluster suppression, and minimum-movement suppression
    on top of TrackNetInference.track_video's own blob detection and
    InpaintNet gap-filling - none of which runs here. A divergence in this
    section reflects that pipeline gap, not the ONNX conversion, so it must
    never be used as a pass/fail signal, and no coverage ratio is computed
    for it. Only suppression-invariant statistics are reported: how many
    frames both consider the shuttle visible, and the pixel delta on those
    frames.

The shot-gap detector rejects a candidate rally outright when fewer than 25%
of frames in its window are visible, so a coverage collapse costs whole
rallies rather than precision - which is why (a) exists as a hard gate at all.
"""
import argparse, json, sys
from pathlib import Path

import cv2
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _common as common

# Same reasoning as check_tracknet_parity.py's 1px bound: a peak shift this
# small is inside the noise the static-cluster filter already tolerates. The
# delta threshold here is a little looser than that script's (2px instead of
# 1px) because this runs across a whole real video instead of one synthetic
# frame, so a handful of near-tie frames can nudge the median or p95 without
# meaning anything; the visibility-agreement bound is what actually catches
# an fp16 rounding error flipping whether the shuttle is seen at all.
VISIBILITY_AGREEMENT_MIN = 0.99
GATE_DELTA_PX_MAX = 2.0
# The delta half of the gate is only meaningful if the shuttle was actually
# seen often enough to measure a position drift on. Without this, a video
# with the wrong path, a black clip, or one where the shuttle is essentially
# never above VIS_THRESHOLD would score visibility_agreement == 1.0 (both
# sides silently agree "not visible" every frame) and both_visible == 0 - a
# falsely reassuring GATE PASS with nothing actually measured, the exact
# failure mode this script was rewritten to eliminate.
GATE_MIN_VISIBLE_FRACTION = 0.05


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video", help="path to the source mp4")
    ap.add_argument("--tracker-repo", required=True,
                     help="path to the badminton-tracker checkout (read-only); "
                          "needed to load the PyTorch TrackNet class for the gate")
    ap.add_argument("--onnx", default=common.ONNX_DEFAULT, help="path to the exported fp16 ONNX model")
    ap.add_argument("--corpus", default=None,
                     help="optional corpus/<video_id> directory; enables the informational "
                          "(b) comparison against the cloud's results.json. Not required for the gate.")
    args = ap.parse_args()

    model, in_dim, seq_len = common.load_torch_model(args.tracker_repo)
    sess = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])

    try:
        cap = common.open_video(args.video)
    except RuntimeError as e:
        print(e, file=sys.stderr)
        return 2

    try:
        orig_w = cap.get(cv2.CAP_PROP_FRAME_WIDTH) or common.WIDTH
        orig_h = cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or common.HEIGHT

        # Gate (a): model-space (512x288) positions from both sides.
        gate_torch: dict = {}
        gate_onnx: dict = {}
        # Informational (b): PyTorch positions rescaled to the video's own
        # resolution, to compare against the cloud's coordinate space.
        torch_orig: dict = {}

        for start, stack, count in common.iter_batches(cap, seq_len):
            hm_t = common.run_torch(model, stack)
            hm_o = common.run_onnx(sess, stack)
            t_model = common.heatmap_to_positions(hm_t, common.WIDTH, common.HEIGHT, count)
            o_model = common.heatmap_to_positions(hm_o, common.WIDTH, common.HEIGHT, count)
            t_orig = common.heatmap_to_positions(hm_t, orig_w, orig_h, count)
            for i in range(count):
                idx = start + i
                gate_torch[idx] = t_model[i]
                gate_onnx[idx] = o_model[i]
                torch_orig[idx] = t_orig[i]
    finally:
        cap.release()

    if not gate_torch:
        print(f"no frames decoded from video: {args.video}", file=sys.stderr)
        return 2

    # --- (a) THE GATE ---------------------------------------------------
    frames_a = sorted(gate_torch)
    agree = sum(1 for f in frames_a if gate_torch[f]["visible"] == gate_onnx[f]["visible"])
    both_a = [f for f in frames_a if gate_torch[f]["visible"] and gate_onnx[f]["visible"]]
    deltas_a = sorted(common.euclidean(gate_torch[f], gate_onnx[f]) for f in both_a)
    torch_visible_fraction = sum(1 for f in frames_a if gate_torch[f]["visible"]) / max(len(frames_a), 1)
    measurable = torch_visible_fraction >= GATE_MIN_VISIBLE_FRACTION and len(both_a) > 0

    gate = {
        "frames": len(frames_a),
        "visibility_agreement": agree / max(len(frames_a), 1),
        "both_visible": len(both_a),
        "torch_visible_fraction": torch_visible_fraction,
        "median_delta_px_at_512x288": common.percentile_sorted(deltas_a, 0.5),
        "p95_delta_px_at_512x288": common.percentile_sorted(deltas_a, 0.95),
    }
    gate_ok = measurable and (
        gate["visibility_agreement"] >= VISIBILITY_AGREEMENT_MIN
        and gate["p95_delta_px_at_512x288"] <= GATE_DELTA_PX_MAX
    )

    print("=== (a) GATE: conversion fidelity (PyTorch vs ONNX fp16) ===")
    for k, v in gate.items():
        print(f"  {k:30}: {v}")
    if not measurable:
        print(f"  GATE UNMEASURABLE - the shuttle was visible on fewer than "
              f"{GATE_MIN_VISIBLE_FRACTION:.0%} of frames (or never agreed visible "
              f"on both sides), so there is nothing to measure position drift on. "
              f"This is not evidence the conversion is fine; check the video path "
              f"and that it actually shows play.")
    else:
        print("  GATE PASS" if gate_ok else "  GATE FAIL")

    # --- (b) INFORMATIONAL -----------------------------------------------
    informational = None
    if args.corpus:
        results = json.loads((Path(args.corpus) / "results.json").read_text())
        cloud = {int(k): v for k, v in results.get("shuttle_positions", {}).items()}
        frames_b = sorted(set(torch_orig) & set(cloud))
        both_b = [f for f in frames_b if cloud[f].get("visible") and torch_orig[f]["visible"]]
        deltas_b = sorted(common.euclidean(torch_orig[f], cloud[f]) for f in both_b)
        informational = {
            "note": ("NOT a gate: cloud shuttle_positions is a filtered track (court-ROI "
                     "rejection, static-cluster suppression, minimum-movement suppression on "
                     "top of blob detection + InpaintNet gap-fill); local is raw model output. "
                     "A divergence here is expected and is not evidence about the ONNX conversion."),
            "frames_compared": len(frames_b),
            "both_visible": len(both_b),
            "median_delta_px": common.percentile_sorted(deltas_b, 0.5),
            "p95_delta_px": common.percentile_sorted(deltas_b, 0.95),
        }
        print("=== (b) INFORMATIONAL: reimplementation fidelity vs cloud (NOT a gate) ===")
        for k, v in informational.items():
            print(f"  {k:30}: {v}")
    else:
        print("=== (b) INFORMATIONAL: skipped (no --corpus given) ===")

    report = {"gate": gate, "gate_measurable": measurable, "gate_pass": gate_ok, "informational": informational}
    Path("tools/models/reports").mkdir(parents=True, exist_ok=True)
    name = Path(args.corpus).name if args.corpus else Path(args.video).stem
    Path(f"tools/models/reports/coverage-{name}.json").write_text(json.dumps(report, indent=2))

    if not measurable:
        return 2
    return 0 if gate_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
