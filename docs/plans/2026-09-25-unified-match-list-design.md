# Design: one match list, with where each match lives and what it has

**Date:** 2026-09-25
**Status:** Implemented on `feat/unified-match-list` (2026-09-25). Section 5 lists where the shipped version differs from this proposal.
**Asked for:** "In the matches left panel, the matches have been divided by 'on the
phone' and 'my matches'. I think you can somehow unify them together, but display
some extra information such as whether the video is in the cloud or locally,
whether the video has been analysed and what has been analysed (heatmap,
skeleton, etc)."
**Visual mock:** published alongside this doc (light and dark, at the real 330dp
drawer width).
**Touches:** `2026-09-11-on-device-rallies-as-matches-design.md` (unshipped; this
gives it the row it needs), `2026-09-03-home-and-analytics-redesign-design.md`
(the drawer and the Analytics picker it specified).

The drawer sorts matches by *where the file is*. A coach thinks in matches. The
split also makes one match appear twice today, and hides what analysis a match
has behind a row menu. This proposes one list, one row per match, with a
compact status line that says where the match lives and what it has.

---

## 1. What exists today

Verified by reading the code. File references are the evidence.

### 1.1 Two sections built from two sources

- `LocalVideoSection.kt:80` renders "On this phone" from local entries, as the
  drawer's one raised card per row, with a full-width Analyze button.
- `ClipListScreen.kt:215` renders "My matches" from `state.ownedRows`: cloud
  video matches and courtside-scored matches, merged by `mergeMatchRows`
  (`MatchRow.kt`). Rows are flat, with no thumbnail.
- `ClipListScreen.kt:117-118` drops local entries bound to a scored match
  (`scoreLogId != null`), because the scored row already represents them.
  iOS does the same at `ClipListView.swift:179`.
- "Shared with me" is a third section, read-only for this user.

### 1.2 One match can appear in both sections (code-read, not reproduced)

- A local entry's id becomes the cloud `videos.id` when it is analysed
  (`LocalVideoEntry.kt:149`).
- Since `f96d78c` a cloud run keeps the local file and leaves the entry at
  `ANALYZED`, so the skeleton has footage to draw over.
- The standalone filter above checks only `scoreLogId`. Nothing checks whether
  the entry's id is also an owned cloud match. So a standalone video analysed
  in the cloud is listed twice: an "Analyzed" card under "On this phone" and a
  rally row under "My matches". Neither platform removes the duplicate.
- **Not reproduced on hardware:** the attached phone has no local entries right
  now (drawer screenshot in the mock), and the emulator cannot take a reinstall.
  This rests on the code path above.

### 1.3 What each row can know without a network call per row

| Fact | Source | Cost |
| --- | --- | --- |
| File on this phone | local entry exists | in memory |
| In the cloud | owned clips for `videoId`, or entry stage past `CREATE_ROW` | in memory (clip cache) |
| Rallies, cloud | clip count per `videoId` | in memory |
| Rallies, on device | `PlayerTrackStore.loadClips` count | disk, already computed as `clipCounts` (`AuthGate.kt:329`) |
| Heatmap (track) | `storedTrackIds` | disk header, already a set |
| Skeleton | `SkeletonStore` header check (`SkeletonStore.kt:160`) | disk header |
| Scored points | score log | in memory |
| Cloud pose artifact not yet downloaded | `CloudPoseRepository.artifact` | **network, one row per call** |

The last row matters. `list_match_metadata` (`MatchMetadata.kt`) carries title,
description, players and format, but nothing about Phase 2. Knowing "the cloud
has a heatmap for this match you have not opened yet" needs one extra column on
that RPC. Without it the row can only show what is already on the phone.

### 1.4 The same split is on the Analytics picker

`AnalyticsScreen.kt:47-48` groups by `LOCAL_VIDEOS` / `OWNED_MATCHES`, and
`AnalyticsRowState.kt` already encodes the honesty rules this design needs:
skeleton only when the footage is here (`READY` vs `READY_NO_VIDEO`), no
Analyze on a match with no local file (`NOT_ON_DEVICE`).

### 1.5 Width budget

Drawer 330dp, side margin 24dp, row padding 16dp, thumbnail 64dp plus a 12dp
gap, overflow menu about 36dp. The text column is about 118dp, as the comment
in `LocalVideoSection.kt` already says. "On phone · Cloud · Rallies · Heatmap
· Skeleton" in words does not fit in 118dp. The status line has to be glyphs.

---

## 2. Recommendation

