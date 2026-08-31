#!/usr/bin/env python3
"""Trim a captured corpus entry to a time window for use as a CI fixture.

Frame numbers and timestamps are NOT rebased. A fixture is a window onto the
original, so any index in it means the same thing it meant in the full capture.
"""
import argparse, json
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="corpus/<video_id> directory")
    ap.add_argument("dst", help="output fixture directory")
    ap.add_argument("--start", type=float, required=True, help="seconds")
    ap.add_argument("--end", type=float, required=True, help="seconds")
    args = ap.parse_args()

    src, dst = Path(args.src), Path(args.dst)
    results = json.loads((src / "results.json").read_text())
    fps = float(results.get("fps") or 30.0)
    f0, f1 = int(args.start * fps), int(args.end * fps)

    results["shuttle_positions"] = {
        k: v for k, v in results.get("shuttle_positions", {}).items() if f0 <= int(k) <= f1
    }
    if results.get("skeleton_data"):
        results["skeleton_data"] = [
            f for f in results["skeleton_data"] if f0 <= f["frame"] <= f1
        ]
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

    dst.mkdir(parents=True, exist_ok=True)
    (dst / "results.json").write_text(json.dumps(results))
    (dst / "video.json").write_text((src / "video.json").read_text())
    (dst / "clips.json").write_text(json.dumps(clips))
    print(f"trimmed to {args.start}-{args.end}s: "
          f"{len(results['shuttle_positions'])} shuttle frames, "
          f"{len(results['rallies'])} rallies, {len(clips)} clips")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
