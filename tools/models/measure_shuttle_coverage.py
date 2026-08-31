#!/usr/bin/env python3
"""Two independent measurements against the on-device TrackNet/InpaintNet conversion.

(a) THE GATE - conversion fidelity. Runs the SAME source video through
    production's own pipeline - TrackNetInference.track_video in
    ../badminton-tracker/backend/tracknet/inference.py - twice: once with the
    real PyTorch TrackNet (and, unless --tracknet-only is given, the real
    PyTorch InpaintNet), and once with those same attributes replaced by a
    thin ONNX Runtime shim (_OnnxModelShim below). track_video calls its
    models as plain attributes (`self.tracknet(batch_tensor)` at
    inference.py:298, `self.inpaintnet(inp_tensor)` at inference.py:416), so
    swapping the attribute is all that is needed; nothing here reimplements
    background computation, blob detection, gap-filling, or coordinate
    scaling - it is the same production code on both sides. Any divergence
    can only come from the ONNX conversion (or, with --tracknet-only, from
    TrackNet's conversion alone). This needs no cloud data at all, and it
    alone drives GATE PASS/FAIL and the exit code.

    HONEST CAVEAT ON WHAT THIS DOES NOT PROVE: this script forces
    device="cpu" so it can run anywhere, which means the PyTorch reference
    side runs fp32. TrackNetInference.use_half is only True when
    torch.cuda.is_available(), and production always runs on CUDA, so
    production's real reference is fp16-on-GPU, compared against a fp16
    ONNX model. This gate instead compares fp32-on-CPU torch against that
    same fp16 ONNX model, which is a STRICTER comparison than the
    fp16-to-fp16 one it stands in for (fp32 has less rounding error to
    begin with, so any divergence measured here is not smaller than what
    fp16-to-fp16 would show, and is plausibly larger). Stricter is the safe
    direction for a gate, but it is not the identical comparison production
    runs, and a reader should not read these numbers as what happens on the
    GPU.

(b) INFORMATIONAL - reimplementation fidelity, NOT a gate. Compares the
    local PyTorch track_video output (which, as of this rewrite, already
    runs the full production pipeline: median background, blob detection,
    InpaintNet gap-fill, scaling to the video's native resolution) against
    the cloud's results.json["shuttle_positions"]. That field is not raw
    model output either: it is the result of _build_shuttle_positions_dict
    (backend/modal_supabase_processor.py:4073, assigned into the payload at
    :4193), which applies court-ROI polygon rejection, static-cluster
    suppression, and minimum-movement suppression on top of
    TrackNetInference.track_video's own output. The expected-divergence
    caveat here is now much weaker than it used to be: both sides finally
    run the same background/blob-detection/inpainting/scaling, so what
    remains is the cloud's three extra suppression passes plus the ONNX
    conversion and any decode differences - not a whole reimplemented
    pipeline. A divergence in this section still must never be used as a
    pass/fail signal, and no coverage ratio is computed for it. Only
    suppression-invariant statistics are reported: how many frames both
    consider the shuttle visible, and the pixel delta on those frames.

The shot-gap detector rejects a candidate rally outright when fewer than 25%
of frames in its window are visible, so a coverage collapse costs whole
rallies rather than precision - which is why (a) exists as a hard gate at all.

PERFORMANCE NOTE: this runs track_video (full median-background sampling
plus per-frame inference) twice, once per side, entirely on CPU. Use a short
clip - but "short" is not sufficient on its own. _run_inpaintnet skips
itself entirely when a trajectory is nearly-fully visible or nearly-fully
missing (inference.py:363), which a short, easy, fully-tracked clip is
likely to be. Pick (or trim to) a clip that actually contains a real gap in
shuttle detection - a lost-behind-the-body moment, a fast smash, a frame the
shuttle leaves frame - so the InpaintNet half of the deployed configuration
is actually exercised. See EXIT CODES below for how this script tells you
when it was not.

EXIT CODES, checked worst-first so a real failure can never be masked by a
weaker signal from later in the list:
  0 - GATE PASS. Measured on its own terms, and (if both models were
      swapped) InpaintNet's shim was actually called.
  1 - GATE FAIL: visibility agreement or position delta missed threshold,
      on the gate's own measured terms. Takes priority over 3 below: a
      catastrophic ONNX collapse can itself be severe enough to also trip
      production's near-missing skip (inference.py:363) and leave
      InpaintNet's shim uncalled, and that run must be reported as FAIL,
      not steered toward "pick a better clip" - inpaintnet_unexercised is
      still recorded in the JSON either way.
  2 - UNMEASURABLE: the video would not open, decoded no frames, or the
      shuttle was visible too rarely in the torch reference to measure
      anything (GATE_MIN_VISIBLE_FRACTION).
  3 - INPAINTNET UNEXERCISED: the gate would otherwise have been a clean
      PASS, but both models were swapped (the default, deployed
      configuration) and the InpaintNet shim was never actually called on
      this video, so this run did not validate InpaintNet's conversion
      despite the "swapped" label - see the PERFORMANCE NOTE above.
      gate_pass in the JSON report is forced false for this case, so
      neither this exit code nor the JSON can be mistaken for a validated
      deployed-config pass.
  4 - CRASH: the ONNX side of the gate itself raised (a bad ONNX file, a
      shape mismatch, an ONNX Runtime internal error). Distinct from 1
      (FAIL) because no divergence was actually measured - the conversion
      could not even be run, which is not the same claim as "ran and
      diverged". A report is written recording the crash, under its own
      "-crashed" filename so it can never overwrite a prior successful
      run's report for the same video and configuration.
"""
import argparse, json, subprocess, sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import torch

