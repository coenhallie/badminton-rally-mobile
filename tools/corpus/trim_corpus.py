#!/usr/bin/env python3
"""Trim a captured corpus entry to a time window for use as a CI fixture.

Frame numbers and timestamps are NOT rebased. A fixture is a window onto the
original, so any index in it means the same thing it meant in the full capture.

Every per-frame array must be trimmed for that claim to hold. A `completed`
capture carries FOUR of them, not one: `shuttle_positions`, `skeleton_data`,
`skeleton_frames`, and the per-player `positions` inside `analytics`. An
earlier version trimmed only the first two, so `skeleton_frames` - the largest
key in a completed capture by an order of magnitude - travelled into fixtures
at full video length while the file claimed to be a window.

Phase 2 payload is dropped by default because fixtures get committed and the
Phase 1 golden tests never read it: keeping it made a 68-second fixture 38MB,
of which 33.8MB was untrimmed skeleton_frames. Pass --keep-phase2 for a
fixture meant to exercise pose work, and expect to need a much shorter window.
"""
import argparse, json
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="corpus/<video_id> directory")
    ap.add_argument("dst", help="output fixture directory")
    ap.add_argument("--start", type=float, required=True, help="seconds")
    ap.add_argument("--end", type=float, required=True, help="seconds")
    ap.add_argument(
        "--keep-phase2",
        action="store_true",
        help="keep skeleton_frames/skeleton_data/analytics/players (large)",
    )
    args = ap.parse_args()

    src, dst = Path(args.src), Path(args.dst)
    results = json.loads((src / "results.json").read_text())
    fps = float(results.get("fps") or 30.0)
    f0, f1 = int(args.start * fps), int(args.end * fps)

    results["shuttle_positions"] = {
        k: v for k, v in results.get("shuttle_positions", {}).items() if f0 <= int(k) <= f1
    }
    # Every per-frame array, not just the two that used to be handled here.
    for key in ("skeleton_data", "skeleton_frames"):
        if results.get(key):
            results[key] = [f for f in results[key] if f0 <= f.get("frame", -1) <= f1]

    # analytics.players[].positions is per-frame too, one level down.
    analytics = results.get("analytics")
    if isinstance(analytics, dict):
        for player in analytics.get("players") or []:
            if isinstance(player.get("positions"), list):
                player["positions"] = [
                    p for p in player["positions"] if f0 <= p.get("frame", -1) <= f1
                ]
    for player in results.get("players") or []:
        if isinstance(player.get("positions"), list):
            player["positions"] = [
                p for p in player["positions"] if f0 <= p.get("frame", -1) <= f1
            ]

    # Phase 1 consumes TWO tracks and the cloud persists both, in different
    # shapes. `shuttle_positions` is the FILTERED track that feeds the gradient
    # detector. The shot-gap detector instead reads `skeleton_frames`, whose
    # per-frame `shuttle_position` is a fusion of TrackNet and YOLO detections
    # and is far less filtered - on one capture, 4355 frames against 3015.
    #
    # Reproducing the cloud's rally list needs both, so project the fusion
    # track out of skeleton_frames before the Phase 2 payload is dropped.
    # Kept as frame/x/y only: the full skeleton_frames array is 33MB for a
    # 68-second window, this projection is under 100KB.
    fusion = {}
    for f in results.get("skeleton_frames") or results.get("skeleton_data") or []:
        pos = f.get("shuttle_position")
        if pos and pos.get("x") is not None and f0 <= f.get("frame", -1) <= f1:
            fusion[str(f["frame"])] = {"x": pos["x"], "y": pos["y"]}
    if fusion:
        results["fusion_shuttle_track"] = fusion

    if not args.keep_phase2:
        # Dropped rather than trimmed: a Phase 1 fixture never reads these, and
        # trimmed they still dominate the file. video_width and video_height
        # stay - the fixture loader reads them.
        for key in ("skeleton_data", "skeleton_frames", "analytics", "players"):
            results.pop(key, None)

    results["rallies"] = [
        r for r in results.get("rallies", [])
        if r["start_timestamp"] >= args.start and r["end_timestamp"] <= args.end
    ]
    results["trimmed_window"] = {"start": args.start, "end": args.end,
                                 "start_frame": f0, "end_frame": f1}

    clips = [
        c for c in json.loads((src / "clips.json").read_text())
        if c["start_timestamp"] >= args.start and c["end_timestamp"] <= args.end
    ]

    # Fixtures under this trimmed output get committed (see
    # tools/corpus/README.md), but the full videos row captured by
    # fetch_corpus.py carries production PII: owner_id, title, and
    # player_labels (real people's names), plus storage_path. Project the
    # row down to only what a fixture consumer actually reads today -
    # manual_court_keypoints, for homography - plus id, before it lands in
    # a committed fixture. Nothing else identifying goes into git history.
    video = json.loads((src / "video.json").read_text())
    video_fixture = {
        "id": video.get("id"),
        "manual_court_keypoints": video.get("manual_court_keypoints"),
    }

    dst.mkdir(parents=True, exist_ok=True)
    (dst / "results.json").write_text(json.dumps(results))
    (dst / "video.json").write_text(json.dumps(video_fixture))
    (dst / "clips.json").write_text(json.dumps(clips))
    size_mb = (dst / "results.json").stat().st_size / 1e6
    print(f"trimmed to {args.start}-{args.end}s: "
          f"{len(results['shuttle_positions'])} shuttle frames, "
          f"{len(results['rallies'])} rallies, {len(clips)} clips, "
          f"{len(results.get('fusion_shuttle_track') or {})} fusion frames, "
          f"results.json {size_mb:.2f} MB"
          f"{'' if args.keep_phase2 else ' (phase 2 payload dropped)'}")
    if size_mb > 5:
        print("  warning: this is large for a committed fixture; "
              "narrow the window or drop --keep-phase2")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
