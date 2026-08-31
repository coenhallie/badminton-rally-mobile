# Design: changing and removing a match's video

**Date:** 2026-08-29
**Status:** Proposal, pending approval
**Follows:** `2026-08-28-match-video-attach-design.md`
**Supersedes:** the first bullet of §4.8 of that document, which says "No
affordance for this is built in this pass"

A match that has taken a video is stuck with it. If the analysis finds no
rallies - the most likely way a first attempt fails, since it depends on camera
angle, framing and light - the coach's only action is `Retry`, which re-runs the
same detector over the same file and reaches the same answer. He cannot swap the
video for a better recording, and he cannot take it away and keep the match.

Scope: from the match page, change the video (the old one goes, a new one is
picked and analysed) or remove it (the video, its rallies and their notes go;
the match, its points and its tags stay).

---

## 1. Two dead ends, not one

The reported symptom is the zero-rally case. There is a second, and an
affordance that only covers the first would leave it standing.

**1.1 The FAILED entry.** `AnalyzeCoordinator.runPipeline` counts the clips on
the server after the pipeline reports success, and a confirmed zero fails the
entry with "Analysis finished but found no rallies in this video."
(`AnalyzeCoordinator.kt`). The entry stays at `FAILED`, so `attachStatus`
returns `AttachKind.FAILED`, and both platforms render that message with a
`Retry` button. `canAddVideo` is false, because the `videos` row exists and the
score log is `BOUND`. Retry is the only thing on the page.

**1.2 The entry that is gone.** When there is no entry, `videos.id` is bound and
the clip count is zero, `attachStatus` returns `AttachKind.FINISHING_UP`, whose
only UI is a bare spinner - no button at all. It is meant to cover the seconds
between the pipeline finishing and `clips.refresh()` landing, and it resolves on
the next refresh in that case. It does not resolve when the video genuinely has
no clips and the entry is gone, which happens whenever the entry was removed
after a zero-rally failure (the list's delete path does exactly that,
`ClipListViewModel.deleteScoreLog`). That match then spins forever.

**So the affordance is not gated on a failure and not gated on a zero clip
count.** It is offered whenever the match has a video and nothing is in flight,
which is also what the request asks for: change or delete the video *per match*,
not only when the analysis disappointed.

---

## 2. The predicate

One shared function, next to the rest of the derivations both platforms read
(`AttachStatus.kt`, `canRemoveLocalVideo`), because three surfaces answering
this by hand is three chances for them to disagree:

```kotlin
fun canRemoveMatchVideo(hasVideo: Boolean, entry: LocalVideoEntry?): Boolean =
    (hasVideo || entry != null) && (entry == null || canRemoveLocalVideo(entry.stage))
```

- **has a video, in either sense.** `log.videoId != null` is the server's
  answer, and it is only true from `CREATE_ROW` onward. An entry that has not
  reached that step yet is still a video the coach picked for this match, and he
  must be able to take it back.
- **nothing in flight.** `canRemoveLocalVideo` already draws this line for the
  local library's own remove gesture: removing an entry mid-upload corrupts the
  run and swallows its outcome, because the failure update targets an entry that
  no longer exists. The same rule, not a second one.

`canAddVideo` and `canRemoveMatchVideo` are mutually exclusive by construction:
the first requires no video in either sense, the second requires one. The match
page therefore never shows "Add video" and "Remove video" at the same time, and
"one video per match" (§4.5 of the 2026-08-28 design) still holds.

---

## 3. Removal is one ordering, and it lives in shared

`removeMatchVideoOrMessage` in `shared/.../scoring/MatchVideoRemoval.kt`, taking
the five stores it touches and returning null on success or a ready-to-display
message on failure - the `...OrMessage` idiom `SwiftInterop.kt` already
establishes, so iOS calls it with no wrapper and Android reads the same value
into its error channel.

```
1. Refuse if the pipeline is running for this match's entry.
2. If log.videoId != null:
     videos.deleteMatch(videoId)      <- server first; on failure, stop here
     clips.pruneVideo(videoId)
     scoreLogs.detachVideo(scoreLogId)
3. Remove the local entry for this match, and its local annotations.
```

**Why shared rather than one copy per platform.** The delete sequences on the
match list are hand-ported (`ClipListViewModel.deleteMatchVideo` /
`ClipListModel.deleteMatch`) and that is the existing convention. It is the
wrong convention for this one: step 2 failing must abort steps 3 and 4, and iOS
cannot test that. `IosTestDoubles.kt` has only a `NoopVideosRepository` whose
`deleteMatch` returns unconditional success and records nothing, and
`ClipListModel` has no test file at all, so a Swift copy would ship unverified.
`commonTest`'s `FakeVideosRepository` already records `deleteMatchCalls` and
takes a `nextDeleteMatchResult`. The ordering is tested once, where it can be.

**Server first, then local**, for the reason `deleteBoundMatch` already
documents: a local removal that runs ahead of a failed server delete leaves the
phone claiming something the server disagrees with, and here it would also strand
the entry - the only handle on a file that still has a `videos` row.

**The `videoId == null` branch is real.** An entry that failed at `UPLOAD` or
`CREATE_ROW` has no `videos` row, no clips and no binding, so removal is purely
local. The confirm text differs accordingly (§4).

