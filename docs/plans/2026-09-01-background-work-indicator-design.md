# Design: a background-work indicator in the app chrome

**Date:** 2026-09-01
**Status:** Proposal, pending approval
**Follows:** `2026-08-31-on-device-analysis-pipeline-design.md` (the on-device
pipeline whose runs this has to represent)

An analysis takes minutes. A cloud run uploads and then waits on Modal; an
on-device run is roughly seven times realtime, so a five-minute video is most of
an hour. Both are started from one screen and then left alone, which is the
whole point of them running in the background.

The app does not say so anywhere except that one screen. `LocalAnalysisBanner`
is mounted on the clip list route and nowhere else (`AuthGate.kt:164`), so
opening a match, a clip, the labels screen or the scoring surface leaves no
indication that anything is running at all. The user's report was that the app
looks idle while it is working.

This adds one indicator to the app chrome that is visible from every screen,
driven by a single merge of the cloud and on-device state.

The request was phrased as an icon in the navigation drawer. There is no
navigation drawer (§1.1); the equivalent always-visible surface is the top app
bar, and that is what this uses.

---

## 1. What exists today

Verified by reading the code, not assumed. File references are the evidence.

### 1.1 There is no navigation drawer on either platform

- Android is a single `NavHost` (`AuthGate.kt:127`). Each destination builds its
  own `Scaffold` and `TopAppBar`: `ClipListScreen.kt:153,155`,
  `MatchScreen.kt:196,198`, `ClipDetailScreen.kt:166,169`,
  `LabelsScreen.kt:83,85`, `NewMatchScreen.kt:54,56`,
  `LocalPlayerScreen.kt:200,203`, `CourtMarkingScreen.kt:85,87`. There is no
  `ModalNavigationDrawer`, no `NavigationBar`, and no `NavigationRail` anywhere
  in `androidApp/src/main`.
- iOS is a `NavigationStack` rooted at the clip list
  (`RootView.swift:17`), with per-view `.toolbar`.

Three of the seven Android bars already declare `actions`
(`ClipListScreen.kt:162`, `MatchScreen.kt:225`, `LabelsScreen.kt:97`,
`LocalPlayerScreen.kt:216`); the rest do not.

### 1.2 The state to show already exists, app-scoped

Nothing new is needed to *know* what is running, only somewhere to *show* it.

- `AnalyzeCoordinator.progress: StateFlow<Map<String, AnalyzeProgress>>`
  (`AnalyzeCoordinator.kt:48`), where `AnalyzeProgress` carries
  `uploadProgress` and `pipelineProgress` as nullable fractions
  (`AnalyzeCoordinator.kt:19-23`). Explicitly documented as transient.
- `AnalyzeCoordinator.hasActiveUpload: StateFlow<Boolean>`
  (`AnalyzeCoordinator.kt:51`).
- `LocalVideoRepository.entries: StateFlow<List<LocalVideoEntry>>`
  (`LocalVideoRepository.kt:32`), the durable half. Each entry carries
  `stage: AnalyzeStage` and `failedStep` (`LocalVideoEntry.kt:52-53`).
- `isAnalysisRunning(stage)` (`LocalVideoEntry.kt:15`) already defines which
  stages count as active: `UPLOADING` or `PROCESSING`. This design reuses it
  rather than restating the rule.
- `LocalAnalysisRunner.state: StateFlow<Map<String, LocalAnalysisState>>`
  (`LocalAnalysisRunner.kt:58`) for the on-device pipeline.

### 1.3 The device state is not in `:shared`, and that constrains the merge

`LocalAnalysisRunner` and `LocalAnalysisState` are declared in **androidApp**
(`localanalysis/LocalAnalysisRunner.kt:16,42`), not in `:shared`.
`LocalAnalysisCoordinator` is shared, but the flow holding per-video device
progress is not.

So a merge written in `commonMain` cannot reach the device state by itself. §4
resolves this rather than leaving it to be discovered mid-build.

### 1.4 `FAILED` is durable, and already surfaced on the row

