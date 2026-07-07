# Local Video Recording & Analyze — Design

**Date:** 2026-07-07
**Status:** Approved by user (brainstorming session)

## Goal

Let the user record or import a match video on the phone, keep it **local** (a file
reference only — no upload) until they choose to analyze it, then run the existing
cloud rally-analysis pipeline from the phone: court marking → upload → trigger →
progress → rally clips appear in "My matches". Local videos are playable in-app with
the existing frame-step controls.

## Decisions made with the user

| Question | Decision |
|---|---|
| Upload timing | Video stays local until **Analyze**; Analyze uploads then triggers the pipeline. Backend unchanged. |
| Court keypoints | Full 12-point court marking on mobile, byte-identical output to desktop `CourtSetup.vue`. |
| Video source | System camera (`ACTION_VIDEO_CAPTURE`) + Photo Picker import. No in-app camera. |
| UI placement | "On this phone" section on the existing matches screen, above "My matches". |
| Post-analysis | Local entry auto-removed on success; gallery file untouched. |
| Upload reliability | Foreground-only, app-scoped coroutine, resumable (TUS). WorkManager is a possible follow-up. |
| Architecture | Approach 1: thin Android feature + one new shared repository; backend untouched. |

## How the existing pipeline works (investigated, reused unchanged)

1. Web uploads the raw video to the `videos` storage bucket at `{uid}/{videoId}.mp4`,
   inserts a `videos` row (client-generated UUID, status `uploaded`).
2. User marks 12 court keypoints; saved to `videos.manual_court_keypoints` (jsonb).
   The `process-video` Edge Function **refuses** to start without it.
3. `process-video` Edge Function (JWT auth): validates status `uploaded` + keypoints,
   signs a video URL, flips status to `processing_phase1`, HMAC-calls the Modal GPU
   worker.
4. Modal **phase 1**: downloads video, rally detection, cuts per-rally clips (ffmpeg),
   uploads to `clips` bucket + thumbnails, inserts `rally_clips` rows, writes
   status/progress to the `videos` row. Terminal: `phase1_complete`
   (or `failed_phase1`). Phase 2 (`start-analytics`, heavy analytics → `completed`)
   is **not** needed for clips and is not triggered from mobile.
5. RLS already allows everything mobile needs: owners can insert `videos` rows,
   upload to `videos/{uid}/...`, and update `manual_court_keypoints`
   (column-level grant). No backend change required.

Video status values (migration `0005_two_phase_pipeline.sql`): `pending`, `uploaded`,
`processing_phase1`, `phase1_complete`, `processing_phase2`, `completed`,
`failed_phase1`, `failed_phase2` (+ legacy `processing`, `failed`).
**Mobile treats `phase1_complete` and `completed` as success**, `failed_*` as failure.

## Architecture

### Shared module (KMP) — new `VideosRepository`

Same interface + impl pattern as existing repos. New dependency: `functions-kt`
(supabase BOM already pins the version).

```kotlin
interface VideosRepository {
    /** Insert the videos row. Call AFTER upload succeeds (same order as web). */
    suspend fun createVideo(videoId: String, filename: String, sizeBytes: Long): Result<Unit>
    /** Resumable TUS upload to videos/{uid}/{videoId}.mp4 with progress 0f..1f. */
    fun uploadVideo(videoId: String, filename: String, sizeBytes: Long,
                    dataProducer: UploadDataProducer): Flow<UploadState>
    /** Write manual_court_keypoints (identical JSON to desktop). */
    suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit>
    /** Invoke the process-video Edge Function. */
    suspend fun startProcessing(videoId: String): Result<Unit>
    /** Poll the videos row every ~5s; emits status+progress until terminal. */
    fun observeProcessing(videoId: String): Flow<ProcessingUpdate>
}
```

- `UploadDataProducer` is a platform-neutral function type providing a ktor
  `ByteReadChannel` (Android side opens `ContentResolver` streams). Resumable
  restarts request a fresh channel at a given offset.
