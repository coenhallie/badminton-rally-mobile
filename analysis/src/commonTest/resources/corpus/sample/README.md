# `sample` corpus fixture

Captured from a real processed video and trimmed by `tools/corpus/trim_corpus.py`.

**Provenance**
- video id: `2eabfc01-7ba6-4d0c-a481-0f7591a0851c`
- phase: `completed`, fps 25.0, 12032 frames, 26 rallies, 26 `rally_clips` rows
- window: the whole video (`--start 0 --end 100000`), not a slice

**Why the whole video and not a window.** Rally detection is global: the
shot-gap detector's minimum-gap rule and its shuttle-visibility gate both read
across neighbouring rallies, and the gradient detector strides across the whole
track. A 68-second window of this same capture produced 2 rallies where the
cloud's in-window list had 4, purely because the cloud had seen the other 11
minutes. A window can smoke-test the loader; it cannot check a detector.

**What it carries.** `results.json` holds both tracks Phase 1 consumes:
`shuttle_positions` (the cloud's FILTERED track, feeding the gradient detector)
and `fusion_shuttle_track` (projected out of `skeleton_frames`, the TrackNet and
YOLO per-frame fusion feeding the shot-gap detector). The Phase 2 payload is
dropped: `skeleton_frames` alone is 33MB against this file's 0.85MB, and no
Phase 1 test reads it.

`video.json` is projected down to `id` and `manual_court_keypoints`. The full
captured row carries `owner_id`, `title` and `player_labels`, none of which
belongs in git.