**Three outcomes, not two.** "Removed", "the server refused", and "the pipeline
is still running". The third is unreachable through the UI, which honours §2's
predicate, but the shared function re-checks it anyway: it is the only defence
against the menu being opened before an upload starts and tapped after. Its
message says to wait rather than to try again, because trying again immediately
would fail the same way.

**Two things the caller keeps.** The error channel, and whether to refresh.
Neither belongs in shared. No refresh is needed on success: `pruneVideo` and
`detachVideo` both write through to flows the page is already collecting.

---

## 4. What the coach sees

**One overflow menu per platform.** Android's match page already has one
("Export as text"); the two items go there. iOS has four discrete trailing
toolbar items and no overflow, so Export moves into a new ellipsis `Menu`
alongside the two new items - adding two more bare glyphs would put five in the
bar, and two of them would be indistinguishable under VoiceOver.

**Both are behind a confirm, and the confirm says what survives.** This is the
opposite of the list's bound-match confirm, which says the points go too, so its
wording cannot be reused:

| | Has a `videos` row | Local only |
| --- | --- | --- |
| **Remove** | "The video, its rallies and any notes on them are deleted. The match, its points and its tags stay." | "The video is taken off this match. The match, its points and its tags stay." |
| **Change** | Same first sentence, then "You'll pick a new video and Shuttl will analyse it." | Same, then the same sentence. |

The strings are built in shared, for the same reason `AttachStatus.text` is: two
platforms writing the same sentence are two chances to write it differently.

**Change is remove, then the attach path that already exists.** Once removal
lands, `log.videoId` is null and no entry points at the match, so `canAddVideo`
flips true on its own and `onAddVideo` / `attachVideo(intent)` runs the whole
pipeline unchanged - including `MatchTarget(log.id, log.title)`, which pre-fills
the new entry's title so the replacement video carries the match name onto its
INSERT (§3.2 of the 2026-08-28 design). No new attach machinery, and one owner
of the picker as that design requires.

The alternative - pick the new video first, then delete the old one - was
rejected: it would leave two entries pointing at one score log and a `videoId`
still naming the deleted video, for the whole window between the pick and the
delete. Deleting first means a coach who cancels the picker is left with a match
and no video, which is a state the page already handles and offers "Add video"
on.

**On iOS the confirm only records the answer.** The removal, and the picker
after it, run from an `onChange` on the alert's dismissal rather than from the
button's own closure: a sheet raised while an alert is still tearing down is
silently dropped, which is the same class of failure `MatchSheet` already exists
for in that file, and the picker is exactly such a sheet. Android needs no
equivalent - Compose has no such restriction, and the flow was watched working
there.

**The source is chosen in the confirm, not after it.** The Change dialog's
buttons are Cancel / Record / Import, matching the board's own finish prompt
(`ScoringScreen.kt`), rather than confirming and then raising a second dialog to
ask where the video comes from.

---

## 5. Not in this pass

- **The match list's rows.** The gesture is on the match page, which is where
  the video was attached from. Adding it to the swipe menu as well is a second
  surface for the same operation and was not asked for.
- **Storage objects orphaned by a failed upload.** `uploadVideo` writes storage
  objects before `createVideo` inserts the row, and `deleteMatch` - which is
  what deletes those objects - is skipped on the local-only branch of §3
  because there is no `videos` row to delete. Those objects leak. This is
  pre-existing and identical in `ClipListViewModel.deleteScoreLog`, which
  removes an entry the same way; fixing it needs a delete-by-path that
  `VideosRepository` does not expose. Reported, not fixed here.
- **Undo.** Removal is irreversible, which is what the confirm is for.

---

## 6. How this is verified

**Shared, unit:** `canRemoveMatchVideo` across every `AnalyzeStage` and both
`hasVideo` values; the prompt copy for all four cells of §4's table; and
`removeMatchVideoOrMessage`'s ordering - the full sequence, the local-only
branch, a server failure aborting the local half, and the running-pipeline
refusal.

**Both platforms, view model:** the parity convention `MatchModelTests` states
in its own header - the same cases, name for name, as `MatchViewModelTest`. The
gate is offered on a bound match, on a failed entry, on a match stuck at
"Finishing up", and not offered while uploading, while clipping, or on a match
with no video at all.

**On device, by hand:** done on Android, on the emulator, against the real
server. The reported dead end was reproduced first: a video attached to a scored
match, analysed, and left saying "Analysis finished but found no rallies in this
video." with Retry as the only action. Then, in order: the overflow offers
nothing new on a match with no video; "Change video" on a video still at LOCAL
shows the local-only wording, removes the entry and lands in the picker; the
overflow offers nothing new while the pipeline is clipping; and "Remove video"
on the failed, bound match deletes it on the server and leaves the match, its
score and both its points exactly where they were, with no orphan left in "On
this phone".

Not done on iOS: signing in on the simulator needs the user, and the simulator
has no touch API to drive the menu with. The Swift half is covered by the
same-named parity cases in `MatchModelTests` and nothing else.

**What cannot be verified here:** §5 of the 2026-08-28 design still stands -
`20260827000000_score_logs.sql` is not applied to the server, so
`ScoreLogsRepository.sync()` fails server-side and the `detachVideo` round trip
cannot be observed. Benign for this change: a failed upsert means the pull never
runs, so the local detach is not overwritten. It is still unverified rather than
verified.
