# Design: an on-device run produces a match, not a clip dump

**Date:** 2026-09-11
**Status:** Proposal, pending approval
**Follows:** `2026-08-31-on-device-analysis-pipeline-design.md` (this reopens one
line from its §8 "Deliberately not in this pass"),
`2026-09-09-ios-on-device-analysis-design.md`

An on-device run already does the expensive part. It detects the rallies, cuts
them frame-accurately, and leaves one mp4 per rally on the phone. What it does
not do is give the coach anywhere to use them.

What a cloud match offers - a match row in the drawer, a rally list with
durations and note counts, a rally page with the transport bar and the label
picker, a label summary strip over the match - a device run offers none of. Its
rallies live behind a drawer menu item, on a screen whose own doc comment says
it exists "so the two pipelines can be judged" (`LocalClipsView.swift:33-40`).
It is a developer's comparison surface, and it has been the product surface by
default since device runs started producing clips people actually watch.

This document makes a finished device run produce a match: same drawer section,
same rally list, same rally page, same labels, same summary. It stays entirely
on the phone. Nothing is uploaded.

---

## 1. What exists today

Verified by reading the code, not assumed. File references are the evidence.

### 1.1 The clips a device run cuts, and the screen that shows them

- `LocalAnalysisRunner.kt:250-251` cuts the rallies (`ClipCutter().cut`) and
  writes a sidecar (`tracks.saveClips`). iOS is the same two lines at
  `LocalAnalysisRunner.swift:450-455`.
- `PlayerTrackStore.kt:98` writes `local-clips/<entryId>/clips.index`: one line
  per clip holding `index, startSeconds, endSeconds, filename`
  (`PlayerTrackStore.kt:159` for the path). `loadClips` at
  `PlayerTrackStore.kt:114`. iOS mirrors it at `PlayerTrackStore.swift:109`
  and `:119`.
- `PlayerTrackStore.kt:148` `scanClips` recovers clips by filename when the
  sidecar is missing, with `startSeconds = 0.0, endSeconds = 0.0`, because the
  bounds are not recoverable from the file. Runs made before the sidecar
  existed are in this state.
- `AuthGate.kt:560` is the whole Android clips screen: a `Column` of
  `TextButton`s whose label is `"Rally N  1.2s - 8.4s  (7.2s)"`, opening
  `LocalClipPlayerDialog`. `LocalClipsView.swift` is its iOS port.
- `AuthGate.kt:233` counts clips per row for the drawer menu item
  (`storedClips(it).size`).
- `ClipCutter.kt:33` records that clips carry no audio. Unchanged by this
  design, and still a real gap for reviewing a rally.

### 1.2 What a cloud match gets that a device run does not

- `ClipListScreen.kt:214` renders "My matches" from `state.ownedRows`;
  `ClipListViewModel.kt:75` `toMatches` groups clips by `videoId` into
  `MatchSummary`, one row per match.
- `RalliesFacet.kt:36` `ralliesFacet` draws the summary strip, the description
  and one `ClipRow` per clip. `ClipListScreen.kt:488` `ClipRow` shows a
  thumbnail, the rally title and `"<duration>s · <n> notes"`.
- `ClipDetailScreen.kt` plus `ClipDetailViewModel.kt` is the rally page: video
  card, transport bar, notes with labels, per-clip rename.
- `MatchLabelSummary.kt:107` `buildMatchLabelSummary(clips, annotations)` rolls
  a match's labelled notes into the strip. It takes `RallyClip` and
  `RallyAnnotation` concretely.
- Every one of these is driven by four narrow interfaces:
  `ClipsRepository` (`ClipsRepository.kt:13`), `AnnotationsRepository`
  (`AnnotationsRepository.kt:11`), `MediaRepository` (`MediaRepository.kt:8`)
  and `AnnotationLabelsRepository`. All four are constructed in one place,
  `RallyApp.kt:63-66`, which both platforms share.

### 1.3 The local half is further along than it looks

Three facts decide this design, and none of them is an accident.

- **The rally page's components already run on local notes.**
  `LocalPlayerScreen.kt:48-55` imports `AddAnnotationSheet`, `AnnotationRow`,
  `NotesHeader`, `PlaybackErrorOverlay`, `PlaybackSettingsSheet`, `TransportBar`
  and `VideoCard` from `clipdetail` and drives them from
  `LocalAnnotationsRepository`. A local rally page is not a second player to
  build.
