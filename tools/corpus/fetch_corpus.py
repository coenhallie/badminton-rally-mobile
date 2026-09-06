#!/usr/bin/env python3
"""Pull one video's full cloud output into a local corpus directory.

Reads SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY from the environment.
Service role because results.json sits behind per-owner storage RLS and we
want this to work for any video, not only the caller's.
"""
import argparse, json, os, sys
from pathlib import Path

from supabase import create_client


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("video_id")
    ap.add_argument("--out", default="corpus", help="corpus root directory")
    args = ap.parse_args()

    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        print("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set", file=sys.stderr)
        return 2

    sb = create_client(url, key)

    # postgrest's .single() raises (rather than returning an empty result) on
    # zero matching rows, so the "no row" case surfaces as an exception here,
    # not as a falsy .data.
    try:
        row = sb.table("videos").select("*").eq("id", args.video_id).single().execute().data
    except Exception as e:
        print(f"no videos row for {args.video_id}: {e}", file=sys.stderr)
        return 1
    if not row.get("results_storage_path"):
        print(f"{args.video_id} has no results_storage_path yet", file=sys.stderr)
        return 1

    blob = sb.storage.from_("results").download(row["results_storage_path"])
    results = json.loads(blob)

    clips = (
        sb.table("rally_clips")
        .select("*")
        .eq("video_id", args.video_id)
        .order("rally_index")
        .execute()
        .data
    )

    out = Path(args.out) / args.video_id
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(json.dumps(results))
    # Full videos row, PII and all (owner_id, title, player_labels,
    # storage_path). Fine to keep locally - this directory is never
    # committed (see tools/corpus/README.md) - but trim_corpus.py strips
    # video.json down before writing a fixture that IS committed.
    (out / "video.json").write_text(json.dumps(row))
    (out / "clips.json").write_text(json.dumps(clips))

    shuttle = results.get("shuttle_positions", {})
    visible = sum(1 for p in shuttle.values() if p.get("visible"))
    print(f"captured {args.video_id}")
    print(f"  phase           : {results.get('phase')}")
    print(f"  fps             : {results.get('fps')}")
    print(f"  total_frames    : {results.get('total_frames')}")
    print(f"  rallies         : {len(results.get('rallies', []))}")
    print(f"  rally_clips rows: {len(clips)}")
    print(f"  shuttle visible : {visible}/{len(shuttle)}")
    print(f"  skeleton_data   : {len(results.get('skeleton_data', []))} frames")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