### 2.1 One list, one row per match

- The drawer shows **Matches**, one list, newest first. The "On this phone"
  and "My matches" labels go.
- **Shared with me** stays its own section below. The boss did not ask about
  it, it has a different owner and a different swipe ("Remove" means leave the
  share), and mixing it in would put rows the coach cannot analyse or delete
  among rows they can.
- A filter row sits above the list: **All · On this phone · Not analyzed**.
  "What is taking space on this phone" and "what still needs analysing" were
  the two jobs the old split did well. The filter keeps both one tap away
  without making them the list's structure. Hidden while the list has fewer
  than six matches, where it would only add chrome.

### 2.2 Joining the sources

A match is keyed by the first id it had. Cases:

| Case | Key | Sort time | Thumbnail |
| --- | --- | --- | --- |
| Local file only | entry id | `addedAt` | local frame |
| Local file, analysed in cloud | entry id (= `videos.id`) | `addedAt` | local frame |
| Scored, no video | score log id | log `createdAt` | score tile ("2-1") |
| Scored, video attached (local and/or cloud) | score log id | log `createdAt` | video frame |
| Cloud only (other phone, or file removed) | `videoId` | earliest clip `createdAt` | cover clip |
| Track kept, file removed, no cloud | entry id | `addedAt` | score tile or placeholder |

The existing rule holds: a row never jumps when its clips arrive, because it
sorts on when the match began, not on its newest artifact. One change: a
cloud-only row sorts on its *earliest* clip, not its latest
(`ClipListViewModel.kt:113` uses `latestCreatedAt`), so re-cutting a match
does not move it to the top.

### 2.3 The row

```
┌────────┐ Kunlavut vs Viktor          ⋮
│ thumb  │ 3-1 · 12 rallies · Sep 8
└──[▣☁]──┘ ▣☁  ▦ ◉ ⌖ ⁂
```

- **Line 1:** match name.
- **Line 2:** score (if scored), rally count (if cut), date. Words, because
  they are numbers.
- **Line 3, the status line:** one *where* glyph, a thin divider, then one
  glyph per analysis the match has.
  - **Where:** phone, cloud, or phone+cloud. Always shown.
  - **What:** Rallies, Heatmap, Skeleton, Score. Only what exists is drawn,
    in the accent colour. Nothing greyed out: four grey glyphs on every
    unanalyzed row is noise, and "Not analyzed" in words says it better.
  - Skeleton is drawn only when the file is on this phone (the `READY` rule).
    A kept track with the file gone shows Heatmap, not Skeleton.
- **Accessibility:** the status line is one content description: "On this
  phone and in the cloud. Rallies, heatmap, skeleton."
- **Legend:** the filter row's info button opens a small sheet naming the
  glyphs. The match page names them in words, so the glyphs are a scan aid,
  not the only place the information lives.

### 2.4 Rows that need something from the coach

The drawer keeps exactly one raised surface, but it moves from "on this phone"
to "needs an action":

- Not analyzed (local file, nothing run): raised card, status line reads
  "Not analyzed", full-width **Analyze** button, as today.
- Analysing: raised card, status reads "Analyzing 42%…" (shared
  `cloudAnalysisStatus` / `deviceWorkLabel`), spinner. Glyphs of anything the
  match already has stay visible.
- Failed: raised card, the failure line, **Retry**.
- Everything else: the quiet row.

### 2.5 Swipe and menu

The two sections disagree today: local swipe is "Remove" (frees the file,
keeps the tracks), match swipe is "Delete" (removes the cloud row and clips).
A merged row can have both.

- Swipe says **Delete**. When the row has only one thing to delete it opens
  today's single confirmation, with today's wording.
- When the row has both a local file and a cloud match, swipe opens a two-choice
  sheet: **Remove video from this phone** (keeps the cloud match and the
  analysis) and **Delete match everywhere**. The first is listed first, since
  it is the one that cannot lose data.
- The overflow menu keeps Edit details, Remove video from this phone, Delete
  match. "Clips on this phone (N)" and "Player heatmap" leave the menu: the
  row itself now says they exist, and the match page opens them.

### 2.6 Where a tap goes

Unchanged per case: a cloud or scored match opens the match page, a local-only
file opens the local player. When `2026-09-11-on-device-rallies-as-matches`
ships, a device run's rallies open on the match page too, and the local-only
case disappears for analysed files.

### 2.7 One shared row model

