# Cloud analysis parity - Implementation Plan

**Goal:** A match analysed in the cloud shows the same analytics surface a match analysed on this phone shows - Heatmap and Base, reachable from the Analytics list - with both players rather than one. The panels a cloud run fills are the panels a device run fills, resolved from one source so the two can never half-agree.

**Why now:** `2026-09-12-cloud-pose-artifacts-plan.md` landed Tasks 1-14 and never ran Task 15 (end to end, on a real match). Four defects shipped behind that gap. Each is reproduced by a failing test below before it is fixed.

**Spec:** `docs/plans/2026-09-12-cloud-pose-artifacts-design.md` sections 3, 8 and 10 still hold. Nothing here changes the wire format, the worker, or `results.json`.

## What is broken

1. **The cloud artifact is deleted seconds after it lands.** `AnalyzeCoordinator` succeeds, installs the cloud track and skeleton, then calls `localVideos.remove(entryId)`. That fires `onLocalVideoRemoved` -> `AnalysisFiles.deleteAll`, whose store list includes `cloud-tracks` and `cloud-skeletons`. Both platforms. Blocks 2, 3 and 4.
2. **Base can never appear for a cloud run.** `availablePanels(hasBoundedClips = ...)` is fed from `storedClips`, the device clip index, which the cloud path never writes.
3. **`READY_NO_VIDEO` is unreachable, and would not open.** Android computes `storedTrackIds` from `localEntries` only; iOS hardcodes `entry.map { ... } ?? false`; and Android's `rowFor` sets `entryId = entry?.id`, so the row has no id to navigate with.
4. **A stale in-memory device run outranks the stored cloud track.** `heatmapSource` prefers `LocalAnalysisState.Done` (near only) over `storedTrack` (cloud, both), contradicting `storedTrack`'s documented "cloud wins, and silently".

## Decisions taken

**D1 - Retiring is not discarding.** `LocalVideoRepository`'s removal hook gains a reason. A coach deleting a video discards it and everything derived from it goes. The pipeline finishing retires it: the entry and the footage go, the analysis stays. This is the narrowest fix that keeps `AnalysisFiles`'s stated invariant ("nothing protects a cloud artifact the way it protects a local one") true for the case it was written for - a deliberate delete - without applying it to the pipeline tidying up after itself.

**D2 - The video is still released on a successful cloud run.** Unchanged, deliberately. `AnalyzeCoordinatorTest.a_successful_analysis_releases_the_local_file` states the reason: the clips live in the cloud now, and on iOS the on-device copy is the only thing holding that storage. Freeing it is the point of offloading.

**D3 - Skeleton stays conditional on the video, and that is the one place parity stops.** The skeleton is an overlay on playback. D2 means a cloud run usually leaves no footage to overlay, so the tab is not offered - which is exactly what `panelCapabilities` already promises ("Both players, if the video is here") and what `availablePanels(hasVideo = ...)` exists for. It DOES appear when the video is still here (an annotated video, kept as `ANALYZED`). Drawing the skeleton over the streamed cloud clips instead would need every skeleton frame re-anchored onto a clip timeline; that is a feature, not a fix, and is out of scope. Noted in "Follow-ups".

**D4 - One source per entry, for every panel.** Track, skeleton and rally windows are resolved together from whichever run produced the track being drawn. A cloud track with device rally windows is two runs' answers on one court. This also subsumes defect 4.

**D5 - Cloud rally windows come from `rally_clips`.** It is already the rally truth for a cloud video (spec section 3). No rally is computed on the phone from a cloud pose stream; `CloudPoseOutcome` still has nowhere to put one.

## Global constraints

- No em dash anywhere written by hand. Plain dash.
- No agent attribution on commits.
- `rally_index` is 1-based. No display adds `+ 1`.
- The cloud path never computes a rally. No step may reach a rally detector from a cloud-sourced `RawInference`.
- Do not hand-edit `project.pbxproj`. Run `xcodegen generate` from `iosApp/` after adding Swift files.
- iOS commands prefixed with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; simulator builds need `CODE_SIGNING_ALLOWED=NO`.
- iOS test names mirror Android test names one for one.
- TDD: write the failing test, watch it fail for the stated reason, implement, watch it pass.
- Android verification builds pin `ANDROID_SERIAL=emulator-5554`.

**Test commands:**

```bash
./gradlew :shared:jvmTest :analysis:jvmTest :androidApp:testDebugUnitTest
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" CODE_SIGNING_ALLOWED=NO
```

## Task 1: Retiring keeps the cloud analysis (defect 1)

Reproduced by `scratchpad/repro/ZzCloudArtifactRepro.kt`, which goes in as the first failing test.

**Files:**
- Modify `shared/.../localvideo/LocalVideoRepository.kt` - removal reason on the hook
- Modify `shared/.../localvideo/AnalyzeCoordinator.kt` - retire on success
- Modify `androidApp/.../localanalysis/AnalysisFiles.kt` - split the store list
- Modify `androidApp/.../RallyAndroidApp.kt`, `shared/.../RallyAppIos.kt`, `iosApp/Sources/RallyIOSApp.swift` - wiring
- Modify `iosApp/Sources/LocalAnalysis/AnalysisFiles.swift`
- Test `shared/.../localvideo/AnalyzeCoordinatorTest.kt`, `LocalVideoRepositoryTest.kt`, `iosApp/Tests/LocalVideoCleanupWiringTests.swift`