`AnalyzeCoordinator.fail` persists `stage = FAILED` on the entry
(`AnalyzeCoordinator.kt:213`), and `LocalVideoRepository` restores entries from
storage on construction (`LocalVideoRepository.kt:31`). An entry stays `FAILED`
until `retry()` or removal.

That failure is already visible where it is actionable: the list offers Retry
for a failed entry (`AuthGate.kt:178,214`), and `canAnalyze` admits `FAILED`
(`LocalVideoListViewModel.kt:77`).

This matters because the obvious badge rule ("any entry is FAILED") would light
the indicator permanently for a video that failed last week. §3 decides against
it.

### 1.5 Both platforms currently pin the screen on during upload

- Android: `FLAG_KEEP_SCREEN_ON` while `hasActiveUpload`
  (`MainActivity.kt:42-48`), commented "foreground-only uploads".
- iOS: `UIApplication.shared.isIdleTimerDisabled` on the same flow
  (`RootView.swift:36-37`).

On Android that is now only true of the *cloud* path. The on-device path runs
under a foreground service and a refreshed partial wake lock
(`LocalAnalysisService.kt`), so it genuinely continues with the screen off.

On iOS nothing continues in the background: there is no foreground service, no
on-device pipeline, and uploads are not on a background `URLSession`. §6 is
where that lands.

---

## 2. The fact the design turns on

**The two pipelines have different truths about the background, and one
indicator has to tell both without lying.**

An on-device Android run keeps going with the screen off and the app swiped
away. A cloud run's *upload* stops when the app leaves the foreground, but its
*processing* continues on Modal regardless of the phone, which is why
`reattachToProcessing()` exists (`AnalyzeCoordinator.kt:87`).

So "the app is working in the background" is three different claims:

| Work | Continues with screen off? | Continues if app is closed? |
|---|---|---|
| On-device analysis (Android) | Yes, wake lock | Survives task removal; not a process kill |
| Cloud upload | No, foreground only | No |
| Cloud processing (Modal) | Yes, it is not on the phone | Yes |

The indicator therefore shows **what is happening**, not a blanket "working in
the background" reassurance. The label carries the distinction (§4.2).

---

## 3. Decisions

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Surface | Icon in each screen's `TopAppBar` actions | A navigation drawer: neither platform has one (§1.1), and adding one to hang a status icon on it inverts the cost |
| Composition | One `BackgroundWorkAction()` inserted into each bar's `actions` | A shared `AppScaffold` wrapper: the seven bars differ in title, nav icon and actions, and two are fullscreen players. A wrapper either re-parameterises everything back out or forces chrome onto screens that did not ask for it |
| Merge location | Pure function in `commonMain`, platform state passed in | Merging inside Android: guarantees the iOS version drifts, and leaves the rules untested in `commonTest` |
| Failure badge | Failures observed **during this app session** | "Any entry is `FAILED`": durable and sticky (§1.4), so a week-old failure would light the badge forever. The durable record already lives on the row with its Retry |
| Tap target | Navigate to the clip list | A sheet embedding `LocalAnalysisBanner`: the banner owns `LocalClipPlayerDialog`, so a clip player could open on top of `ClipDetailScreen`'s own player |
| Idle state | Render nothing | A grey "idle" icon: permanent chrome that says nothing, on seven bars |

---

## 4. Shared model

### 4.1 `DeviceWork`, the shared shape of a device run

`commonMain`, new file `localvideo/BackgroundWork.kt`. Android maps its
`LocalAnalysisState` into this; iOS passes an empty map today.

```kotlin
/**
 * One on-device run, reduced to what an indicator needs.
 *
 * The device pipeline's own state type lives in androidApp, so the merge cannot
 * see it. Reducing to this at the boundary is what lets the merge rules live in
 * commonMain and be tested there.
 */
enum class DevicePhase { PREPARING, ANALYSING, CUTTING }

data class DeviceWork(
    val entryId: String,
    val phase: DevicePhase,
    val fraction: Float?,
    val failed: Boolean,
)
```

The phase is carried rather than collapsed into one "analysing" because §2's
principle is that the indicator says what is happening: copying a
multi-gigabyte file and running inference over it are minutes apart in what the
user should expect next.