The row state lives in `shared/` next to `analyticsRowState`, as a function
from (entry?, score log?, cloud match?, stored track?, skeleton?, device clip
count, liveness) to a `MatchListItem` with `where`, `has`, `action` and `sortAt`.
The drawer and the Analytics picker both draw from it, so the two lists cannot
disagree about a match, and Android and iOS cannot drift.

**Analytics picker in the same change.** Its row gets the same status line and
the same order. Its `READY / READY_NO_VIDEO / ANALYSABLE / NOT_ON_DEVICE` state
becomes a projection of `MatchListItem`, so it is no longer computed separately.

---

## 3. Decisions

| Question | Chosen | Rejected, and why |
| --- | --- | --- |
| Structure | One list plus a filter | Keep sections and add badges: leaves the duplicate and the "where is my match" hunt. Tabs (Phone / Cloud): the same split, one tap further away. |
| Status encoding | Glyphs on one line | Words ("Heatmap · Skeleton"): does not fit 118dp. Colour dots like Analytics: a dot says "something", not which thing. Thumbnail corner badge for *where*: too small at 64x40 to read phone vs cloud, and it hides on score tiles. |
| Absent analyses | Not drawn | Greyed glyphs: noise on every unanalyzed row; "Not analyzed" in words is clearer. |
| Shared matches | Separate section | Merged: different owner and swipe meaning. |
| Raised card | Rows needing an action | Rows on this phone: in one list the card would mark storage, which is now the status line's job. |
| Cloud Phase 2 visibility | Add a `has_pose` column to `list_match_metadata` (phase 2 of rollout) | Per-row `CloudPoseRepository.artifact`: a network read per row on every drawer open. |

## 4. Rollout

1. **Shared model and the drawer, both platforms.** Fixes the duplicate as a
   side effect. Glyphs cover what is already on the phone.
2. **Analytics picker** on the same model.
3. **`has_pose` on `list_match_metadata`.** A cloud heatmap or skeleton not yet
   downloaded draws as an outlined glyph ("in the cloud, downloads when
   opened"). Migration committed, applied by the owner.
4. The on-device-rallies-as-matches plan lands into this row rather than
   adding a menu item.

## 5. What shipped differently

Decided during implementation (`docs/plans/2026-09-25-unified-match-list-plan.md`,
"Changes from the spec", plus review rulings):

- **No outlined "in the cloud" glyph and no `has_pose` column (rollout step 3
  dropped).** A cloud pose artifact is only downloaded as part of a cloud run
  started on the same phone (`RallyAndroidApp.kt`, `CloudPoseCoordinator.install`).
  Nothing would download it for a match analysed elsewhere, so the glyph would
  promise something the app cannot do. It needs a download path first.
- **"Clips on this phone (N)" and "Player heatmap" stay in the row menu** until
  the on-device rallies have a match page (the 2026-09-11 plan).
- **Where is one or two bare glyphs** (phone, cloud) rather than a combined
  glyph, which fits the measured text column (Android ~138dp, iOS 130pt).
- **The description line left drawer rows**; the players line stays.
- **Deleting a match that is also on this phone discards the local file and
  its analysis too**, only after the server delete succeeds, and the
  confirmation says so. Otherwise the surviving entry would bring the row back
  as phone-only.
- **Joining rules tightened in review:** a score log's own `video_id` claims
  its video first; one video id per row; an entry attached to a match that has
  not loaded yet stays hidden (cold start).
- **Colour:** the analysis chips use the darker green in light mode (the accent
  was 2.4:1 on its chip); Android menus and dialogs now draw on the app's own
  raised surface instead of Material's default lavender (pre-existing, app-wide).
- **Analytics:** the per-row "Not on this phone" line is gone; the status line
  carries where the video is.
- **US spelling** ("Not analyzed") as in the rest of the app.

## 6. Deliberately not in this pass

- Storage size per row. Useful for "what is taking space", but a second number
  on line 2 crowds the rally count. The "On this phone" filter can carry a
  total instead, later.
- Merging Shared with me.
- Bulk actions (select several, remove from phone).

## 7. Verification

- Unit tests on the shared `MatchListItem` builder, one per row of the table
  in 2.2, plus the duplicate case from 1.2 as a regression test.
- On the phone: a local-only video, one analysed on device, one analysed in the
  cloud, a scored match with attached video, a scored match without, and a
  cloud match whose file was removed. Screenshot the drawer in light and dark;
  `bgTertiary` is close to other light-theme fills, so check the raised card
  still separates.
- Reproduce 1.2 on the phone before the change, to confirm the duplicate the
  change is claimed to fix.