- [x] Step 1: failing tests - a retired entry keeps its cloud stores; a discarded one does not; the video is released either way
- [x] Step 2: watch them fail
- [x] Step 3: `LocalVideoRemoval { DISCARDED, RETIRED }`; `remove(id)` and `retire(id)`; hook takes both
- [x] Step 4: `AnalysisFiles.deleteAll(reason)` - `RETIRED` skips `cloud-tracks` and `cloud-skeletons`
- [x] Step 5: `AnalyzeCoordinator` success path calls `retire`
- [x] Step 6: run everything, commit

## Task 2: One resolved source per entry (defects 4 and, with Task 3, 2)

**Files:**
- Modify `androidApp/.../localanalysis/PlayerTrackStore.kt` - `Stored` carries its `TrackSource`
- Modify `androidApp/.../localanalysis/HeatmapPanel.kt` - `heatmapSource` respects it
- Mirror in `iosApp/Sources/LocalAnalysis/PlayerTrackStore.swift` and the iOS source resolution
- Test `androidApp/.../localanalysis/HeatmapSourceTest.kt` and its iOS twin

- [x] Step 1: failing test - a stored CLOUD track outranks an in-memory `Done`; a stored LOCAL track does not
- [x] Step 2: watch it fail
- [x] Step 3: implement on both platforms
- [x] Step 4: run everything, commit

## Task 3: Base for a cloud run (defect 2)

**Files:**
- Add `shared/.../analytics/RallyWindows.kt` - `cloudRallyWindows(clips: List<RallyClip>): List<RallyWindow>`
- Modify `androidApp/.../analytics/AnalyticsDetailScreen.kt` and `androidApp/.../localanalysis/BasePositionPanel.kt`
- Modify `iosApp/Sources/Analytics/AnalyticsDetailView.swift` and `BasePositionPanel.swift`
- Test a new `shared/.../analytics/RallyWindowsTest.kt` plus the two detail-screen tests

- [x] Step 1: failing test - a video with cloud clips and a cloud track offers Base; the windows are 1-based and carry the clips' bounds
- [x] Step 2: watch it fail
- [x] Step 3: implement; windows follow the resolved source from Task 2
- [x] Step 4: run everything, commit

## Task 4: The row is reachable (defect 3)

Reproduced by `scratchpad/repro/ZzCloudRepro.kt`.

**Files:**
- Modify `androidApp/.../analytics/AnalyticsRows.kt` - `entryId` survives for a `READY_NO_VIDEO` row
- Modify `androidApp/.../AuthGate.kt` - `storedTrackIds` covers match video ids, not only local entry ids
- Modify `iosApp/Sources/Analytics/AnalyticsRows.swift` and `AnalyticsListView.swift` - the same two
- Test `androidApp/.../analytics/AnalyticsRowsTest.kt` and `iosApp/Tests/AnalyticsRowsTests.swift`

- [x] Step 1: failing tests - a match with a stored track and no entry is `READY_NO_VIDEO`, tappable, and carries its video id; the owned/shared dedupe is unchanged
- [x] Step 2: watch them fail
- [x] Step 3: implement on both platforms
- [x] Step 4: check the `distinctBy` behaviour against `two_matches_that_are_both_off_this_phone_keep_their_own_rows`
- [x] Step 5: run everything, commit

## Task 5: Say what is true (copy) - DONE

`panelCapabilities` and `cloudNotes` are audited against the four fixes. `cloudNotes[2]` ("works even on a phone that never held the video") stays only if Task 4 makes it so for a match this phone uploaded; a match uploaded from ANOTHER phone still has no producer, because `CloudPoseCoordinator.install` has one caller inside the upload pipeline. Either wire that or soften the line - do not leave it claiming something with no code behind it.

- [x] Step 1: `cloudNotes[2]` replaced. The old line claimed a heatmap on a phone that never held the video; nothing fetches an artifact except the phone that uploaded, so it described a feature. The new line says the video leaves and what that costs, which is the promise a coach acts on before starting.
- [x] Step 2: `AnalysisCapabilityTest` still pins the table to `AnalyticsPanel.entries`
- [x] Step 3: commit

## Task 6: End to end, on a real match - NEEDS THE OWNER

This is the step the previous plan skipped, and skipping it is why this plan exists. It cannot be done by the agent: the iOS simulator's session is Keychain-only and needs a human sign-in, and the Android emulator cannot be reinstalled onto (121MB APK, /data 92% full).

- [ ] Step 1: owner runs one cloud analysis on a real phone, on a real match, from this branch
- [ ] Step 2: owner confirms the `CloudPose` log line `cloud pose <side>: N samples over M pose frames` appears for NEAR and FAR
- [ ] Step 3: owner opens Analytics and confirms the row is tappable after the run finishes
- [ ] Step 4: owner confirms the tab row shows Heatmap and Base, and that the Near/Far toggle has two usable sides
- [ ] Step 5: owner screenshots the tab row; agent records what was seen here

## Follow-ups (not in scope)

- The skeleton over streamed cloud clips, which would need skeleton frames re-anchored per clip. Only then can a cloud run match a device run's third panel without keeping the footage.
- A cloud analysis fetched for a match uploaded from a different phone. `CloudPoseCoordinator.install` has exactly one caller today, inside the upload pipeline, so the "phone that never held the video" story has no producer. See Task 5.
- The VFR risk: cloud poses are frame-indexed by the worker's decode (`CAP_PROP_POS_MSEC` timestamps) while the skeleton overlays the phone's copy. Invisible until the skeleton-over-clips work above.