### 4.2 `BackgroundWork`, what the indicator renders

```kotlin
data class BackgroundWork(
    val activeCount: Int,
    val label: String,
    val fraction: Float?,   // null renders indeterminate
    val hasFailure: Boolean,
)
```

### 4.3 The merge, as a pure function

```kotlin
fun backgroundWork(
    entries: List<LocalVideoEntry>,
    progress: Map<String, AnalyzeProgress>,
    device: List<DeviceWork>,
    sessionFailures: Set<String>,
): BackgroundWork?
```

Rules, in order:

1. **Active set.** Cloud entries where `isAnalysisRunning(entry.stage)`
   (`LocalVideoEntry.kt:15`), plus every `DeviceWork` that is not `failed`.
2. **Nothing active and no session failure returns `null`.** The indicator is
   absent rather than idle.
3. **Fraction.** With exactly one active item, its fraction: for cloud, the
   `pipelineProgress` if present else `uploadProgress`; for device, its
   `fraction`. With more than one, `null`, because averaging two runs at
   different stages produces a number that means nothing.
4. **Label.** One item gets the specific phrasing that carries §2's
   distinction: `"Uploading 40%"` (foreground only), `"Processing in the
   cloud"`, `"Preparing video"`, `"Analysing on device 12%"`, `"Cutting
   clips"`. More than one gets `"3 analyses in progress"`, counting analyses
   rather than videos because running both pipelines over one video is the
   comparison this app exists to make.
5. **`hasFailure`** is `sessionFailures.isNotEmpty()`, never derived from
   `stage == FAILED` (§1.4).
6. **Failures clear.** The caller drops an id when its entry leaves `FAILED`
   (a retry) or stops existing (a removal), and a device failure clears when the
   runner replaces that state. A set that only grows is the §1.4 bug one layer
   up: retry, succeed, and a red dot would be left on five screens with nothing
   to click.

`sessionFailures` is owned by the caller and holds entry ids that moved into a
failed state while this process has been running. It is deliberately not
persisted: the durable record is the row.

---

## 5. Android

### 5.1 `BackgroundWorkMonitor`

A plain class, not a ViewModel: it is application-scoped, and a ViewModel
belongs to a `ViewModelStoreOwner`, so tying it to one would restart the
collection as the user navigates. Constructed once in `RallyAndroidApp`
alongside the existing `localAnalysis` runner, because the indicator outlives
every screen that shows it.

It takes the three **flows**, not the two coordinators. It needs nothing else
from them, and depending on the objects would drag a `Context` and a Supabase
client into a class whose whole job is a fold over three streams; it also makes
the failure rule testable with three `MutableStateFlow`s and no fakes.

It maps `LocalAnalysisState` into `List<DeviceWork>`, tracks `sessionFailures`
by observing entries whose stage transitions into `FAILED` while it is
collecting, and exposes `StateFlow<BackgroundWork?>`.

### 5.2 `BackgroundWorkAction`

```kotlin
@Composable fun BackgroundWorkAction(work: BackgroundWork?, onClick: () -> Unit)
@Composable fun BackgroundWorkAction()   // reads the ambient state
```

The explicit form holds the rendering and stays independently testable. The
no-argument overload reads two composition locals, `LocalBackgroundWork` and
`LocalBackgroundWorkClick`, provided once around the `NavHost`
(`AuthGate.kt:147`).

That is a composition local rather than two more parameters on five screen
signatures on purpose: this is app chrome that any screen may show and none of
them own, and threading it would put a progress concern into the signature of
every screen that happens to have a bar.

Renders nothing when `work` is `null`. Otherwise an `IconButton` containing a
`CircularProgressIndicator`: determinate when `fraction` is non-null,
indeterminate otherwise, with a small error dot when `hasFailure`. Its
`contentDescription` is the label, so the state is available to TalkBack rather
than encoded only in a ring.

### 5.3 Which bars get it

Added as the **first** entry in `actions`, so it sits left of each screen's own
buttons and does not move as those change.

