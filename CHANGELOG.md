# Changelog

All notable changes to the Rally mobile app are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The mobile app is versioned independently from the web app.

## [Unreleased]

### Added
- Swipe-to-remove on every row of the matches list, on both platforms: local
  videos (full swipe removes immediately; the row menu item remains), owned
  matches (confirmation dialog, then permanently deletes the match, its rally
  clips, annotations, shares, and storage files), and shared-with-me matches
  (leaves the share; the owner's data is untouched).
- Backend: `delete_match` and `leave_shared_match` RPCs plus owner delete
  policies on the `videos`/`clips`/`thumbnails` storage buckets
  (`supabase/migrations/20260718000000_delete_match.sql`).
- iOS: full analyze pipeline — 12-point court mapping, resumable upload with
  progress, live processing status, and failure/retry dialogs. iOS and Android
  are now at full feature parity.
- Shared: analyze orchestration (AnalyzeCoordinator) and court-marking geometry
  promoted to the shared module.
- iOS: frame stepping on remote clips, pull-to-refresh on match rallies, light/dark
  theme toggle (shared preference), app icon.
- iOS: record/import local videos with an "On this phone" library section
  (1 GB cap, recordings also saved to Photos), local playback with
  frame-accurate stepping, and on-device annotations.
- Shared: local video registry and local annotations persistence promoted to
  the shared module (identical storage format; Android data is preserved).
- iOS app (initial release, remote-clips parity): email sign-in, match list with
  thumbnails, rally clips, clip detail with AVPlayer and annotations, match sharing.
- Single shared version source `Config/Version.xcconfig` read by both Android and iOS;
  release tags `vX.Y.Z` are CI-verified against it. Version shown on both sign-in screens.
- Version indicator in the home screen overflow menu.
- Record or import match videos on the phone; they stay on-device under "On this phone".
- Play local videos with the same frame-step controls as rally clips.
- Analyze a local video from the phone: 12-point court mapping (identical to the
  desktop flow), resumable upload with progress, and live pipeline progress until
  the match's rally clips appear.
- Add timestamped annotations (shot-quality chips + notes) to local on-phone videos.
  Annotations are stored on the phone; a video keeps its annotations after analysis
  (marked "Analyzed") and loses them only when removed from the app.

### Removed
- The "Continue with Google" sign-in button. The OAuth callback was never
  wired up, so the button could not complete a sign-in; email sign-in is the
  supported method.

### Fixed
- "Retry" after an analysis failed during processing (including "no rallies
  found") silently failed again without doing anything: it re-read the stale
  failed status instead of re-running the analysis. Retry now re-triggers the
  pipeline, and the video's status row is reset before every trigger.
- The iOS video player screen now reflects analysis state live: the Analyze
  button disappears while an analysis is running, and the screen closes when
  the video completes analysis and moves to the matches list (matching
  Android). Previously the screen froze on its initial state and starting an
  analysis from it gave no feedback at all.
- Removing a local video is now blocked while it is uploading or analyzing,
  on both platforms. Removing mid-upload deleted the video file out from
  under the running upload and swallowed the failure entirely.
- The device no longer goes to sleep mid-upload when two videos upload at
  the same time and the first one finishes.
- An upload interrupted at the very last moment (app killed after the final
  chunk but before bookkeeping) now reports success on retry instead of
  failing with "File already uploaded" for a day.
- Re-analyzing a video whose earlier attempt failed more than a day ago
  errored instantly with "Bad Content-Type format: null" (a supabase-kt bug:
  the resumable-upload cache stored the literal string "null" as the content
  type and choked parsing it back when the expired entry was resumed). Uploads
  now set an explicit video/mp4 content type, retry once past a poisoned cache
  entry from an older build, and upload with upsert so re-analyzing a fully
  uploaded video no longer fails with "path already exists".
- iOS crashed at launch when a match's thumbnail file was missing from storage;
  a failed thumbnail signing now falls back to the placeholder on both platforms.
- iOS: import failures now surface an error; stale thumbnails evicted on remove;
  assorted intake polish. Android: friendlier refresh-error message.
- Signing out (or a revoked session) now returns to the sign-in screen from
  any screen, not just the home list.
- A video no longer gets stuck on an endless spinner if the app is killed
  mid-upload; analysis resumes on next launch.
- A brief network problem while waiting for analysis results no longer
  crashes the app; persistent problems show a retryable failure instead.
- Clip detail no longer covers a playable video with a full-screen error when
  only the annotations failed to load, shows a loading spinner instead of a
  premature "No annotations" message, reports the real cause when clips can't
  be fetched, and its Retry button now also works when the clip never loaded.
- Removing someone's access to a shared match now shows an error when it
  fails instead of doing nothing.
- A shared-match recipient without an email address no longer breaks the
  whole "manage shares" list.
- Local video playback shows an error with Retry when the file is missing or
  access was revoked, instead of a frozen player.
- Resumed uploads are positioned at the exact byte offset, preventing rare
  corrupted uploads after an interrupted transfer.
- Court-marking controls (including "Start Analysis") stayed on screen for
  portrait videos instead of being pushed out of view.
- Analyze failures now show the full error with the HTTP status and a Retry,
  instead of being clipped behind the button.
- Local-video upload waited for the transfer to actually finish before starting
  cloud processing (previously it could trigger against a not-yet-uploaded file).
- Analysis progress now shows a correct 0–100% instead of values like 9245%.
- A finished analysis that detects no rallies now keeps the entry with a clear
  message rather than silently disappearing.

## [0.1.0] - 2026-05-18

Initial pre-release of the Android companion app.

### Added
- Sign in with Supabase auth.
- List of owned matches and matches shared with you, including the sharer's email.
- Match clip list with thumbnails and per-rally playback.
- Clip detail view with frame-step controls.
- Share sheet for sending matches to other users.
- Light/dark theme toggle.