- `CourtKeypoints` is `@Serializable` with exactly the desktop field names:
  `top_left`, `top_right`, `bottom_right`, `bottom_left`, `net_left`, `net_right`,
  `service_line_near_left`, `service_line_near_right`, `service_line_far_left`,
  `service_line_far_right`, `center_near`, `center_far` — each `[x, y]`
  (float, source-video pixels).
- `ProcessingUpdate(status, progress, error)` with an `isTerminalSuccess` /
  `isFailure` classification as above.
- storage path convention `{uid}/{videoId}.mp4` and 1 GB size cap mirror the web.

### Android app — new `localvideo` feature package

**`LocalVideoRepository`** (androidApp, like `ThemePreferenceRepository`): persisted
registry of local entries, serialized as JSON into the existing
`multiplatform-settings` store. Entry:

```kotlin
@Serializable
data class LocalVideoEntry(
    val id: String,              // client UUID; becomes videos.id on Analyze
    val uri: String,             // content:// URI (persistable permission taken)
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    val keypoints: CourtKeypoints? = null,  // saved before upload; survives retry
    val stage: AnalyzeStage = AnalyzeStage.LOCAL,
    val failedStep: AnalyzeStep? = null,    // UPLOAD | CREATE_ROW | KEYPOINTS | TRIGGER | PROCESSING
    val failureMessage: String? = null,
)
enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED }
```

Exposes `StateFlow<List<LocalVideoEntry>>` + CRUD. Transient progress (upload %,
pipeline %) lives in the coordinator's in-memory state, not in Settings.

**`AnalyzeCoordinator`** (Application-scoped, created in `RallyAndroidApp`): owns the
analyze state machine, runs in an app-scoped `CoroutineScope` (`SupervisorJob +
Dispatchers.Default`) so it survives navigation (not process death — accepted).

Analyze flow (all interaction first, then hands-off):
1. Analyze tapped → **CourtMarkingScreen** (local frame, instant).
2. Keypoints saved into the entry → stage `UPLOADING`.
3. Upload (resumable, progress) → `createVideo` row → `setCourtKeypoints` →
   `startProcessing` → stage `PROCESSING` → poll `observeProcessing`.
4. Terminal success → `clips.refresh()`, entry removed → match appears in
   "My matches".
5. Any failure → stage `FAILED(step, message)`; **Retry resumes from the failed
   step** (keypoints are never re-tapped; TUS resumes partial uploads).
6. `FLAG_KEEP_SCREEN_ON` while an upload is active (via a Compose effect observing
   coordinator state).

Process-death recovery: entries persisted as `UPLOADING`/`PROCESSING` are shown on
next launch as resumable (`Retry`-style affordance); `PROCESSING` entries re-attach
to polling automatically since the backend job continued without us.

### Video intake

- **Record**: `ACTION_VIDEO_CAPTURE` with `EXTRA_OUTPUT` pointing at an app-created
  `MediaStore.Video` URI (`Movies/Shuttl`); app-created MediaStore items stay
  readable by the app. Falls back to gallery import if no camera app.
- **Import**: `PickVisualMedia.VideoOnly` Photo Picker;
  `takePersistableUriPermission` on the result.
- Metadata (duration, size, display name) read via `MediaMetadataRetriever` /
  `ContentResolver.query`.
- Cap: reject files > 1 GB with a friendly message (web parity).

### Court marking — exact desktop parity (`CourtSetup.vue` is the spec)

- Frame at **t = 0.1s** (avoids black first frame), decoded at source resolution
  (`MediaMetadataRetriever.getFrameAtTime` with `OPTION_CLOSEST`).
- 12 points, identical order/labels/colors:
  `TL,TR,BR,BL` (red/green/blue/yellow), `NL,NR` (magenta/cyan),
  `SNL,SNR` (orange/lime), `SFL,SFR` (azure/rose), `CTN,CTF` (white/gray);
  full labels "Top-Left Corner" … "Center-Far".
- Identical overlays: dashed-green approximate-court guide (15% margins, net line,
  service lines at 60%, center line) under the points; instruction box with next
  label in next color; "n / 12 points" counter; dashed rectangle connecting the
  first 4 corners; markers = filled circle + black outline + short label centered.
