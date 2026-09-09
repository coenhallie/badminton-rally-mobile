# Design: what to build next for the coach

**Date:** 2026-08-28
**Status:** Assessment, pending scope decision
**Trigger:** "what else can we implement which would help a badminton coach"
**Follows:** `2026-08-26-live-scoring-review-design.md` (the competitor assessment
and the L0-L4 layering), `2026-08-28-match-video-attach-design.md` (just shipped)

This is an assessment, not an implementation plan. It surveys what a badminton
coach actually needs, gives each candidate a verdict, and recommends a running
order. No paired `-plan.md` is written yet.

Two kinds of claim appear below and they are marked differently. **Verified**
means read in this repo, with a file reference. **Reported** means it comes from
`badminton-tracker`, the other repo, and is quoted from the earlier review
design rather than re-read. Everything else is cited literature.

---

## 1. What we have today, verified

- **The pipeline.** Import or record a video, mark the court on 12 points, and a
  Modal job returns one clip per detected rally. `AnalyzeCoordinator` runs
  UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING and resumes from the failed
  step.
- **Per-clip notes.** Timestamped annotations carrying user-owned colour labels,
  rolled up per match by `buildMatchLabelSummary`.
- **The seeded label set is exactly the standard badminton split.** Every new
  account gets `Good shot` / `Forced error` / `Unforced error`
  (`supabase/migrations/20260824000000_annotation_labels.sql:50-53`). That is
  not a coincidence worth ignoring: it is the same three-way division the
  literature reports as the primary match statistic (§3).
- **Live scoring.** A match is created before any video exists, scored courtside
  by tapping a side, and tagged per point from that same label palette.
- **The fold already records more than any surface reads.** `ScoredPoint`
  (`shared/.../scoring/MatchState.kt:84-95`) carries `ordinal`, `gameIndex`,
  `wonBy`, `scoreBefore`, `scoreAfter`, **`servedBy`**, `tags` and `comment`.
  The server of every rally is a field, not a derivation.
- **Attaching a video to a scored match**, shipped today: the pipeline binds
  `score_logs.video_id`, the row reports its progress, and the match ends up as
  one page with Points and Rallies facets.

What we do **not** have, also verified:

- **No player identity.** `ScoreLog.homePlayers` is `List<String>`, free text
  per match. Two matches cannot know they share a player.
- **No side on a tag.** `PointTag` is `(labelName, labelColor)`. A rally can be
  marked "Unforced error" without recording whose.
- **No shot count or placement on a clip.** `RallyClip` carries timings, paths,
  a title and an annotation count. Nothing about what happened inside the rally.
- **No point-to-rally mapping.** Deliberately: the reconcile step (L2b) is
  designed in §7 of the 2026-08-26 review and not built.

---

## 2. The asset nobody else has

