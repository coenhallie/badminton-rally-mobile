#!/usr/bin/env python3
"""Fetch every source weight the on-device pipeline needs and pin its SHA.

Three different provenances, deliberately handled separately so a change in
any one of them is visible in the manifest diff:
  - tracknet/inpaintnet: Modal volume badminton-tracker-models
  - badminton detector : copied from the badminton-tracker checkout
  - yolo26m-pose       : resolved by Ultralytics, which is why it needs pinning
"""
import argparse, hashlib, json, shutil, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path

WEIGHTS = Path("tools/models/weights")
MANIFEST = Path("tools/models/manifest.json")


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pull_from_modal(remote: str, dest: Path) -> None:
    if dest.exists():
        # `modal volume get` refuses to overwrite an existing destination and
        # aborts under check=True, which would otherwise make this script
        # fail on every re-run after the first. Skipping an existing file
        # makes a re-run idempotent; delete tools/models/weights/ first to
        # force a refetch.
        print(f"skip {dest} (already present; delete it to refetch)")
        return
    subprocess.run(
        ["modal", "volume", "get", "badminton-tracker-models", remote, str(dest)],
        check=True,
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tracker-repo", required=True,
                    help="path to the badminton-tracker checkout (read-only)")
    args = ap.parse_args()

    WEIGHTS.mkdir(parents=True, exist_ok=True)
    entries = {}

    pull_from_modal("tracknet/TrackNet_best.pt", WEIGHTS / "tracknet.pt")
    entries["tracknet"] = {"source": "modal://badminton-tracker-models/tracknet/TrackNet_best.pt"}

    pull_from_modal("tracknet/InpaintNet_best.pt", WEIGHTS / "inpaintnet.pt")
    entries["inpaintnet"] = {"source": "modal://badminton-tracker-models/tracknet/InpaintNet_best.pt"}

    src = Path(args.tracker_repo) / "backend/models/badminton/weights/best.pt"
    if not src.exists():
        print(f"detector weight not found at {src}", file=sys.stderr)
        return 1
    shutil.copy2(src, WEIGHTS / "badminton.pt")
    entries["badminton"] = {"source": "badminton-tracker:backend/models/badminton/weights/best.pt"}

    # Ultralytics resolves this by name at runtime in the cloud worker, which is
    # exactly why it is unpinned there. Downloading it once and recording the
    # SHA is the whole point of this step.
    #
    # Pass an explicit destination path rather than a bare filename: YOLO()
    # downloads a not-yet-local checkpoint to exactly the path it is given,
    # and a bare filename resolves relative to the current working directory
    # (the repo root, per this script's README), dropping ~50MB of untracked
    # .pt there. Downloading straight into WEIGHTS keeps it inside the
    # already-gitignored tools/models/weights/.
    from ultralytics import YOLO
    pose_src = WEIGHTS / "yolo26m-pose.pt"
    m = YOLO(str(pose_src))
    shutil.copy2(m.ckpt_path, WEIGHTS / "pose.pt")
    entries["pose"] = {"source": "ultralytics://yolo26m-pose.pt"}

    now = datetime.now(timezone.utc).isoformat()
    for name, meta in entries.items():
        p = WEIGHTS / f"{name}.pt"
        meta["sha256"] = sha256(p)
        meta["bytes"] = p.stat().st_size
        meta["retrieved_at"] = now
        print(f"{name:12} {meta['bytes']:>12,} bytes  {meta['sha256'][:16]}")

    MANIFEST.write_text(json.dumps({"weights": entries}, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
