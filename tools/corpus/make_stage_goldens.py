#!/usr/bin/env python3
"""Record what badminton-tracker's own rally detectors produce for a fixture.

The Kotlin port is checked against these, stage by stage, rather than against
`results.json`'s `rallies`. That is deliberate and not a weaker check.

`results.json`'s rally list cannot be reproduced from a capture at all. It is
the union of the gradient detector over the filtered track and the shot-gap
detector over Phase 1's `skeleton_frames` - and Phase 1's skeleton frames are
never persisted. A `completed` capture's `skeleton_frames` come from Phase 2's
full YOLO loop (`modal_supabase_processor.py:4642`), which overwrote them, and
a `phase1` capture has none at all. So one input to the stored list is gone by
the time anyone reads it.

Running the cloud's own detectors on the tracks the fixture DOES carry gives a
determinate answer for every stage, which is a stronger comparison than a
single end number whose inputs are unavailable.

Usage:
    python tools/corpus/make_stage_goldens.py sample [more fixtures...] [--tracker-repo=PATH]
"""
import json
import sys
from pathlib import Path

# badminton-tracker sits BESIDE this repo, not inside it: parents[2] is the
# repo root, so its sibling is one level further up.
DEFAULT_TRACKER = Path(__file__).resolve().parents[3] / "badminton-tracker"
TRACKER = Path(
    next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--tracker-repo=")), DEFAULT_TRACKER)
)
TRACKER_BACKEND = TRACKER / "backend"
if not TRACKER_BACKEND.is_dir():
    print(f"badminton-tracker backend not found at {TRACKER_BACKEND}; "
          f"pass --tracker-repo=<path>", file=sys.stderr)
    raise SystemExit(2)
sys.path.insert(0, str(TRACKER_BACKEND))

from rally_detection import detect_rallies  # noqa: E402
from rally_detection_shot_gap import (  # noqa: E402
    detect_rallies_from_shots,
    refine_rallies,
    union_rallies,
)

FIXTURES = Path("analysis/src/commonTest/resources/corpus")


def bounds(rallies):
    return [[r["start_frame"], r["end_frame"]] for r in rallies]


def stages_for(name: str) -> dict:
    results = json.loads((FIXTURES / name / "results.json").read_text())
    fps, total = results["fps"], results["total_frames"]
    filtered = {int(k): v for k, v in results["shuttle_positions"].items()}
    fusion = {int(k): v for k, v in (results.get("fusion_shuttle_track") or {}).items()}

    # Frames are built exactly as Phase1Pipeline builds them: index 0 to
    # total-1, timestamp = frame / fps. The cloud uses container PTS instead,
    # which on the captures here differs by at most 0.08s against a 3.1s gap
    # threshold; using frame/fps on BOTH sides keeps this a test of the port
    # rather than of the timestamp source.
    def frames(lookup):
        return [
            {"frame": f, "timestamp": f / fps, "shuttle_position": lookup(f)}
            for f in range(total)
        ]

    fusion_frames = frames(
        lambda f: {"x": fusion[f]["x"], "y": fusion[f]["y"]} if f in fusion else None
    )
    filtered_frames = frames(
        lambda f: {"x": filtered[f]["x"], "y": filtered[f]["y"]}
        if f in filtered and filtered[f].get("visible")
        else None
    )

    gradient = detect_rallies(filtered, fps=fps, total_frames=total)
    raw = detect_rallies_from_shots(fusion_frames, fps, require_players=False)
    filt = detect_rallies_from_shots(filtered_frames, fps, require_players=False)
    union = union_rallies(gradient, raw, fps=fps)
    refined = refine_rallies(filt, raw, fps=fps) or union

    return {
        "gradient": bounds(gradient),
        "raw_shot_gap": bounds(raw),
        "filtered_shot_gap": bounds(filt),
        "union": bounds(union),
        "refined": bounds(refined),
    }


def main() -> int:
    names = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not names:
        print(__doc__, file=sys.stderr)
        return 2
    for name in names:
        stages = stages_for(name)
        (FIXTURES / name / "stages.json").write_text(json.dumps(stages, indent=1))
        print(f"{name}: " + "  ".join(f"{k}={len(v)}" for k, v in stages.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