Every competitor surveyed has either automatic video analysis or a scorekeeper.
[goSmash](https://gosmash.app/) and [BadPro+](https://badproplus.com/) do shot
classification and heatmaps; the app reviewed on 2026-08-26 does scoring. We now
have both halves plus court homography, pose and shuttle tracking.

That matters because of the bottleneck the research keeps naming. Harvard's VIRD
study of high-performance coaching found match analysis "is time-consuming and
relies heavily on manual note-taking due to lack of automatic data collection
and appropriate visualization tools". A human-authored point sequence is exactly
the ground truth an automatic detector has never had, and an automatic detector
is exactly the labour a note-taking coach has never had.

Neither half is worth much alone. Bound together they are a category nobody
currently occupies.

---

## 3. What the research says coaches need

- **The winners / forced / unforced split is the primary statistic.** Rio 2016
  analysis reports roughly 36% direct winners, 23% forced errors, 41% unforced
  errors. Unforced errors dominate individual matches; forced errors are more
  prevalent in team events.
- **Errors decide matches more than winners do.** A 2025 perturbation study
  found negative impulses (bad shots) caused **64.8%** of rally-deciding moments
  against 35.2% for positive ones, and converted to a point loss **84.3%** of
  the time where good shots converted at 69-73%. Its conclusion: reframe
  training "from how to win points toward how to prevent losing them." Poor
  lobs and clears generated the costliest mistakes.
- **Rally length is a diagnostic.** Elite rallies run 9-10 seconds and 7.5-9
  strokes. A club rally averaging four strokes is a statement about errors.
- **Serve and receive patterns are recorded by hand today.** Whether a player
  wins more serving long or short is named explicitly as something coaches
  track manually.
- **Court coverage and movement efficiency** are the most-advertised feature in
  the category, usually as heatmaps.
- **Club coaches are not elite coaches.** VIRD's subjects were Olympic and
  national staff wanting flexible viewpoints on spatial data. Club-level
  services are sold as hours of coach time, around GBP 50 for one match. The
  club coach's scarce resource is time, not spatial fidelity.

---

## 4. Capability-by-capability verdict

| # | Capability | Verdict | Cost | Why |
| --- | --- | --- | --- | --- |
| 1 | **Reconcile: map point N to rally N** | **Build first** | M | Already designed in full (§7 L2 of the 2026-08-26 review): the offset question, the plus-or-minus nudge, skipping a detected rally, "bound k of n" stated rather than implied. Until it exists, the points and the clips are two lists on one page. After it, every clip carries `score_at_start`, `serving_side`, `game_index`, `point_won_by`, and every capability below gets better. |
| 2 | **Player identity** | **Build** | S | A `players` table plus a join from `score_logs`. Cheap, and the precondition for every longitudinal question. Without it no statistic can outlive a single match, and "is this player improving" is unanswerable. This is what turns the product from *analyse this match* into *coach this player*. |
| 3 | **Error ledger: winners / forced / unforced, per side** | **Build** | S | The labels already exist and are already the right three. What is missing is attribution, and after #1 it is derived rather than asked for: a `Forced error` on a point `wonBy` the away side is home's error. Present it errors-first, per §3's perturbation finding. |
| 4 | **Serve and receive outcomes** | **Build** | XS | `ScoredPoint.servedBy` is already a field, and `serviceCourt` is already folded. Points won serving versus receiving, and from the right court versus the left, are a pure function of the existing log. **Needs no video at all**, so it works on a score-only match, which is most of them. |
| 5 | **Rally length and stroke count per clip** | **Build** | S (cross-repo) | Reported: `rally_detection.py` already groups shuttle direction reversals into rallies with a minimum shot count, so the count exists and is discarded. `RallyClip` has no column for it (verified). One column, one pipeline change, one grant pass. |
| 6 | **Sessions: several matches grouped on one date** | **Build reduced** | S | The 20% of tournament mode that buys 80%, already identified in the 2026-08-26 review. Club nights are many short matches on one evening, and the match record almost gives this away free. Not brackets, not seeding, not roles. |
| 7 | **Court coverage heatmap, distance covered** | **Build later** | M (cross-repo) | Homography and pose already produce court coordinates, so the data exists. The work is entirely in the Python pipeline plus new columns. Most-advertised feature in the category, and the one a coach can read at a glance. |
| 8 | **Shuttle landing placement chart** | **Build later** | M (cross-repo) | Where rallies ended, as a court plot. Needs trajectory endpoint extraction. Pairs naturally with #7. |
| 9 | **Shot classification (clear / drop / smash / net / drive / lift)** | **Build later, reduced** | L | What every competitor leads with, and the largest ML lift here. §3 suggests the coaching value concentrates in a coarse split: the costly mistakes were poor lobs and clears, so attacking / neutral / defensive would carry most of it at a fraction of the cost. Do not start here. |
| 10 | **Longitudinal trends across matches** | **Build later** | M | Falls out of #2 plus #3 and #4. Worthless before #2, nearly free after it. |
| 11 | **Technique scoring, form analysis, per-shot pose critique** | **Decline** | L | The category is full of it and it is the part that needs the most ML for the least defensible accuracy. Our edge is the match record, not the swing. |
| 12 | **Drill library, session planning** | **Decline** | M | A different product with entrenched incumbents (Sportplan, Sportlogic). Nothing we know about a match makes us better at prescribing a drill. |
| 13 | **Club administration: scheduling, payments, invoicing, registration** | **Decline** | L | Named as the real pain in club-management marketing, and genuinely a pain, but it is a business-operations product. Building it would double the surface area and halve the focus. |
| 14 | **Tournament brackets, seeding, roles, multi-court** | **Decline** | L | Already declined on 2026-08-26 and the reasoning holds. #6 is the part worth taking. |
| 15 | **Multi-sport** | **Decline** | L | Already declined. The whole pipeline is badminton-specific. |

---

## 5. Recommended order, and why it is this order

**1. Reconcile (#1).** It is designed, it is the promise the video-attach work
implicitly made, and it is the multiplier: #3 needs it for side attribution,
#7 and #8 become filterable by score situation because of it, and the point
count it produces is the first honest measure of how far the detector drifts.

**2. Player identity (#2).** Cheap, dull, and the single change with the longest
tail. Every statistic built before it is stranded in one match.

**3. The two ledgers (#3 and #4).** Together they are a match report a coach can
act on, drawn entirely from data already in hand. #4 in particular costs almost
nothing and works with no video, which means it lands for every match ever
scored rather than only the filmed ones.

**Then reassess.** #5 is a small cross-repo follow-up. #7 through #9 are a
second, larger programme and should get their own design once the first three
have been in a coach's hands, because what he reaches for will reorder them.

The sequence compounds: each step makes the next more useful, and none of the
first four needs any new machine learning.

---

## 6. Constraints worth stating before anyone plans this

- **#5, #7 and #8 are cross-repo.** They are Python pipeline work in
  `badminton-tracker` plus migrations against `rally_clips`, which the Modal
  pipeline writes. Per `0004` and `0008`, any new column the client must read
  needs its own revoke-then-grant-by-column pass, and a service-role smoke test
  will not catch its absence.
- **#2 changes an existing shape.** `score_logs.home_players` is a `text[]` with
  a CHECK constraint. Introducing a `players` table means a migration that keeps
  existing free-text names working, not a replacement.
- **#3 depends on #1 for its best version.** A side-attributed error ledger
  without reconcile would have to ask the coach which side erred, adding a tap
  per rally to the one surface where taps are expensive. With reconcile it is
  derived. Do not build it first and regret the input.
- **The `score_logs` migration is still unapplied on the server.** Every
  capability here reads or writes that table.
  **Update 2026-09-09: no longer true.** `supabase migration list --linked` on
  2026-09-07 shows it applied. Reconcile, player identity and the ledgers are
  unblocked on the server side.

---

## 7. Open questions for the coach

1. **Is he coaching individuals or a club?** #2 and #10 are worth much more for
   a coach following eight named players over a season than for one recording
   his own matches.
2. **Does he want the report during the match or after it?** #3 and #4 are
   computable live, on the board, with no video. If a between-games readout is
   what he wants, that is a different and smaller surface than a post-match page.
3. **How much drift does the detector actually have?** Nobody knows yet. The
   first reconciled match answers it, and the answer should shape #1's UI rather
   than be guessed at now.

---

## Sources

- [Long rallies and next rally performances in elite badminton, PLOS One](https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0229604)
- [Perturbations in badminton: why avoiding bad shots is more important than executing good ones, 2025](https://journals.sagepub.com/doi/10.1177/17479541251317477)
- [Statistical differences in set analysis at the Rio 2016 Olympic Games, Frontiers](https://www.frontiersin.org/journals/psychology/articles/10.3389/fpsyg.2019.00731/full)
- [Match-play data according to playing categories in badminton: a systematic review, Frontiers](https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2025.1466778/full)
- [VIRD: immersive match video analysis for high-performance badminton coaching, Harvard VCG](https://vcg.seas.harvard.edu/publications/20231023-vird-immersive-match-video-analysis)
- [CoachAI: microscopic badminton match data collection and tactical analysis](https://arxiv.org/pdf/1907.12888)
- [Nacsport: using video analysis to improve badminton performance](https://www.nacsport.com/blog/en-gb/Tips/badminton-performance-analysis)
- [goSmash](https://gosmash.app/) and [BadPro+](https://badproplus.com/), surveyed as current competitors
