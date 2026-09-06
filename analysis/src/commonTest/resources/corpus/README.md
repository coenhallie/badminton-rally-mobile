# Corpus fixtures

Captured from real processed videos with `tools/corpus/fetch_corpus.py`, then
trimmed by `tools/corpus/trim_corpus.py`. `stages.json` in each is written by
`tools/corpus/make_stage_goldens.py`, which runs badminton-tracker's own rally
detectors over the fixture's tracks.

| fixture | video | fps | frames | rallies | why it is here |
|---|---|---|---|---|---|
| `sample` | `2eabfc01` | 25.0 | 12032 | 26 | `completed`, so it carries the fusion track; the shot-gap detector has real work to do |
| `0a654e34` | `0a654e34` | 50.0 | 18969 | 24 | `phase1`, so it has NO fusion track - exercises the empty raw shot-gap path |
| `743d7fb1` | `743d7fb1` | 29.7357... | 5972 | 11 | a fractional frame rate, which is where the truncated `int()` thresholds bite |
| `synthetic` | none | 30.0 | 600 | 2 | hand-authored, for the loader's parser only - never a fidelity oracle |

**Whole videos, not slices.** Rally detection is global: the shot-gap
detector's minimum-gap rule and visibility gate read across neighbouring
rallies, and the gradient detector strides the whole track. A 68-second window
of `sample` gave 2 rallies where the cloud's in-window list had 4, purely
because the cloud had seen the other 11 minutes. A window can smoke-test the
loader; it cannot check a detector.

**Phase 2 payload is dropped.** `skeleton_frames` alone is 33MB against
`sample`'s 0.85MB, and no Phase 1 test reads it. What is kept from it is the
projected `fusion_shuttle_track`, which the shot-gap detector needs.

**No PII.** `video.json` is projected down to `id` and
`manual_court_keypoints`. The captured row also carries `owner_id`, `title` and
`player_labels`; none of that reaches git. Full captures live outside the repo.