- **`planReanchor` exists with no production caller.**
  `ClipReanchoring.kt:56`, in `shared/.../local/`, with a full test suite
  (`ClipReanchoringTest.kt`) and a doc comment describing "the one place local
  deliberately does something the cloud does not": moving a note when
  re-analysis shifts a clip boundary. It was written for this feature.
- **A local entry's id is its future `videos.id`.**
  `LocalVideoEntry.kt:140`: "client UUID; becomes videos.id on Analyze". So a
  device match and the cloud match of the same video share an identity rather
  than colliding under two.

### 1.4 What the prior design deliberately left out

`2026-08-31-on-device-analysis-pipeline-design.md:753` lists "Local-first
storage. Clips, annotations and sharing stay on Supabase" as out of scope, and
its §5.5 instead has the phone upsert `rally_clips` rows so "Supabase looks as
though the cloud had done it".

This document reopens that line and takes the other branch. The reason is the
one in §1.3: the seams for local-first rallies were cut while §5.5 was never
built, and the request is for rallies that work on a phone, not for a cheaper
way to reach the cloud. §5.5 is not superseded - it remains the design for
anyone who wants a device run to become a shareable cloud match - it is simply
not what this builds.

---

## 2. The fact the design turns on

The cloud UI does not depend on Supabase. It depends on four interfaces and two
data classes.

`RallyClip` (`RallyClip.kt`) is a flat record: an id, a video id, an owner, a
rally index, bounds, a duration, a storage path, an optional thumbnail path, an
optional title, a note count, a created-at. Nothing in it is server-shaped
except by convention - `clipStoragePath` is a string, and the only code that
treats it as a bucket key is `MediaRepositoryImpl` (`MediaRepository.kt:16`).
`RallyAnnotation` is the same story.

So the cheapest route to "exactly how it looks on the cloud" is not to rebuild
the screens against a local model. It is to have the local side **produce the
same two data classes** and put them behind the same four interfaces. The
screens then cannot look different, because they are the same screens reading
the same types, on both platforms, with no view code touched.

---

## 3. Decisions

| # | Decision | Rejected | Why |
|---|---|---|---|
| 3.1 | Local runs synthesize `RallyClip`/`RallyAnnotation`; composite repositories merge cloud and local | Parallel local list + detail screens | Doubles the surface on two platforms. The drawer's status lines already drifted once when written per screen (`LocalVideoEntry.kt`'s `cloudAnalysisStatus` comment). |
| 3.2 | Same, via composites | Generalize the UI over a `RallyLike` interface | Touches every screen on both platforms, and breaks the invariant `RalliesFacet.kt:46-52` documents: summary and clips derive from one cache, so they cannot disagree. A composite keeps that for free. |
| 3.3 | A finished device run is promoted into "My matches" | Keep it under "On this phone" | One place to look for a match, whoever analysed it. Costs the row's menu a new home, see §6. |
| 3.4 | Notes live in video time when they belong to no rally, clip time when they do | A one-way migration of notes into clips | The rule makes migration and re-analysis the same idempotent, lossless operation. See §5. |
| 3.5 | Clip id is `local:<entryId>:<index>` | A stored UUID per clip | The id must be derivable from the sidecar, which pre-sidecar runs do not have. Derived ids survive `scanClips` recovery; stored ones would not. |
| 3.6 | `ownerId` is stamped at read time from `auth.currentUserId()` | Persisted in the sidecar | `ClipDetailViewModel.kt:103` computes `isOwner` by comparing them. A persisted value goes stale on a session restore and silently removes the Add-note button. |
| 3.7 | Cloud clips win when both exist for one videoId | Block cloud Analyze once a device run exists | Removing a capability to avoid a conflict is worse than the conflict. Consequence stated in §7.3. |
| 3.8 | No clip thumbnails are generated | A JPEG per clip at cut time | Unnecessary. Android registers `VideoFrameDecoder` globally (`RallyAndroidApp.kt:32`), so `AsyncImage` decodes a frame from the clip file itself, exactly as the local video row already does (`LocalVideoSection.kt:144`). iOS rally rows draw no thumbnail at all (`RalliesFacet.swift:39-52`). |

---

## 4. The local clip layer

### 4.1 `LocalClipSource`, the one platform seam

`PlayerTrackStore` is per-platform and speaks `File` / `URL`, so shared cannot
read the sidecar. A narrow port, in `shared/.../local/`:

```kotlin
/** One cut rally as it exists on this phone. */
data class LocalClipFile(
    val index: Int,
    /** Bounds within the source video. Both 0.0 when unknown - a run recovered
     *  by filename has no sidecar to read them from. */
    val startSeconds: Double,
    val endSeconds: Double,
    /** The clip's own length, always known: probed from the file when the
     *  bounds are not. */
    val durationSeconds: Double,
    /** A file:// URL. Handed to the player and to the thumbnail decoder as-is. */
    val url: String,
)

interface LocalClipSource {
    /** What is on disk for this entry, now. Called off the main thread. */
    fun clips(entryId: String): List<LocalClipFile>
}
```

`durationSeconds` is separate from the bounds on purpose. When the sidecar is
missing, the bounds are genuinely unrecoverable, but the clip's own length is
one cheap probe away (`MediaMetadataRetriever` on Android,
`AVURLAsset.load(.duration)` on iOS). Without it every recovered rally reads
`"0.0s · 0 notes"`, and those runs are the ones already on the phone - the
first thing anyone opens. Probe results are cached in memory by the repository;
nothing is written back, because writing a probed duration into the bounds
columns would claim a position in the source video that is not known.

Android implements this over `PlayerTrackStore.loadClips` (`:114`), iOS over
`PlayerTrackStore.loadClips` (`swift:119`). Both already exist; the
implementation is a mapping plus the probe.

### 4.2 `LocalClipsRepository`

In shared. Folds three sources into `List<RallyClip>`:

| `RallyClip` field | Source |
|---|---|
| `id` | `"local:<entryId>:<index>"` |
| `videoId` | the entry id (which is the future `videos.id`, §1.3) |
| `ownerId` | `auth.currentUserId()`, at read time (3.6) |
| `rallyIndex` | `LocalClipFile.index`, already 1-based (`ClipCutter.kt:43`) |
| `startTimestamp` / `endTimestamp` | the clip's bounds in the source video |
| `durationSeconds` | `LocalClipFile.durationSeconds` |
| `clipStoragePath` | the `file://` URL |
| `thumbnailStoragePath` | the same `file://` URL (3.8) |
| `title` | the entry's title, stamped on every clip, or a per-clip rename |
| `annotationCount` | count from `LocalAnnotationsRepository` |
| `createdAt` | the entry's `addedAtEpochMs` |

Stamping the entry title onto every clip is the same trick the web app uses;
`ClipListViewModel.kt:60` `matchTitle()` takes the most common non-null title,
and `clipRowTitle` (`:72`) falls back to "Rally #N" when a clip's title equals
the match's, so the rally rows read correctly with no special case.

`annotationCount` must be a live `combine` over
`LocalAnnotationsRepository.byVideoId` (`LocalAnnotationsRepository.kt:25`,
already a `StateFlow`), not a value captured once. `ClipRow` renders it and
`sortClips(MostNotes)` (`RalliesFacet.kt:23`) sorts on it; a snapshot passes a
unit test and shows a stale count in the app.

The clip file list is read per entry and cached in a `MutableStateFlow`,
invalidated when a device run completes and on the entries the app already
probes at start (`AuthGate.kt:233` does exactly this read today).

### 4.3 Per-clip titles

`ClipsRepository.updateTitle` is the per-clip rename, and the cloud rally page
offers it. Local clips get a small `Settings`-backed map from clip id to title,
in the same shape as the notes store. Without it the rename affordance would
have to be gated off for local matches, which is a visible parity gap for two
dozen lines of code.

---

## 5. Notes

### 5.1 One rule

**A note lives in video time when it belongs to no rally, and in clip time when
it belongs to one.**

That single rule collapses three problems into one operation:

- *Notes made before analysis.* `LocalPlayerScreen` already writes notes against
  the whole video (`LocalAnnotationsRepository`, keyed by entry id, timestamps
  in video time). A note inside a cut rally moves onto that rally.
- *Notes made on a rally.* Stored against the clip id, timestamps relative to
  clip start - the same convention `ClipReanchoring.kt` documents and the same
  one the cloud uses.
- *Re-analysis.* New weights produce different boundaries and possibly a
  different rally count.

All three are the same function: **normalize every note for the entry back to
video time, then re-partition against the current clip windows.** It is a pure
function of (notes in video time, windows), so it is idempotent - running it
twice changes nothing - and lossless, because a note in clip time carries its
clip's start and can always be mapped back. No migration marker, no one-way
rewrite of the notes blob, which is the only artifact in this design that holds
work the coach cannot reproduce.

