# Changelog

All notable changes to the Rally mobile app are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The mobile app is versioned independently from the web app.

## [Unreleased]

### Added
- Version indicator in the home screen overflow menu.
- Record or import match videos on the phone; they stay on-device under "On this phone".
- Play local videos with the same frame-step controls as rally clips.
- Analyze a local video from the phone: 12-point court mapping (identical to the
  desktop flow), resumable upload with progress, and live pipeline progress until
  the match's rally clips appear.
- Add timestamped annotations (shot-quality chips + notes) to local on-phone videos.
  Annotations are stored on the phone; a video keeps its annotations after analysis
  (marked "Analyzed") and loses them only when removed from the app.

### Fixed
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
