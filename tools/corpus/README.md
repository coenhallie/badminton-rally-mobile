# Verification corpus

Everything in the on-device gates plan measures against this corpus.
`badminton-tracker` has no saved `results.json` anywhere, so capturing a
corpus here also closes that gap: without it, every threshold in the cloud
pipeline is unfalsifiable.

## What a corpus entry is

For each video, a directory `<corpus_root>/<video_id>/` containing:

- `results.json` - the full cloud analysis output (shuttle positions, rallies,
  skeleton data, phase, fps, total_frames, etc)
- `video.json` - the `videos` table row for that video
- `clips.json` - the `rally_clips` table rows for that video, ordered by
  `rally_index`

Trimmed fixtures (a time-windowed subset of a full capture, small enough to
commit) land in `analysis/src/commonTest/resources/corpus/<name>/` with the
same three files. Frame numbers and timestamps in a trimmed fixture are not
rebased: an index in a fixture means the same thing it meant in the full
capture, so a fixture is always a window onto a specific full corpus entry,
never a renumbered clip.

## How to capture

Requires `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` in the environment.
Service role is required because `results.json` sits behind per-owner
storage RLS and capture needs to work for any video, not only the caller's
own.

```bash
python tools/corpus/fetch_corpus.py <video_id> --out corpus
```

This writes `corpus/<video_id>/{results.json,video.json,clips.json}` and
prints a summary: phase, fps, total_frames, rally count, rally_clips row
count, shuttle visibility fraction, and skeleton_data frame count.

Pick videos that differ from each other: one with many short rallies, one
with long rallies, and one where the cloud produced a low rally count. A
video still at `phase1` has no `skeleton_data`, which is fine for the
on-device gates work but will not serve later pose-related work, so note
which captured videos are `phase1` versus `completed` when recording them
below.

The full corpus is never committed to this repository. It is large binary
and JSON output tied to specific production video rows, and it lives outside
the repo (wherever `--out` points, kept locally or in private storage).

## How to trim

```bash
python tools/corpus/trim_corpus.py corpus/<video_id> \
    analysis/src/commonTest/resources/corpus/<name> \
    --start 0 --end 180
```

Pick a window containing at least three complete rallies (rallies whose
`start_timestamp` and `end_timestamp` both fall inside `[start, end]`). The
script prints the resulting shuttle frame count, rally count, and clip count;
confirm the printed rally count is at least 3 before committing a fixture.

Trimmed fixtures under `analysis/src/commonTest/resources/corpus/` are small
enough to commit and are what CI and local test runs exercise.

## Corpus status

**Not yet captured.** No video has been pulled through `fetch_corpus.py` and
no fixture has been trimmed into
`analysis/src/commonTest/resources/corpus/` yet. This environment has no
`SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` configured, so capture could not
be run as part of this change.

To finish this task, a human with Supabase credentials needs to:

1. Pick three videos that differ as described above (many short rallies,
   long rallies, low rally count from the cloud).
2. Run `fetch_corpus.py` for each and record the printed summary in this
   section (phase, fps, total_frames, rallies, rally_clips rows, shuttle
   visibility, skeleton_data frames).
3. Run `trim_corpus.py` against at least one of them to produce a fixture
   with 3+ complete rallies in `analysis/src/commonTest/resources/corpus/`.
4. Replace this status section with the actual video IDs, summaries, and
   what each fixture is meant to exercise.
