#!/usr/bin/env python3
"""A cloud track file, from a local run, for checking the Analysis panels on a device.

docs/plans/2026-09-29-two-player-tracking-design.md section 7 ("On a device"):
before the worker is deployed, a device can be shown what a new worker would
install by placing this file where the app keeps an entry's cloud track.

    ./gradlew :analysis:exportCorpusRun --args="$HOME/shuttl-corpus/<id> <pose file>"
    python tools/models/cloud_track_from_run.py <the *-frames.json it wrote> <out.track>

The format is PlayerTrackStore's v3, as `saveAll` writes it on both platforms:

    v3 <fps> <trackCount>
    <SIDE> <framesWithPose> <sampleCount>
    <frame>,<x>,<y>        (sampleCount rows)
    ...                    (per side)

with the fps the export carries (normalizeFps of the header, CloudPoseOutcome's).

On the Android emulator (keep the original to put back):

    adb exec-out run-as com.badmintontracker.android cat files/cloud-tracks/<videoId>.track > original.track
    adb push out.track /data/local/tmp/t.track
    adb shell "run-as com.badmintontracker.android sh -c 'cat /data/local/tmp/t.track > files/cloud-tracks/<videoId>.track'"

Only the track: the Skeleton panel keeps reading the entry's own skeleton file.
"""
import json
import sys


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: cloud_track_from_run.py <frames.json> <out.track>")
    run = json.loads(open(sys.argv[1]).read())
    lines = [f"v3 {run['fps']} 2"]
    for side in ("NEAR", "FAR"):
        s = run["sides"][side]
        lines.append(f"{side} {s['framesWithPose']} {len(s['samples'])}")
        lines += [f"{x['frame']},{x['x']},{x['y']}" for x in s["samples"]]
    with open(sys.argv[2], "w") as out:
        out.write("\n".join(lines) + "\n")
    print(f"wrote {sys.argv[2]}: " + ", ".join(
        f"{side} {len(run['sides'][side]['samples'])}" for side in ("NEAR", "FAR")))


if __name__ == "__main__":
    main()
