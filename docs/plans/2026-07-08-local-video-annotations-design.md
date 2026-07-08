# Local Video Annotations — Design

**Date:** 2026-07-08
**Status:** Approved by user (brainstorming session)

## Goal

Let the user add timestamped annotations to a local (on-phone) video, mirroring the
annotation experience that analyzed rally clips already have: a shot-quality "kind"
chip (good shot / forced error / unforced error) plus an optional note, tap-to-seek,
and delete. Local videos have no Supabase clip row, so the annotations are stored on
the phone.

## Decisions made with the user

| Question | Decision |
|---|---|
| What annotations attach to | The whole local video, at a timestamp. Independent of analysis. |
| Storage | On the phone (multiplatform-settings), not Supabase. |
| On analysis | If the local video has annotations, keep its entry (marked "Analyzed"); don't auto-remove. Videos with no annotations still auto-remove as today. |
| UX parity | Same surface as analyzed clips: kind chips + optional note, tap-to-seek, delete. |
| Disclosure | Show a small caption in the annotation area noting annotations are on-phone and removed when the video is removed from the app. |

## Existing pieces reused

- `AnnotationKind` (`shared/model/AnnotationKind.kt`) — good_shot / forced_error /
  unforced_error. Reused as-is so chips and colors match analyzed clips.
- `AnnotationKindStyle` + the add-annotation bottom sheet, kind chip, and annotation
  row — currently **private** inside `clipdetail/ClipDetailScreen.kt`. Extracted into
  a reusable component file so both screens share one implementation.
- `LocalVideoRepository` / `LocalVideoEntry` — the local-video registry; extended with
  an `ANALYZED` stage.
- `LocalPlayerScreen` — gains the annotation surface.

## Architecture

### Model (androidApp)

```kotlin
@Serializable
data class LocalAnnotation(
    val id: String,                 // client UUID
    val timestampSeconds: Float,
    val body: String,               // may be blank when only a kind is set
    val kind: AnnotationKind? = null,
    val createdAtEpochMs: Long,
)
```

Deliberately separate from the server `RallyAnnotation` (no `clipId`, local time base).
Reuses the shared `AnnotationKind`.

### `LocalAnnotationsRepository` (androidApp)

Same persistence pattern as `LocalVideoRepository`: JSON in the shared `Settings`
store under key `local_annotations`, shaped as `Map<localVideoId, List<LocalAnnotation>>`.

```kotlin
class LocalAnnotationsRepository(settings: Settings) {
    val byVideoId: StateFlow<Map<String, List<LocalAnnotation>>>
    fun annotationsFor(videoId: String): List<LocalAnnotation>   // sorted by timestamp
    fun hasAnnotations(videoId: String): Boolean
    fun add(videoId: String, timestampSeconds: Float, body: String, kind: AnnotationKind?)
    fun delete(videoId: String, annotationId: String)
    fun removeAllFor(videoId: String)   // called when a local video is removed
}
```

- Corrupt/absent JSON → empty map (same defensive load as `LocalVideoRepository`).
- Lists are kept sorted by `timestampSeconds` on write.

### `LocalPlayerViewModel` (androidApp)

Mirrors `ClipDetailViewModel`'s annotation handling, minus the server/owner/URL-signing
concerns:

```kotlin
class LocalPlayerViewModel(
    private val videoId: String,
    private val annotations: LocalAnnotationsRepository,
) : ViewModel() {
    val state: StateFlow<List<LocalAnnotation>>          // sorted
    val seekTo: SharedFlow<Long>                          // ms, tap-to-seek
    fun onAnnotationTap(a: LocalAnnotation)
    fun addAnnotation(timestampSeconds: Float, body: String, kind: AnnotationKind?)
    fun deleteAnnotation(id: String)
}
```

### Reusable annotation UI

New file `androidApp/.../clipdetail/AnnotationUi.kt` (kept in the `clipdetail`
package so `AnnotationKindStyle` stays co-located) exposing:

- `AddAnnotationSheet(onDismiss, onConfirm: (body, kind) -> Unit)` — the existing
  modal bottom sheet with kind chips + note field, lifted verbatim from
  `ClipDetailScreen`.