| Screen | Gets it | Why |
|---|---|---|
| `ClipListScreen` | Yes | Already has `actions` (`:162`) |
| `MatchScreen` | Yes | Already has `actions` (`:225`) |
| `LabelsScreen` | Yes | Already has `actions` (`:97`) |
| `NewMatchScreen` | Yes | Needs `actions = {}` added |
| `ClipDetailScreen` | Yes | Needs `actions = {}` added |
| `LocalPlayerScreen` | No | A playback surface; a spinner in its chrome competes with the transport controls |
| `CourtMarkingScreen` | No | A focused modal task that ends in starting a run; showing progress for a different video mid-marking is a distraction |

Tapping navigates to the clip list, where `LocalAnalysisBanner` already renders
the detail (`AuthGate.kt:164`).

---

## 6. iOS

**Indicator only, and not in this pass.**

The shared merge (§4) is written so iOS can adopt it by passing an empty device
list. What iOS must not do is claim background work it does not do: uploads are
foreground-only (`RootView.swift:36-37`), there is no on-device pipeline, and
there is no background `URLSession`.

So the honest iOS sequence is:

1. Move uploads to `URLSessionConfiguration.background`.
2. Then adopt the indicator, with "Uploading" meaning it.

Until step 1, an iOS indicator would have to read "Uploading, keep the app
open", which is worth building only alongside the fix rather than instead of it.

---

## 7. Deliberately not in this pass

- **iOS.** §6.
- **A notification for cloud work.** The on-device path has one
  (`LocalAnalysisService.kt`); the cloud path does not, and giving it one is a
  separate question about whether foreground-only upload deserves one.
- **Checkpoint and resume.** Design section 9's Stage 5. The indicator reports
  a lost run as gone, because it is.
- **Making the tap target a detail sheet.** §3.
- **Reworking `LocalAnalysisBanner`.** It stays where it is and keeps its job.
- **Per-item cancellation from the indicator.**

---

## 8. How this is verified

### 8.1 Shared, `commonTest`

`BackgroundWorkTest` against the pure function, which is where the logic risk
is:

- Empty in, `null` out.
- One uploading entry gives its `uploadProgress`; once `pipelineProgress`
  appears it wins.
- `LOCAL`, `ANALYZED` and `FAILED` entries are not active
  (`isAnalysisRunning`).
- Two active items give `fraction == null` and a counted label.
- A `FAILED` entry alone does **not** set `hasFailure`; a `sessionFailures` id
  does. This is the §1.4 regression, and it is the test that would have caught
  the sticky badge.
- Each `DevicePhase` produces its own label.
- Device-only work with no cloud entries still produces a result.

### 8.2 Android

- `BackgroundWorkMonitorTest`: an entry transitioning into `FAILED` while
  collecting adds to `sessionFailures`; one already `FAILED` at first
  collection does not; a retry, a removal, and a restarted device run each
  clear it again. Verified by mutation: making the set accumulate-only fails
  all three clearing tests.
- **Owed, not written:** a Compose UI test that `BackgroundWorkAction` renders
  nothing for `null` and exposes the label as its content description. This
  project has no Compose test harness at all (no `ui-test-junit4` dependency and
  no `createComposeRule` anywhere in `androidApp/src/androidTest`), and adding
  one is its own change. The composable holds no logic beyond the early return,
  and every rule it renders is covered above.

### 8.3 On device

Start an on-device run, navigate to a match and to the labels screen, and
confirm the indicator is present with a moving fraction on each. Then let the
screen go off and confirm on return that it advanced.

---

## 9. Owed from the previous pass

Both are blocked on the phone reconnecting to wireless debugging, and neither is
superseded by this work:

- The swipe-off-recents test on the S23. The code derivation says a run
  survives task removal (application scope, no `stopWithTask`, no
  `onTaskRemoved`); Samsung's kill behaviour is unconfirmed.
- The wake-lock test re-run at its widened margin.
- **This indicator has never been rendered.** The merge rules and the failure
  rule are covered by 20 tests, but the composable has not been on a screen
  once: no ring, no error dot, no bar layout and no TalkBack description has
  been seen. A test count is not a substitute for looking at it.
