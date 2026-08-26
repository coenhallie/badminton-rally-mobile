# Design: live scoring - competitor review and implementation assessment

**Date:** 2026-08-26
**Status:** Assessment, pending scope decision
**Subject:** [Badminton Score & Scoreboard](https://apps.apple.com/us/app/badminton-score-scoreboard/id6747377276) (App Store id 6747377276)
**Trigger:** the badminton coach asked for "similar capabilities" in our app,
then described the flow he actually wants (§2).

This is an assessment, not an implementation plan. It reviews the competitor,
gives every one of its capabilities a verdict, records the constraints in our
codebase that decide how any of it can be built, and designs the flow the coach
described. The paired `-plan.md` is deliberately not written yet.

The coach's description arrived after the competitor review and is a **scope
reduction**, not an addition: it rules out more of that app's feature set than
it asks for, and it removes the one piece of prerequisite engineering the first
draft of this document called essential.

---

## 1. What the app actually is

### 1.1 Listing facts

| | |
| --- | --- |
| Name / subtitle | Badminton Score & Scoreboard - "Badminton Scorekeeper·Watch·TV" |
| Developer | zhenyu song |
| Released | 2025-06-20. Current version 2.6.28, 2026-07-30 |
| Price | Free. IAP: Remove Ads $4.99 one-time, Remove Ads (Yearly) $1.99 |
| Size | 76.2 MB |
| Ratings | **5.0 from 1 rating** |
| Platforms | iOS 13+, iPadOS, watchOS 9+, macOS 11+ (M1), visionOS 1 |
| Languages | 18 |
| Privacy | Tracks Location, Identifiers, Usage Data. Third-party advertising SDK collecting coarse location, device id, product interaction, ad data |
| Site | https://scoreboard.100for1.com (branded "ScoreSyncer", "25+ sports, one scoring engine") |

### 1.2 It is one engine, reskinned

The description text calls the product "BadmintonPoints", the listing calls it
"Badminton Score & Scoreboard", the website calls it "ScoreSyncer", and the
same developer ships Pickleball, Volleyball, Tennis, Ping Pong, Basketball and
Soccer versions of it. The badminton app is a per-sport App Store listing over
a sport-agnostic scoring core. The description even ends by cross-selling the
umbrella app.

That matters for us in one way only: none of the badminton-specific depth a
coach would want (rally quality, serve statistics, error attribution) can be
in there, because the core has to stay sport-neutral. It is a scoreboard.

### 1.3 Read of the screenshots

I pulled all six iPhone screenshots and reviewed them.

- **Scoreboard / Net view.** Landscape, split red/blue, giant numerals, game
  count in small boxes between them, four player-name chips with R/L service
  court markers, the serving player highlighted green. Net view is the same
  surface rotated for an umpire sitting at the net, with names running
  vertically down the outer edges. This is genuinely good design for the job:
  readable across a court, one tap per point.
- **Quick controls.** An eight-tile sheet: Reset Score, Reset Game, Switch
  Court, Announce Score, Coin Toss, Vuvuzela Sound, Whistle Sound, Score
  History. Half of it is real umpiring, half is novelty.
- **Tournament mode.** A list of live and completed matches, each card showing
  team names, pair names, current game score, per-game results (`21-18`,
  `19-21`), and which device is scoring it ("Scored by: iPhone 12"). That
  "scored by" line is the honest core of the product: several phones, one
  tournament, one live view.
- **Tournament hub.** Create/join by code or QR, role per tournament (Creator /
  Judge / Viewer), Results / Share / Manage / Message actions.
- **Settings.** Language, tournament on/off, net view on/off, match timer,
  automatic score announcement, factory reset.
- **Browser live scoreboard.** Phone as scorekeeper, a desktop browser at
  `.../live/abc123` showing the same board with a "Live / Updated 2s ago"
  header and a per-game strip along the bottom.

Two picky observations, since they bear on how much of this is real. The
Settings screenshot shows "Version 1.0.12" while the store serves 2.6.28, and
the tournament screenshots are framed in an Android device with an Android back
arrow and gesture bar. The marketing set is stale and cross-platform, which is
normal, but it means the screenshots are not evidence that the current build
looks like this.

### 1.4 The honest summary

This is a competent, well-marketed, ad-supported commodity scoreboard in a
category with at least ten near-identical competitors (the App Store's own "you
might also like" lists Badminton: Score Counter, Score Badminton, Badminton
Scoreboard: Rally, ScoreMine, Simple Badminton Scoreboard, Badminton4U). It has
**one rating**, fourteen months after release. Its sole review asks for an
Apple Watch app that the subtitle already advertises.

None of that makes the coach wrong. What the coach saw is a set of screenshots
of things our app cannot do at all, and the request is legitimate. But it does
mean the target is a feature set, not a validated product, and we are free to
take the parts that compound with what we already have and decline the parts
that only exist to fill a store listing.

---

## 2. The flow the coach asked for

Verbatim: *"We will create a match first and then human will do the score up to
15 or 21 points for the game, at the same time, add comments or quick tags
(forced error, serve mistake, etc.) according the ones have been defined. Then
we can upload the recorded video to the newly created game."*

Read as four steps:

1. **Create the match first.** The match record exists before any video does.
2. **Score it live**, to 15 or 21 points.
3. **Tag as you score.** Per point, attach a comment or a quick tag drawn from
   *the labels already defined in the app*. His examples ("forced error", "serve
   mistake") are almost exactly the labels we seed every account with: `Good
   shot`, `Forced error`, `Unforced error`.
4. **Upload the recorded video afterwards**, onto that already-created match.

Four consequences, and they are the whole design:

- **The match is no longer the video.** Today "match" is a `videos` row and
  everything hangs off `videos.id`. Here a match is created empty and a video
  joins it later, or never. That inversion is the central architectural change.
- **The scoring device is not the recording device.** He scores courtside on a
  phone while the match is filmed on a tripod or a club camera, and uploads
  later. This is what removes the in-app recorder from scope entirely (§6.2).
- **The tags are the point, not the score.** A scoreboard that forgets the match
  is a commodity. A per-point tag stream, in the coach's own vocabulary, that
  later lands on the rally clips, is the thing no scoreboard app can do.
- **We already have the label system this needs.** `annotation_labels` is
  user-owned, seeded, colour-coded, unique per name, and
  `AnnotationLabelsRepository` already publishes them as an owner-scoped
  `StateFlow` cached to disk and readable cold and offline. A courtside quick-tag
  palette is a new consumer of a repository that already works with no signal in
  a sports hall.

---

## 3. What we have today

Stated so the verdicts below are grounded rather than guessed.

- **This repo**: Kotlin Multiplatform. `shared` holds models, repositories, the
  analyze coordinator, court geometry and pure logic; Android is Compose, iOS is
  SwiftUI. Dependencies: supabase-kt 3.5.0 `auth` / `postgrest` / `storage` /
  `functions`. **No `realtime-kt`, and no `realtime` reference anywhere in the
  mobile codebase.**
- **Backend**: Supabase shared with `badminton-tracker` (Vue 3 web app plus a
  Python/Modal pipeline: YOLO26 pose, TrackNet shuttle, homography from a
  12-point court calibration, shot-gap rally detection). Tables `videos`,
  `processing_logs`, `rally_clips`, `rally_annotations`, `annotation_labels`,
  `match_shares`.
- **The product loop**: record or import a match video, mark the court, analyze,
  get one clip per detected rally, add timestamped notes with user-owned colour
  labels, sort and summarise them per match, share a match with another
  account.

We are a video analysis and coaching-notes app. The competitor is a live
scorekeeper. There is no overlap today at all.

---

## 4. Capability-by-capability verdict

Every capability in the listing and on the site, with a verdict. "Build
reduced" means we ship the useful core and drop the part that only serves a
store listing.

§2 selects from this table rather than adding to it: the coach's flow needs
rows 1 to 8, is indifferent to 9 and 10, and needs none of 11 to 15. The table
is kept in full so the declines are on the record rather than silently skipped.

| # | Capability | Verdict | Cost | Why |
| --- | --- | --- | --- | --- |
| 1 | 21-point rally scoring rules (win by 2, cap at 30, best of 3, interval at 11, change of ends, serve court and serve rotation) | **Build** | S | Pure logic. Belongs in `shared/commonMain` as a fold over events, unit tested once for both platforms. This is the whole substance of the competitor and it is the cheapest thing on the list. |
| 2 | Score history / full match record | **Build** | S | It is the same event log, persisted. Free once #1 exists. |
| 3 | Point-by-point export as text | **Build** | XS | A `String` built from the log. |
| 4 | Undo / Reset score / Reset game | **Build** | XS | Drop the last event and re-fold. Correct by construction if #1 is a fold. |
| 5 | Net view (umpire orientation) | **Build** | XS | A layout flip of the same scoring surface. Cheap, and real umpires use it. |
| 6 | Announce score aloud | **Build reduced** | XS | Platform TTS reading the canonical call ("eighteen fifteen"). Genuinely useful when one person umpires. |
| 7 | Whistle / vuvuzela / coin toss | **Decline** (keep coin toss) | XS | Vuvuzela is listing filler. Coin toss is one line and settles who serves, so it can ride along with #1. |
| 8 | Do Not Disturb during matches | **Build reduced** | XS | We already hold the idle timer during uploads (`RootView.swift`). Extend that to an active match. An iOS app **cannot** enable Do Not Disturb itself; the real feature would be a Focus filter or a Shortcut the user configures. Ship keep-awake, do not claim DND. |
| 9 | Real-time sync across devices | **Build later (L3)** | M | Needs `realtime-kt` added, the session table in the `supabase_realtime` publication, and a conflict rule. Deferred to L3 below, because a single-device scorekeeper is already useful and this is where the scope grows. |
| 10 | Browser live scoreboard, public link, no sign-up | **Build later (L4)** | M | Not reuse of our share infrastructure: `match_shares` is `shared_with_user_id`, authenticated account to account. A public spectator link is a new anonymous read path plus a new page in the `badminton-tracker` repo, whose `src/views` currently holds only `LoginView.vue`. Cross-repo new surface. L4 below. |
| 11 | Tournament mode: brackets, seeding, QR invites, roles, multi-court overview | **Decline for now** | L | This is the part that makes their app a tournament product, and it is orthogonal to coaching video analysis: draws, seeding, byes, per-tournament roles, invitations, messaging. If the coach's real need is club nights, the 20% that buys 80% is a "session" that groups several matches on one date, which falls out of #2 almost for free. Full brackets should be a separate decision with its own design. |
| 12 | Apple Watch control | **Decline for v1** | M | Nobody has asked for it, including the competitor's own users: they advertise it in the subtitle and their single reviewer requested it anyway. A second scoring surface has to stay consistent with the engine forever, which is a permanent tax paid for a use we have no evidence of. Sizing (a watchOS target plus its own connectivity path) is context, not the reason. Revisit if the coach actually umpires from the wrist. |
| 13 | TV / external display via AirPlay or HDMI | **Defer** | M | A browser link (#10) already puts the score on any smart TV, laptop or club display, so a native scene would be a second way to do one thing. It earns its place only if venues that cannot open a browser turn out to be real. |
| 14 | Multi-sport (table tennis, tennis, pickleball, ...) | **Decline** | L | Off-strategy. Our entire pipeline - court calibration, shuttle tracking, rally detection - is badminton-specific. |
| 15 | Bluetooth peer-to-peer offline sync | **Decline** | L | Two custom transports for one engine. An offline-first local log that syncs when the network returns (which L1 gives us anyway) covers the actual failure case, which is a gym with bad wifi. |
| 16 | Ads and remove-ads IAP | **N/A** | - | Not our model. |

**Net:** rows 1-8 are a self-contained, small piece of work. Rows 9-10 are a
second, medium piece. Rows 11-15 are the parts I would not build, and I would
say so to the coach explicitly rather than quietly skipping them.

---

## 5. What this buys that the competitor cannot

Everything above treats the scoreboard as an end in itself. For us it is not.
We are the only one of the two apps that has the match on video, cut into
rallies, with the coach's notes on it. A score log is the missing axis on that
data:

- Every rally clip gains its situation: "18-15, serving, point lost".
- The coach's courtside tag lands on the clip of the rally it describes, so
  "forced error at 14-12" becomes a video he can open. He does the tagging once,
  live, in the two seconds after the point, instead of re-watching an hour of
  footage to find it again.
- The match label summary we shipped this week gains a score dimension: this
  player's unforced errors cluster between 15 and 20.
- Rally clips become filterable by situation: show me every point I lost when
  serving at game point.
- The score log is **ground truth for the rally detector**. Rally detection is a
  heuristic (see §6.3); a point count from a human is a check on it.

That is the reason to build a scoreboard at all, and it is what §7's layers are
arranged to reach.

---

## 6. Constraints that decide the design

These are verified against the code, not assumed. They are the reason this
document exists before a plan does.

### 6.1 Video time and wall-clock time do not meet

`rally_clips.start_timestamp` / `end_timestamp` are **seconds into the video**.
A live score log is **wall-clock**. Joining them on time would require the
instant the camera started rolling, and we do not have it. Under §2's flow we
could not use it anyway (the video comes off a different device), so this
section records why time-based binding is not merely deferred but unavailable:

- `public.videos` has `created_at`, `processing_started_at`, `completed_at`.
  Those are upload and pipeline times. No capture column exists in either
  migration directory: in `badminton-tracker` only `0004`, `0006` and `0008`
  touch the table, and in this repo only `20260825000000_video_description.sql`
  does.
- `LocalVideoEntry.addedAtEpochMs` is set at **intake** -
  `LocalVideoIntake.swift:31` and `VideoIntake.kt:133` both stamp "now" when the
  file arrives in the library, which is after recording finished and after the
  user confirmed the picker. Deriving capture start as
  `addedAtEpochMs - durationMs` is off by however long the user hesitated on
  the camera's review screen: seconds to minutes, unbounded.

Note also that `0002` revokes UPDATE on `public.videos` and re-grants it column
by column (`0007` does this for `pipeline_variant`, and `0008` documents the
consequence of forgetting). Any new column on `videos` that the client must
write after insert needs an explicit column grant, and a service-role smoke
test will not catch its absence.

### 6.2 One phone cannot score and record at once, and does not need to

Both platforms record through the **system camera**: `UIImagePickerController`
with `sourceType = .camera` (`CameraRecorder.swift`) and
`ActivityResultContracts.CaptureVideo` on Android. While that is on screen our
app is not. There is no way to put scoring controls in front of the user during
a recording, and no way to observe the moment recording starts.

The first draft of this document called that the linchpin, and proposed
replacing the system camera with an in-app capture pipeline
(`AVCaptureSession` on iOS, CameraX `VideoCapture` on Android) to recover an
exact capture instant. **§2 makes it unnecessary.** The coach films on one
device and scores on another, then uploads afterwards. Nothing in his flow asks
one phone to do both.

The constraint therefore stands with one remaining consequence, and it is the
one that shapes L2: **there is no shared clock between a score log and a video,
so binding must be ordinal.** The in-app recorder leaves scope. If it is ever
built it has to justify itself on its own merits, such as court framing guides
at record time, and not as a prerequisite for scoring.

### 6.3 Rally count is not point count

`backend/rally_detection.py` groups shuttle direction reversals into rallies
with `min_rally_duration_s = 0.8`, `min_gap_duration_s = 3.0`, a minimum shot
count, and it detects nothing at all where TrackNet has no shuttle. Service
faults, service errors and two-shot rallies fall under those floors; a long
pause mid-rally can split one rally in two.

So in a real game the detector will not produce exactly one clip per point, and
**ordinal binding (point N maps to rally N) drifts**. It must be designed to
drift visibly and be correctable, never to misalign silently.

Read the other way round, this is the strongest argument for the coach's flow:
a human-authored point sequence is **ground truth the detector has never had**.
Bound, it tells us exactly how many points the game contained, which the
detector can only guess at.

### 6.4 Realtime is not wired up in mobile

`gradle/libs.versions.toml` lists `auth-kt`, `postgrest-kt`, `storage-kt`,
`functions-kt` and no `realtime-kt`; grep finds no realtime reference in
`shared`, `androidApp` or `iosApp`. The web app does use realtime
(`0009_realtime_publication.sql` adds `videos` and `processing_logs` to the
publication, deliberately excluding `rally_clips`). Any live sync means a new
dependency here plus a publication change there, and that migration file's own
comments are the guide for how to write it idempotently.

### 6.5 Our sharing is authenticated only

`MatchShare` is `shared_with_user_id` plus an email, gated by RLS on
`auth.uid()`. A no-sign-up spectator link is a different mechanism: a
secret-token row read by an anonymous role, or an edge function. Do not plan it
as an extension of `match_shares`.

---

## 7. The design

L0 and L1 together are the coach's flow end to end. L2 is the payoff. L3 and L4
are the competitor's remaining tricks, kept here so a later "can it also do X"
has an answer, and not proposed.

### L0 - the scoring engine (`shared/commonMain`, pure)

The foundation for everything else. One event log, one fold, no mutable score.

```
ScoreEvent = PointTo(side) | Undo | StartGame | EndMatch | Interval | ChangeEnds
MatchState = fold(rules, events)
```

`MatchState` carries, derived and never stored: points per side, games won,
whose serve it is, which service court (right when the server's score is even,
left when odd), which player of a pair is serving and which is receiving,
whether the interval is due, whether ends change, game point, match point, and
whether the match is over.

**`rules` is a parameter, not an enum.** The coach says "15 or 21", and clubs
vary on what a 15-point game means (win by two or straight to 15, cap at 21 or
no cap, interval at 8 or none). So the fold takes `ScoringRules(target, winBy,
cap, intervalAt, gamesToWin)` with 21/2/30/11/2 as the BWF default and 15 as a
preset. A wrong guess about his club's 15-point variant then costs a parameter
default, not a redesign, which is what lets this be designed before he answers
(§9).

Why a fold: undo is "drop the last event and re-fold", which cannot drift from
the forward path; the same log is the history view, the text export, and the
thing that binds to video; and it is trivially unit-testable. Same shape as
`buildMatchLabelSummary` - one pure shared function both platforms call, so
Android and iOS cannot disagree about the score.

Tests are the deliverable here as much as the code: serve rotation in doubles,
setting at 20-20 through 29-29 to 30, change of ends at 11 in the third, the
15-point presets, undo across a game boundary.

**Cost: small. No backend, no UI, no migration.**

### L1 - create a match, score it, tag it

This is §2 steps 1 to 3, and it is shippable on its own with no video anywhere
near it.

**Create the match first.** Name, singles or doubles, player or pair names,
scoring rules, date. A match now exists with no video, which is the inversion
described in §7.1 below.

**The scoring surface.** Landscape, one large tap target per side, in our own
`ShuttlTheme` rather than a copy of the competitor's red/blue. Serving side and
service court shown, because the engine knows them. Net view is the same view
rotated. Undo, reset game, switch ends, score history, coin toss. Keep the
screen awake for the duration, reusing the idle-timer pattern already in
`RootView.swift`.

**Tagging while scoring.** The part that matters, and the part the competitor
has no equivalent of. Each point can carry tags and a free-text comment:

- The palette is `AnnotationLabelsRepository.labels` - the coach's own defined
  labels, already seeded with `Good shot` / `Forced error` / `Unforced error`,
  already cached to disk per owner and readable cold and offline. A sports hall
  with no signal is the normal case, and that repository already handles it.
- Tagging must not slow the scoring down. The interaction is: tap the side that
  won the point, and the tag row is right there for an optional second tap. One
  tap scores; two taps score and tag. Never a modal, never a required field.
- A tag is stored **against the point**, with the label name and colour
  snapshotted onto it, exactly as `RallyAnnotation` snapshots `label_name` and
  `label_color`. Same reason: renaming or deleting a label later must not
  rewrite or orphan history.
- A longer comment is available behind the same row for when there is time
  (between rallies, at the interval).

**Persistence.** Local first, in the same `multiplatform-settings` registry
pattern as `LocalVideoRepository`, so a match survives the app being killed
mid-game with no network. Sync to `score_logs` when signed in and connected.

**Output even with no video ever uploaded:** a full point-by-point record, the
tag tally for the match (the same aggregation shape as
`buildMatchLabelSummary`), and a text export.

**Cost: small to medium, and it is the bulk of what the coach asked for.**

### 7.1 The inversion: a match that has no video

Today a match *is* a `videos` row. `rally_clips.video_id`, `match_shares`,
match titles and `MatchGrouping` all key off it. §2 requires a match to exist
before any video does, so something has to give.

**Rejected - a `matches` table that `videos` points at.** Cleaner on paper, and
wrong here: `videos` rows are written by the Modal pipeline and by the web app's
upload path, both in the `badminton-tracker` repo, and `videos.title` is
insert-only by deliberate design (`0008` documents the absent UPDATE grant).
Option A means a coordinated schema change across two repos and a Python worker
before the coach can score a single point.

**Chosen - `score_logs` is the pre-existing entity, with a nullable
`video_id`.** One new table this app owns outright, one nullable column, no
change to how `videos` or `rally_clips` are written, nothing to coordinate with
the pipeline. Binding is an UPDATE of that one column.

The honest cost, and it is the largest single piece of UI work in the feature:
**the match list becomes a union of two entity kinds.** `MatchGrouping.matches`
(ported line for line between `MatchGrouping.swift` and `ClipListViewModel`)
currently folds `[RallyClip]` into matches. It has to accept score-only rows
alongside video-backed ones, on both platforms, with the sort order, the cover
image, the swipe-to-remove behaviour and the shared/owned partition all still
correct. Budget for that explicitly rather than discovering it.

Lifecycle states, so L2 has something to attach to:

| State | Meaning |
| --- | --- |
| `live` | Being scored right now. Local only until it ends. |
| `unbound` | Finished, no video attached. A perfectly good terminal state. |
| `bound` | Mapped onto a video's `rally_clips`. |
| `reconciled` | A human has confirmed or corrected the mapping. |

**A bound match has two names, and the score log's wins.** The match is created
and named before any video exists, so its name is on the `score_logs` row. But
`videos.title` is insert-only by deliberate design (`0008` documents the absent
UPDATE grant), Modal stamps it onto every clip, and `matchTitle(of:)` names the
match in the list from the most common clip title. Left alone, a coach who
creates "Thu League vs Marco" and then uploads `marco_rematch.mp4` gets his
match renamed, or sees one name on the match page and another on the rally rows.
So: the score log's name is the match's name whenever it has one, and
`videos.title` is the fallback for video-first matches only. The corollary
matters because it cannot be fixed afterwards - **the "Add video" flow must
pre-fill the video title from the score log**, so the two agree at insert time.

**Removal needs two new answers**, and they land in the swipe gesture users
already have (`delete_match` / `leave_shared_match`, both keyed on the video):

- *Score-only match.* No video, so `delete_match` does not apply. It needs its
  own removal path behind the same gesture.
- *Bound match whose video is deleted.* The score log must **survive as
  `unbound`, keeping its tags.** The obvious implementation is the wrong one:
  the tags were denormalized into `rally_annotations`, which `delete_match`
  cascades away, so a naive delete silently destroys work the coach did
  courtside before that video ever existed. `score_logs` stays the source of
  truth precisely so this is recoverable, and re-binding a new upload then
  works.

**A score-only match cannot be shared.** `match_shares` is keyed
`(video_id, shared_with_user_id)`, so sharing only becomes available once a
video is attached. Acceptable, but it should be an explicit disabled state with
a reason rather than a missing button.

### L2 - upload the video onto the match

§2 step 4. The match already exists, holds a scored, tagged point sequence, and
now gets a video.

**The flow.** From the match, "Add video": pick or import the recording, mark
the court, analyze. The existing `AnalyzeCoordinator` runs unchanged - it stays
a pipeline over one video and grows no dependency on scoring. The only new thing
is that `score_logs.video_id` is set at the start and the match page offers
**Reconcile** once clips exist.

**Binding is ordinal.** Point *N* maps to `rally_index` *N*. Exact in principle,
since one rally is one point in badminton, and wrong in practice wherever the
detector dropped or split a rally (§6.3). There is no clock to fall back on
(§6.1, §6.2), so the reconcile screen is not a nicety - it is the mechanism.

**The reconcile screen** shows the two sequences side by side, the coach's point
log against the detected rallies, with drift visible:

- **It opens on one question: which detected rally is the first point?** The
  default offset is not zero. Warm-up hitting before the first serve produces
  detected rallies carrying no points, at the front of the sequence, which is the
  worst possible place for a drift to start. Answering that one question first
  flips ordinal binding from usually wrong to usually right, and everything after
  it is a correction rather than a rebuild.
- Then: nudge the mapping by plus or minus one from any row onward (the way
  subtitle offsets work), and skip a detected rally that was not a point.
- Confidence is stated, not implied: bound *k* of *n* points.

**What binding writes.** Two things, and both go where the readers already look
rather than into new surfaces the rest of the app would have to learn:

1. **The coach's live tags become `rally_annotations` rows** on the matched clip.
   They then appear in clip detail, in `buildMatchLabelSummary`, and to share
   recipients, with zero new read paths. `clip_id` and `timestamp_seconds` are
   both NOT NULL, which is exactly why the tags cannot be written as annotations
   before binding and need `score_logs` to hold them until then. Timestamp: the
   clip's start. That is arbitrary but consistent, and the reconcile screen lets
   the coach drag it; do not over-derive it from tag semantics ("forced error" is
   the last shot, "serve mistake" is the first). Note `annotation_count` is
   maintained by the security-definer `bump_annotation_count` trigger, so a bulk
   insert at bind time fires it once per row and the counts stay right for free.
   Re-binding is delete-and-reinsert of the rows this binding created.
2. **The score situation is denormalized onto the clip:** `score_at_start`,
   `serving_side`, `game_index`, `point_won_by`. Same precedent as the
   `RallyAnnotation` label snapshot - write the resolved thing where the readers
   already are. A `match_shares` recipient then needs no new policy at all (they
   already read `rally_clips`), the L2 filters become a plain query instead of a
   client-side join under a second policy, and `score_logs` stays owner-only,
   which is the simple policy. The cost is that a snapshot can go stale against
   an edited log, exactly as a renamed label does not rewrite old annotations.
   That trade is already the house rule; re-binding is the correction.

`rally_clips` is written by the Modal pipeline, so these columns need their own
grant pass, in the same revoke-then-grant-by-column style as `0004` and `0008`.

**What the coach gets.** Every rally clip carries its situation and his own
courtside tag. Filter a match by situation: every point lost when serving above
15, every unforced error in the third game. The label summary gains a score
axis. And the point count is ground truth against which the detector can be
measured (§6.3).

**Cost: medium. One migration, one reconcile screen, no new pipeline work.**

### L3 - second device live sync

Add `realtime-kt`, put `score_logs` in `supabase_realtime`, subscribe. One
device holds the scorekeeper role, others follow; the conflict rule is
single-writer with explicit handover, not last-write-wins, because two people
tapping a shared score is exactly how it goes wrong. L0's append-only event log
is already the right wire format: a reconnecting device replays rather than
reconciles.

**Not proposed.** Recorded because the competitor has it and someone will ask.

### L4 - public spectator link

A `score_logs` row with a secret token, an anonymous read path (RLS on the token
or an edge function), and a page in the `badminton-tracker` repo at
`/live/<token>` reusing L0's fold so the browser computes the same state from
the same events. Needs an expiry, a revoke, and to expose nothing but names and
scores.

**Not proposed.** Same reason as L3.

## 8. Recommendation

1. **Build L0 + L1.** That is the coach's steps 1 to 3 end to end: create a
   match, score it to 15 or 21, tag each point from the labels he has already
   defined. Entirely ours to control, no pipeline work, and useful on the first
   club night even if no video is ever uploaded.
2. **Then L2.** Step 4, plus the reconcile screen that ordinal binding requires.
   This is the reason to build a scoreboard at all, and it is the capability the
   competitor structurally cannot copy: they do not have the video.
3. **Budget the match-list union explicitly** (§7.1). It is the largest single
   piece of UI work here, it lands on both platforms, and it is easy to
   under-count because it looks like a list change.
4. **Do not build** the in-app recorder. The first draft of this document called
   it essential; §2 removed the need. If it comes back it comes back on its own
   merits.
5. **Hold L3 and L4.** Recorded, not proposed.
6. **Decline** tournaments, Watch, multi-sport and Bluetooth P2P, and say so to
   the coach with the reason: they are the parts that make that app a general
   scoreboard, and copying them makes us the eleventh one.

## 9. Open questions, stated rather than guessed

§2 answered the big one. Four smaller ones remain, none of them blocking,
because the design absorbs any answer:

1. **What are his 15-point rules?** Win by two or straight to 15? Cap? Interval
   at 8? A wrong default costs a `ScoringRules` parameter, not a redesign (L0).
2. **Is a tag attached to a point, or free-floating in the game?** L1 assumes
   the former, since it is what makes binding to a rally clip possible at all. A
   tag for something between rallies (a coaching observation, a line call) would
   need somewhere else to go.
3. **Who scores?** If it is always the coach on his own phone, L1 is complete as
   designed. If an assistant scores while the coach watches, that is L3, and it
   moves from "recorded" to "proposed".
4. **Does he want a match with no video to look like a match?** §7.1 puts
   score-only matches in the same list as video-backed ones. The alternative -
   a separate list until a video arrives - is a smaller change and a worse
   product. Worth confirming before the list work starts, because it is the
   expensive part.

## 10. What this assessment does not cover

- No cost or timeline estimates beyond the S/M/L sizing above.
- The competitor's actual behaviour was not tested; it was not installed. The
  review rests on the store listing, the developer's site, and the six
  marketing screenshots, which §1.3 shows are stale in at least two respects.
- No decision on where match creation and scoring sit in the app's navigation,
  which is a real question once a match can exist without a video.
- No design for the scoring surface itself beyond its behaviour: the courtside
  layout has to be readable at arm's length on a bench, and that is a real
  design pass, not a layout detail.
- The tag interaction during live scoring is specified as a rule ("one tap
  scores, two taps score and tag, never a modal") but not drawn. It is the
  highest-risk piece of UX in the feature: if it costs the coach the next rally,
  he will stop using it.
- The `score_logs` schema and the denormalized `rally_clips` columns above are
  sketches. Column-level grants on `public.videos` (§6.1) and the realtime
  publication (§6.4) are the two places a migration will bite; note also that
  `rally_clips` is written by the Modal pipeline, so adding columns the client
  writes at bind time needs its own grant pass. All of it needs a design of its
  own before a plan is written.