TRACKNET_ONNX_DEFAULT = "tools/models/onnx/tracknet.fp16.onnx"
INPAINTNET_ONNX_DEFAULT = "tools/models/onnx/inpaintnet.fp16.onnx"
TRACKNET_WEIGHTS_DEFAULT = "tools/models/weights/tracknet.pt"
INPAINTNET_WEIGHTS_DEFAULT = "tools/models/weights/inpaintnet.pt"

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
# never above the visibility threshold would score visibility_agreement ==
# 1.0 (both sides silently agree "not visible" every frame) - a falsely
# reassuring GATE PASS with nothing actually measured. This is keyed on the
# torch reference's own visible fraction alone, deliberately not on whether
# both sides agreed on any visible frame: if torch sees the shuttle often
# but ONNX never agrees, that is a real, measurable divergence
# (visibility_agreement collapses) and must reach GATE FAIL, not be
# reported as UNMEASURABLE.
GATE_MIN_VISIBLE_FRACTION = 0.05


class _OnnxModelShim:
    """Drop-in replacement for a torch model attribute on TrackNetInference.

    track_video's only contact with its models is two plain attribute
    calls - `self.tracknet(batch_tensor)` and `self.inpaintnet(inp_tensor)`.
    Assigning an instance of this class over one of those attributes routes
    exactly that call through ONNX Runtime while every other line of
    track_video (background, blob detection, gap-filling, scaling) keeps
    running unmodified.
    """

    def __init__(self, onnx_path):
        self.sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        self.input_name = self.sess.get_inputs()[0].name
        # _run_inpaintnet skips the model entirely when the trajectory is
        # nearly-fully visible or nearly-fully missing (inference.py:363)
        # and skips individual chunks that are all-visible or all-invisible
        # (inference.py:388-390), so on some videos the InpaintNet shim is
        # never actually called even though it was swapped in. Counting
        # calls here is how main() detects and reports that, instead of
        # silently gating "TrackNet + InpaintNet" while only TrackNet was
        # ever measured.
        self.calls = 0

    def __call__(self, x: torch.Tensor) -> torch.Tensor:
        self.calls += 1
        arr = x.detach().cpu().numpy().astype(np.float32)
        # track_video batches TrackNet calls at batch_size (default 16,
        # --batch-size here), but both ONNX exports use a static batch
        # dimension of 1 (export_tracknet.py's dummy input is
        # (1, in_dim, 288, 512); InpaintNet's is (1, 3, length)). Loop over
        # the batch here instead of forcing batch_size=1 on the torch side
        # too, so production's own batching is left untouched on both sides.
        outs = [self.sess.run(None, {self.input_name: arr[i:i + 1]})[0] for i in range(arr.shape[0])]
        out = np.concatenate(outs, axis=0)
        return torch.from_numpy(out).to(dtype=x.dtype, device=x.device)


