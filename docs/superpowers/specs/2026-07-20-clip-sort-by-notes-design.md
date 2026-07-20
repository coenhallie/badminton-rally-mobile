# Sort rally clips by number of notes — design

Date: 2026-07-20
Status: approved

## Goal

On the rally-clips page of a match, let the user sort clips by how many notes
(annotations) they have, in addition to the existing rally order.

## Scope

- Both platforms: Android (`MatchClipsScreen.kt`) and iOS (`MatchClipsView.swift`).
- UI-only. `RallyClip.annotationCount` already exists on the shared model; no
  shared-module, repository, or backend changes.
- Sort choice is ephemeral: it resets to rally order each time the screen opens.

## Sort options

A sort icon in the top bar opens a menu with two options:

| Option | Comparator |
|---|---|
| Rally order (default) | `rallyIndex` ascending — today's behavior |
| Most notes | `annotationCount` descending, tie-break `rallyIndex` ascending |

The active option is indicated in the menu (checkmark/radio).

## Android

In `MatchClipsScreen.kt`:

- File-local `ClipSort` enum: `RallyOrder`, `MostNotes`.
- `var sort by remember { mutableStateOf(ClipSort.RallyOrder) }`.
- Sort `IconButton` + `DropdownMenu` added to the `TopAppBar` actions.
- The existing `clipsForMatch` computation switches its comparator on `sort`.

## iOS

In `MatchClipsView.swift`:

- `@State private var sort` with the same two cases.
- Toolbar `Menu` containing a `Picker` (SwiftUI renders the active checkmark).
- The `.task` observer stores filtered clips unsorted; a computed property
  applies the active comparator so the list re-sorts when the selection changes.

## Error handling & testing

No new failure modes — pure in-memory sort of already-loaded data.
Verification is manual: build both apps, confirm the menu toggles ordering and
that equal-note clips fall back to rally order.