Entries whose clips have unknown bounds (§4.1) are skipped rather than guessed
at: without a start there is no mapping, and inventing one would relocate a
coach's note into a rally it was not about.

### 5.2 Where `planReanchor` fits, and what it does not do

`planReanchor` is per-clip: it takes one old start, one new start, one new
duration and that clip's notes. It assumes the caller has already decided which
new clip an old one *is*. Rally count can change between runs, so the index is
not identity.

So there are two pieces, both in shared and both unit-tested:

1. **`matchClipWindows(old, new)`** - pairs old windows to new by greatest
   temporal overlap, requiring a minimum overlap fraction so two unrelated
   rallies are never matched. Unmatched old windows return no pair.
2. **`planReanchor`** - unchanged, called per matched pair, for the boundary
   shift.

Notes belonging to an old rally with no match fall back to video time rather
than orphaning under a clip id that no longer resolves. Under §5.1 this needs
no special casing: it is what re-partitioning does when nothing contains the
note.

### 5.3 Storage

The existing single `Settings` JSON map (`LocalAnnotationsRepository.kt:81`,
key `local_annotations`) gains clip ids as additional keys alongside entry ids.
`annotationsFor(id)` (`:31`) already works for either. `removeAllFor(entryId)`
(`:58`) learns to drop every `local:<entryId>:` key too, or removing a video
would leave its rally notes behind forever.

### 5.4 The labels themselves

Unchanged. `AnnotationLabelsRepository` is already the shared palette that both
the cloud rally page and `LocalPlayerScreen` read, with its own on-disk cache,
so a local rally page picks labels from the same list and
`buildMatchLabelSummary` (`MatchLabelSummary.kt:107`) rolls them up with no
change: it takes `RallyClip` and `RallyAnnotation`, which is what §4.2 produces.

---

## 6. Promotion: the drawer, and the five affordances

### 6.1 The row moves

`ClipListViewModel.toMatches` groups whatever clips it is given, so once the
composite yields local clips, a finished device run becomes a `MatchSummary`
and a `MatchRow.Video` with no change to either.

The mirror of the existing de-duplication rule is required.
`ClipListScreen.kt:116` already filters "On this phone" down to
`localRows.filter { it.entry.scoreLogId == null }` so an attached video is not
"the same match twice". A promoted entry needs the same treatment: an entry
with local clips is excluded from `standaloneRows`, or it renders in both
sections. iOS applies the same filter in `ClipListModel.swift`.

### 6.2 Where the row's menu goes

`LocalVideoSection.kt:216-234` (iOS `LocalVideoSection.swift:116-125`) is where
five affordances live. Promotion moves the row, so it moves all five:

| On the local row today | After promotion |
|---|---|
| "Clips on this phone (N)" (`:216`) | Gone. It *is* the match page now. |
| "Player heatmap" (`:222`) | Match page overflow. Route unchanged (`Route.Heatmap(entryId)`, and `videoId` on a local match is the entry id, so the route argument is already in hand). |
| "Edit details" (`:228`) | Gated to `stage == LOCAL` already (`canEditLocalVideoDetails`), which a device-analysed entry still is, so it moves to the match page overflow with its rule unchanged. |
| "Remove from app" (`:234`) | Match page overflow, same confirm text, same `canRemoveLocalVideo` gate. It now also deletes the clips and their notes (§5.3). |
| Cloud "Analyze" (the row's button, not its menu) | Match page overflow. See §7.3 for what happens when it is used. |

### 6.3 What the match page must gate

`MatchScreen` and `MatchView` are written for a cloud or scored match. For a
local one:

- **Share is hidden.** `MatchScreen.kt:398` gates the share sheet on
  `videoId != null`, which a local match satisfies. The gate becomes "not a
  local match", derived from the clips rather than from the videoId, because
  §1.3 means a local match and its cloud version share an id.
- **The points facet is absent**, exactly as it is for any video-first match.
  A device run has no score log.
- **Delete** routes to the local removal path, not `delete_match`.

---

## 7. The composite repositories

All three are constructed in `RallyApp.kt:63-66`, so both platforms get them
with no view-layer change.

### 7.1 `CompositeClipsRepository`

- `observeClips()` = `combine(cloud.observeClips(), local.clips)`, cloud first.
- `refresh()` refreshes **the cloud half only** and re-merges. This is the one
  that bites: `ClipsRepositoryImpl.refresh()` (`ClipsRepository.kt:35-37`) is a
  whole-list replace, so routed straight through it wipes local clips out from
  under an open match page.
- `updateTitle(clipId, title)` routes on the `local:` prefix (§4.3).
- `countClipsForVideo` and `pruneVideo` stay cloud-only. The former's single
  caller is `AnalyzeCoordinator.kt:186`, on the cloud path, where a local id
  never reaches it.

### 7.2 `CompositeAnnotationsRepository` and `CompositeMediaRepository`

`AnnotationsRepository`'s four methods route on the clip id prefix; the local
side wraps `LocalAnnotationsRepository` and returns `RallyAnnotation` values
built from `LocalAnnotation` (same fields, plus the clip id the map key already
carries).

`MediaRepository` returns `clip.clipStoragePath` unchanged for a local clip -
it is already a `file://` URL. Both players accept it: Android hands the string
to ExoPlayer, and iOS does `URL(string:)` on it (`ClipDetailModel.swift:89-90`).

### 7.3 One video, two analyses

Because `entryId == videos.id`, running cloud analysis on an entry that already
has a device match produces cloud clips under the same `videoId`. The composite
prefers cloud clips for a videoId that has both, so the match page switches to
the cloud rallies.

**The consequence, stated rather than solved: notes made on the device rallies
stop being visible on that match.** They are not deleted - they remain in the
local store under their clip ids, and removing the cloud video restores them -
but nothing surfaces them while cloud clips exist. Carrying them across means
uploading annotations, which is the sync path this design does not build.

---

## 8. Deliberately not in this pass

- **Any upload.** No clips, no notes, no `rally_clips` rows. §5.5 of the
  2026-08-31 design remains the design for that, unbuilt.
- **Sharing a local match.** It has no server rows to grant anyone.
- **Carrying local notes onto cloud clips** when both exist (§7.3).
- **Audio in clips.** `ClipCutter.kt:33` still drops the source's audio track.
  A real gap for reviewing a rally, unchanged here, and worth its own pass.
- **Colour parity between local and cloud clips.** §6.4 of the 2026-08-31
  design: the cloud decodes BT.709 footage with BT.601 coefficients, so the two
  will not match in one list. Cosmetic, and fixing it belongs with that
  divergence, not here.
- **The heatmap, skeleton and metrics panels.** They keep their own routes and
  their own entry point; only the menu item's location moves (§6.2).
- **Retiring `Route.LocalClips` / `LocalClipsView`.** They stay as the A/B
  comparison surface they were written to be, reachable in debug builds. They
  are no longer how a coach reaches their rallies.

---

## 9. How this is verified

### 9.1 Shared, `commonTest`

Test-first. Every rule worth pinning lives here, and both platforms get it:

- `RallyClip` synthesis: ids, 1-based rally index, title stamping and fallback,
  live note counts, owner stamped at read time.
- Note partitioning: video time to clip time and back is lossless; running it
  twice is a no-op; a note outside every rally stays in video time; an entry
  with unknown bounds is left alone.
- `matchClipWindows`: overlap pairing, a changed rally count, a rally that
  disappears, and the minimum-overlap refusal.
- `planReanchor` wiring: existing tests stay green and gain a caller.
- Composite: merge order, `refresh()` leaving local clips intact, prefix
  routing for `updateTitle`, cloud-preferred de-duplication when one videoId
  has both.

### 9.2 Android

- `ClipListViewModel` promotes an analysed entry into `ownedRows` and drops it
  from `standaloneRows`.
- The match page draws rallies, the summary strip and the note counts for a
  local match, and hides share.

### 9.3 iOS

- The same two, against `ClipListModel` and `MatchView`.

### 9.4 On device

Capture a screenshot at each step rather than chaining blind taps, which drift:

1. An existing pre-sidecar run: the match appears in "My matches", its rallies
   show a real duration rather than "0.0s", and thumbnails decode.
2. Label a rally; the summary strip appears over the match with the right count.
3. Kill the app, reopen: the label is still there.
4. Re-run analysis on the same entry; notes land on the right rallies and none
   is lost.
5. Both platforms, both themes. Light theme is the one to check: `bgInput` and
   `bgTertiary` resolve to the same colour there, so a layered surface that
   reads correctly in dark can flatten into one block.