def _build_tracker(tracknet_weights, inpaintnet_weights):
    from tracknet.inference import TrackNetInference

    tracker = TrackNetInference(device="cpu")
    tracker.load_weights(str(tracknet_weights), str(inpaintnet_weights))
    return tracker


def _video_dims(video_path, fallback_w, fallback_h):
    """Read only the video's frame dimensions; no frame decoding.

    Needed to convert the position deltas track_video returns (original
    video pixel space, per inference.py's w_scale/h_scale) back into the
    512x288 model space that GATE_DELTA_PX_MAX and check_tracknet_parity.py's
    1px bound are both calibrated in, so "2px at 512x288" means the same
    thing regardless of the source video's resolution. track_video already
    proved this path opens (it raises RuntimeError otherwise, caught in
    main()), so this second, metadata-only open is not reintroducing that
    check - if it were ever to fail here despite that, dims fall back to the
    model's own size (scale 1.0) rather than crashing on a division by zero.
    """
    cap = cv2.VideoCapture(str(video_path))
    try:
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)) or fallback_w
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)) or fallback_h
    finally:
        cap.release()
    return w, h


def _tracker_repo_commit(tracker_repo):
    """Best-effort git commit hash of the (read-only) tracker repo checkout,
    for provenance in the report JSON - so a report can be traced back to
    exactly which version of the production pipeline it ran. Returns None
    rather than raising if this cannot be determined (not a git checkout,
    git not on PATH, or any other lookup failure); provenance is a nicety,
    not something worth failing a multi-minute run over.
    """
    try:
        result = subprocess.run(
            ["git", "-C", str(tracker_repo), "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except Exception:
        pass
    return None


def _percentile_sorted(sorted_values: list, p: float):
    """Nearest-rank percentile over an already-sorted list, or None if empty."""
    if not sorted_values:
        return None
    idx = min(int(len(sorted_values) * p), len(sorted_values) - 1)
    return sorted_values[idx]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video", help="path to the source mp4")
    ap.add_argument("--tracker-repo", required=True,
                     help="path to the badminton-tracker checkout (read-only); "
                          "needed to import TrackNetInference for the gate")
    ap.add_argument("--onnx", default=TRACKNET_ONNX_DEFAULT, help="path to the exported TrackNet fp16 ONNX model")
    ap.add_argument("--inpaintnet-onnx", default=INPAINTNET_ONNX_DEFAULT,
                     help="path to the exported InpaintNet fp16 ONNX model")
    ap.add_argument("--tracknet-weights", default=TRACKNET_WEIGHTS_DEFAULT)
    ap.add_argument("--inpaintnet-weights", default=INPAINTNET_WEIGHTS_DEFAULT)
    ap.add_argument("--tracknet-only", action="store_true",
                     help="swap only TrackNet's ONNX model, leaving the real PyTorch "
                          "InpaintNet on both sides. Isolates TrackNet's conversion when "
                          "the combined gate fails. Default swaps BOTH TrackNet and "
                          "InpaintNet ONNX models, which is the deployed on-device "
                          "configuration and is what this gate should normally run as.")
    ap.add_argument("--batch-size", type=int, default=16,
                     help="passed through to track_video's batch_size")
    ap.add_argument("--max-bg-samples", type=int, default=300,
                     help="passed through to track_video's max_bg_samples")
    ap.add_argument("--corpus", default=None,
                     help="optional corpus/<video_id> directory; enables the informational "
                          "(b) comparison against the cloud's results.json. Not required for the gate.")
    args = ap.parse_args()

    sys.path.insert(0, str(Path(args.tracker_repo) / "backend"))
    from tracknet.inference import WIDTH as MODEL_W, HEIGHT as MODEL_H

    torch_tracker = _build_tracker(args.tracknet_weights, args.inpaintnet_weights)
    onnx_tracker = _build_tracker(args.tracknet_weights, args.inpaintnet_weights)
    tracknet_shim = _OnnxModelShim(args.onnx)
    onnx_tracker.tracknet = tracknet_shim
    inpaintnet_shim = None
    if not args.tracknet_only:
        inpaintnet_shim = _OnnxModelShim(args.inpaintnet_onnx)
        onnx_tracker.inpaintnet = inpaintnet_shim
    swapped = "TrackNet only" if args.tracknet_only else "TrackNet + InpaintNet (deployed config)"

    try:
        torch_positions = torch_tracker.track_video(
            args.video, batch_size=args.batch_size, max_bg_samples=args.max_bg_samples)
    except RuntimeError as e:
        # track_video's own cap.isOpened() check (inference.py:145-146) is
        # what catches a bad path or unreadable file now; this script no
        # longer opens the video itself for decoding, only for the metadata
        # read in _video_dims, so this is production's own loud failure,
        # not a reimplementation of it.
        print(e, file=sys.stderr)
        return 2

    if not torch_positions:
        print(f"no frames decoded from video: {args.video}", file=sys.stderr)
        return 2

    report_dir = Path("tools/models/reports")
    report_dir.mkdir(parents=True, exist_ok=True)
    name = Path(args.corpus).name if args.corpus else Path(args.video).stem
    # Disambiguated by configuration: a --tracknet-only isolation run must
    # not silently overwrite the deployed-config report for the same video,
    # or a later "which run produced this JSON" question has no answer.
    config_suffix = "tracknet-only" if args.tracknet_only else "full"
    report_path = report_dir / f"coverage-{name}-{config_suffix}.json"
    # Deliberately a distinct filename, not the same path with an
    # overwrite-if-crashed guard: a crash report is written unconditionally
    # below on the ONNX-crash path, and must never be able to clobber a
    # prior successful multi-minute run's report for this same video and
    # configuration just because this run happened to fail.
    crash_report_path = report_dir / f"coverage-{name}-{config_suffix}-crashed.json"

    provenance = {
        "video": str(args.video),
        "tracker_repo": str(args.tracker_repo),
        "tracker_repo_commit": _tracker_repo_commit(args.tracker_repo),
        "tracknet_weights": str(args.tracknet_weights),
        "inpaintnet_weights": str(args.inpaintnet_weights),
        "tracknet_onnx": str(args.onnx),
        "inpaintnet_onnx": str(args.inpaintnet_onnx) if not args.tracknet_only else None,
        "swapped": swapped,
        "batch_size": args.batch_size,
        "max_bg_samples": args.max_bg_samples,
    }

    try:
        onnx_positions = onnx_tracker.track_video(
            args.video, batch_size=args.batch_size, max_bg_samples=args.max_bg_samples)
    except Exception as e:
        # Distinct from GATE FAIL (exit 1): nothing was actually measured
        # here, the ONNX side itself did not run to completion - a bad ONNX
        # file, a shape mismatch, an ONNX Runtime internal error. Treating
        # this as exit 1 would make "the conversion diverged" and "the
        # conversion could not even be evaluated" indistinguishable to a
        # caller checking the exit code, and previously would also have
        # lost the report entirely, since it was only ever written at the
        # very end of a multi-minute run.
        print(f"ONNX side crashed while running track_video: {e}", file=sys.stderr)
        print(f"crash report written to {crash_report_path} (not {report_path}, "
              f"which is left untouched if it already holds a prior result)", file=sys.stderr)
        crash_report_path.write_text(json.dumps({
            "provenance": provenance,
            "crashed": True,
            "crash_phase": "onnx track_video",
            "error": str(e),
        }, indent=2))
        return 4

    orig_w, orig_h = _video_dims(args.video, MODEL_W, MODEL_H)
    w_scale = orig_w / MODEL_W
    h_scale = orig_h / MODEL_H

    # --- (a) THE GATE ---------------------------------------------------
    frames_a = sorted(set(torch_positions) & set(onnx_positions))
    agree = sum(1 for f in frames_a if torch_positions[f]["visible"] == onnx_positions[f]["visible"])
    both_a = [f for f in frames_a if torch_positions[f]["visible"] and onnx_positions[f]["visible"]]

    def _delta_model_px(f):
        a, b = torch_positions[f], onnx_positions[f]
        dx = (a["x"] - b["x"]) / w_scale
        dy = (a["y"] - b["y"]) / h_scale
        return (dx * dx + dy * dy) ** 0.5

    deltas_a = sorted(_delta_model_px(f) for f in both_a)
    torch_visible_fraction = sum(1 for f in frames_a if torch_positions[f]["visible"]) / max(len(frames_a), 1)
    # Keyed on the torch reference's own visible fraction alone - NOT on
    # len(both_a) > 0. If torch sees the shuttle often but ONNX agrees on
    # none of those frames, that is a real, measurable divergence: agree
    # can be at most (1 - torch_visible_fraction) in that case, so
    # visibility_agreement necessarily falls below VISIBILITY_AGREEMENT_MIN
    # and the gate_ok check below fails on that term before it would ever
    # reach the (then-None) p95 comparison - see the GATE_MIN_VISIBLE_FRACTION
    # comment above. That is meant to reach GATE FAIL, not UNMEASURABLE.
    measurable = torch_visible_fraction >= GATE_MIN_VISIBLE_FRACTION

    gate = {
        "frames": len(frames_a),
        "visibility_agreement": agree / max(len(frames_a), 1),
        "both_visible": len(both_a),
        "torch_visible_fraction": torch_visible_fraction,
        "median_delta_px_at_512x288": _percentile_sorted(deltas_a, 0.5),
        "p95_delta_px_at_512x288": _percentile_sorted(deltas_a, 0.95),
        "tracknet_shim_calls": tracknet_shim.calls,
        "inpaintnet_shim_calls": inpaintnet_shim.calls if inpaintnet_shim is not None else None,
    }
    # gate_ok_raw is what the gate measured, on its own terms, ignoring
    # whether InpaintNet's shim ever got called. Belt-and-braces: the
    # `gate["p95..."] is not None` guard is redundant with the proof above
    # (GATE_MIN_VISIBLE_FRACTION + VISIBILITY_AGREEMENT_MIN > 1 forces the
    # `and` to short-circuit before reaching a None p95), but costs nothing
    # to state explicitly here.
    gate_ok_raw = measurable and (
        gate["visibility_agreement"] >= VISIBILITY_AGREEMENT_MIN
        and gate["p95_delta_px_at_512x288"] is not None
        and gate["p95_delta_px_at_512x288"] <= GATE_DELTA_PX_MAX
    )
    # _run_inpaintnet skips the model entirely on a nearly-fully-visible or
    # nearly-fully-missing trajectory (inference.py:363) and skips
    # individual all-visible/all-invisible chunks (inference.py:388-390).
    # On such a video the InpaintNet shim is swapped in but never called, so
    # a PASS here would only have measured TrackNet despite the "swapped"
    # label saying otherwise - the same silent-no-op failure mode
    # GATE_MIN_VISIBLE_FRACTION exists to catch for the whole gate.
    inpaintnet_unexercised = inpaintnet_shim is not None and inpaintnet_shim.calls == 0

    # Priority, worst-first: a gate that already failed on its own measured
    # terms must be reported as FAIL, even when InpaintNet's shim also went
    # unexercised on the same run - a catastrophic ONNX collapse that also
    # trips production's own near-missing skip at inference.py:363 must not
    # be reported as "pick a better clip" when the true story is "the
    # conversion is broken". INPAINTNET_UNEXERCISED only applies to a run
    # that would otherwise have been a clean PASS: the one case where "the
    # measurement itself is not trustworthy as deployed-config coverage" is
    # the only thing wrong with it. Both signals are always kept in the
    # JSON regardless of which one decided the exit code.
    if not measurable:
        exit_code = 2
        reported_gate_pass = False
    elif not gate_ok_raw:
        exit_code = 1
        reported_gate_pass = False
    elif inpaintnet_unexercised:
        exit_code = 3
        reported_gate_pass = False
    else:
        exit_code = 0
        reported_gate_pass = True

    print(f"=== (a) GATE: conversion fidelity (PyTorch fp32/CPU vs ONNX fp16; swapped: {swapped}) ===")
    for k, v in gate.items():
        print(f"  {k:30}: {v}")
    if not measurable:
        print(f"  GATE UNMEASURABLE - the shuttle was visible in the torch reference on "
              f"fewer than {GATE_MIN_VISIBLE_FRACTION:.0%} of frames, so there is nothing "
              f"to measure position drift on. This is not evidence the conversion is "
              f"fine; check the video path and that it actually shows play.")
    elif not gate_ok_raw:
        print("  GATE FAIL")
        if inpaintnet_unexercised:
            print("  (InpaintNet's shim also went unexercised on this run, but the gate "
                  "already failed on its own measured terms - that is reported first; "
                  "see inpaintnet_shim_calls in the JSON.)")
    elif inpaintnet_unexercised:
        print("  GATE INPAINTNET UNEXERCISED (exit 3) - visibility agreement and delta "
              "both passed, but InpaintNet's ONNX shim was swapped in and never called "
              "on this video (its trajectory had too few gaps, or too few detections, "
              "for _run_inpaintnet to invoke it), so this run did not validate "
              "InpaintNet's conversion despite the \"swapped\" label above. gate_pass "
              "is forced false. Re-run against a video with a real gap in shuttle "
              "detection.")
    else:
        print("  GATE PASS")

    report = {"provenance": provenance, "gate": gate, "gate_measurable": measurable,
              "gate_pass": reported_gate_pass, "inpaintnet_unexercised": inpaintnet_unexercised,
              "informational": None}
    report_path.write_text(json.dumps(report, indent=2))

    # --- (b) INFORMATIONAL -----------------------------------------------
    # Wrapped end to end: a typo'd --corpus, a malformed results.json, or
    # any other failure in this section must never reach the exit code or
    # overwrite the gate-only report already written above. (b) is
    # advisory only, and a crash in it is not a gate failure.
    if args.corpus:
        try:
            results = json.loads((Path(args.corpus) / "results.json").read_text())
            cloud = {int(k): v for k, v in results.get("shuttle_positions", {}).items()}
            frames_b = sorted(set(torch_positions) & set(cloud))
            both_b = [f for f in frames_b if cloud[f].get("visible") and torch_positions[f]["visible"]]

            def _delta_orig_px(f):
                a, b = torch_positions[f], cloud[f]
                return ((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2) ** 0.5

            deltas_b = sorted(_delta_orig_px(f) for f in both_b)
            informational = {
                "note": ("NOT a gate: local now runs the same production pipeline as the "
                         "cloud (median background, blob detection, InpaintNet gap-fill, "
                         "scaling to native resolution) via TrackNetInference.track_video, "
                         "so this is no longer comparing raw model output to a filtered "
                         "track. What remains is the cloud's court-ROI polygon rejection, "
                         "static-cluster suppression, and minimum-movement suppression "
                         "(_build_shuttle_positions_dict, modal_supabase_processor.py:4073) "
                         "applied on top of its own track_video output, plus the ONNX "
                         "conversion and any decode differences. A divergence here is still "
                         "expected, for a narrower set of reasons than before, and must not "
                         "be used as a pass/fail signal."),
                "frames_compared": len(frames_b),
                "both_visible": len(both_b),
                "median_delta_px": _percentile_sorted(deltas_b, 0.5),
                "p95_delta_px": _percentile_sorted(deltas_b, 0.95),
            }
            print("=== (b) INFORMATIONAL: reimplementation fidelity vs cloud (NOT a gate) ===")
            for k, v in informational.items():
                print(f"  {k:30}: {v}")
            report["informational"] = informational
            report_path.write_text(json.dumps(report, indent=2))
        except Exception as e:
            print("=== (b) INFORMATIONAL: failed, skipped (does not affect the gate) ===",
                  file=sys.stderr)
            print(f"  {e}", file=sys.stderr)
    else:
        print("=== (b) INFORMATIONAL: skipped (no --corpus given) ===")

    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
