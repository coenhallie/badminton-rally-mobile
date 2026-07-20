# Unify user-facing copy on "notes" — design

Date: 2026-07-20
Status: approved

## Goal

The app mixes "annotations" and "notes" in user-visible copy for the text
coaches attach to moments in rallies. Standardize every user-facing string on
**note/notes** — the natural coach term, and the one already used in clip row
subtitles and the sort menu.

## Scope

- UI copy only, both platforms (Android Compose strings, iOS SwiftUI strings).
- Includes accessibility strings (`contentDescription`, `accessibilityLabel`).
- NOT renamed: code identifiers, file names (`AddAnnotationSheet.swift`,
  `AnnotationUi.kt`, `AnnotationsRepository`, …), the `rally_annotations`
  database table, and the `annotation_count` API field. Coaches never see
  those; renaming them adds migration risk for zero user benefit.

## String changes

A fresh case-insensitive grep for "annotation" across `iosApp/Sources` and
`androidApp/src/main` during implementation is the authoritative list. Known
occurrences (mirrored on both platforms):

| Current | New |
|---|---|
| "Add annotation" (sheet title, add-button accessibility label) | "Add note" |
| "Delete annotation?" (dialog title) | "Delete note?" |
| "This annotation" (dialog body fallback) | "This note" |
| "Delete annotation" (delete-button accessibility label) | "Delete note" |
| "No annotations on this clip." | "No notes on this clip." |
| "Annotations are saved on this phone and are removed if you remove the video from the app." | "Notes are saved on this phone and are removed if you remove the video from the app." |
| "Couldn't load annotations" / "Couldn't add annotation" / "Couldn't delete annotation" | "Couldn't load notes" / "Couldn't add note" / "Couldn't delete note" |

Already correct, unchanged: "N NOTES" clip row subtitles, "Most notes" sort
option, "Note (optional)" text field placeholder.

Also: one-line entry under `[Unreleased]` → `### Changed` in CHANGELOG.md.

## Verification

String-only change:

- Both platforms compile; existing unit tests stay green (confirm during the
  sweep that none assert on the changed strings).
- Quick visual check in the running emulator/simulator (clip detail add/delete
  flow, local player).