- `AnnotationRow(timestampSeconds, body, kind, onClick, onDelete)` — the existing row,
  generalized to take primitive fields (works for both `RallyAnnotation` and
  `LocalAnnotation`).
- `KindChip`, `AnnotationKind.style()` — moved here (from `AnnotationKindStyle.kt` /
  `ClipDetailScreen.kt`), unchanged behavior.

`ClipDetailScreen` is refactored to call these shared composables. No behavior change
to analyzed-clip annotations; existing `ClipDetailViewModelTest` stays green.

### `LocalPlayerScreen` changes

- A `+` FloatingActionButton (visible when not fullscreen) captures
  `player.currentPosition` and opens `AddAnnotationSheet`.
- Below the player + `FrameStepBar`: the annotation list (`LazyColumn` of
  `AnnotationRow`), tap seeks the player, delete shows a confirm dialog (same as
  `ClipDetailScreen`).
- **Disclosure caption** in the annotation area (empty-state text when there are no
  annotations, and a small caption under the list header otherwise):
  *"Annotations are saved on this phone and are removed if you remove the video from
  the app."*
- Screen now takes a `LocalPlayerViewModel` (built in `AuthGate` via `viewModelFactory`,
  keyed by entry id).

## Lifecycle

### New stage

`AnalyzeStage` gains `ANALYZED`. An `ANALYZED` local entry:
- stays in "On this phone",
- shows no Analyze button (shows an "Analyzed" label instead of a status/progress line),
- is tappable to play + annotate.

### `AnalyzeCoordinator` success path

Inject `LocalAnnotationsRepository`. On terminal success with clips present
(the existing `clipCount > 0` branch):

```kotlin
if (localAnnotations.hasAnnotations(entryId)) {
    localVideos.update(entryId) {
        it.copy(stage = AnalyzeStage.ANALYZED, failedStep = null, failureMessage = null)
    }
} else {
    localVideos.remove(entryId)   // unchanged behavior
}
```

The zero-clip ("no rallies") and failure branches are unchanged.

### Removal

`LocalVideoListViewModel.remove(id)` also calls
`localAnnotations.removeAllFor(id)` so a removed video leaves no orphaned annotations.
(Matches the disclosure caption.)

## Row rendering (LocalVideoSection)

`LocalVideoRow` / `toRow` gain handling for `ANALYZED`:
- `statusText = "Analyzed"`, `canAnalyze = false` (no Analyze button, no spinner).
- LOCAL/FAILED still show the Analyze button; UPLOADING/PROCESSING still show progress.

## Error handling

- Add with empty note **and** no kind → ignored (same guard as `ClipDetailViewModel`).
- Timestamp coerced to ≥ 0.
- Settings write failure is silent/best-effort (consistent with existing local repos);
  in-memory `StateFlow` is the source of truth for the session.
- No network paths, so no network error handling.

## Testing

Existing conventions (fakes + kotest + Turbine + runTest; `MapSettings` for local repos):

- `LocalAnnotationsRepositoryTest`: add/delete/round-trip via `MapSettings`, per-video
  keying isolation, timestamp sort, `hasAnnotations`, `removeAllFor`, corrupt-JSON →
  empty.
- `LocalPlayerViewModelTest`: add (kind-only, note-only, both), empty add ignored,
  delete, list sorted by timestamp, `onAnnotationTap` emits ms on `seekTo`.
- `AnalyzeCoordinatorTest` (extended): success **with** annotations → entry kept and
  stage `ANALYZED` (not removed); success **without** annotations → removed (existing
  case still holds). Uses a `FakeLocalAnnotationsRepository` or the real one over
  `MapSettings`.
- `LocalVideoListViewModelTest` (extended): `ANALYZED` row → `statusText == "Analyzed"`,
  `canAnalyze == false`.

## Out of scope (explicit)

- Syncing local annotations to Supabase or onto the analyzed rally clips.
- Editing an existing annotation (add/delete only, matching analyzed clips today).
- iOS.
- Any backend change.