- Identical controls: tap to place, Undo, Start Analysis enabled only at 12/12.
- Identical output: display→source-pixel mapping (`scaleX/scaleY`), JSON structure
  byte-equivalent to desktop's `saveAndProceed()`.
- Mobile-only affordances that do NOT change output: pinch-zoom/pan
  (`graphicsLayer` transform; taps inverse-mapped to source pixels), and a compact
  schematic court diagram (Compose Canvas, proportions from `homography.ts`
  `COURT_KEYPOINT_POSITIONS`) shown below the frame instead of the desktop's
  280px side panel.

### UI integration

- **ClipListScreen**: "+" `IconButton` in top bar → dropdown "Record video" /
  "Import video". New "ON THIS PHONE" section above "My matches"; rows show video
  thumbnail (Coil `coil-video` decoder), name, date, duration, status line
  ("Uploading 42%…", "Analyzing 67%…", "Failed — tap to retry"), Analyze button
  (stages LOCAL/FAILED), overflow remove (reference only). Section hidden when
  empty.
- **LocalPlayerScreen** (`Route.LocalPlayer(entryId)`): ExoPlayer on the
  `content://` URI, reusing `FrameStepBar` and the fullscreen/orientation behavior
  from `ClipDetailScreen` (extract the shared fullscreen system-bars logic into a
  small reusable helper rather than duplicating). No annotations. Analyze button
  in the top bar.
- **CourtMarkingScreen** (`Route.CourtMarking(entryId)`): as specced above.
- New routes added to `Route` + `AuthGate` NavHost, same `viewModelFactory` style.

### New dependencies

| Module | Dependency | Purpose |
|---|---|---|
| shared | `supabase-functions` (`functions-kt`, BOM-pinned) | invoke `process-video` |
| androidApp | `coil-video` | local video thumbnails |

Both latest-stable via the existing version catalog. No other new libraries —
recording/import use Activity Result contracts already available via
`activity-compose`.

## Error handling

| Failure | Behavior |
|---|---|
| Upload interrupted | `FAILED(UPLOAD)`; Retry resumes the TUS session. |
| Row insert / keypoints write fails | `FAILED(CREATE_ROW/KEYPOINTS)`; Retry re-runs that step (idempotent: row insert retried with same UUID → treat duplicate-key as success). |
| Edge Function non-2xx | `FAILED(TRIGGER)` with server message; 409 (already processing) treated as success-in-progress. |
| Pipeline `failed_phase1/2` | `FAILED(PROCESSING)` with `videos.error`. Re-trigger is blocked by the Edge Function status guard (true on web too) — surfaced honestly; backend follow-up out of scope. |
| File missing / permission revoked | Row shows "File missing"; playback and Analyze disabled; remove available. |
| App killed mid-upload | Entry persists as `UPLOADING`; next launch shows resumable state. |
| File > 1 GB | Rejected at intake with message (web parity). |

## Testing

Existing conventions (fakes + kotest + Turbine + runTest; MockEngine in shared):

- **shared** `VideosRepositoryTest`: row insert payload, keypoints JSON compared
  against a fixture captured from a real desktop save, Edge Function invocation
  (path + auth), polling emissions incl. terminal classification, upload progress.
- **androidApp**: `LocalVideoRepositoryTest` (MapSettings persistence round-trip),
  `AnalyzeCoordinatorTest` (state machine: happy path, per-step failures, retry
  resume points, auto-remove on success + clips refresh) with fakes,
  `CourtMarkingViewModelTest` (tap sequencing, undo, completion gating), and pure
  unit tests for display↔source coordinate mapping (incl. zoom/pan inverse
  mapping) and the desktop-parity JSON serialization.
- Manual on-device verification of the full record→analyze→clips loop.

## Out of scope (explicit)

- Backend changes of any kind (incl. retry of failed pipeline runs).
- Phase 2 analytics triggering from mobile.
- WorkManager/background upload (follow-up if foreground proves annoying).
- iOS UI (shared `VideosRepository` is iOS-ready by construction).
- Deleting the gallery file, ever.
