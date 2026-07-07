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

## [0.1.0] - 2026-05-18

Initial pre-release of the Android companion app.

### Added
- Sign in with Supabase auth.
- List of owned matches and matches shared with you, including the sharer's email.
- Match clip list with thumbnails and per-rally playback.
- Clip detail view with frame-step controls.
- Share sheet for sending matches to other users.
- Light/dark theme toggle.
