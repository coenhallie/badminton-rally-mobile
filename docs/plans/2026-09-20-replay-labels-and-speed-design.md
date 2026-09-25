# Replay page: labels while watching, a settings gear, the speed in the transport row

2026-09-20. Requested by Coen's boss: add the labels to the match replay page - a movable
floating window over the video in fullscreen, a list by the control buttons otherwise - and
change the "1x" into a settings icon, with the speed shown in the control row.

## What existed

- The replay page is `ClipDetailScreen` / `ClipDetailView`. It shares its video card and
  transport with the local video player and the skeleton view (`VideoCard` + `TransportBar`;
  `ShuttlPlayer` = `ShuttlVideoCard` + `ShuttlTransportBar`).
- Speed was a "1x" chip on the video card that opened `PlaybackSettingsSheet`; the transport row
  was five controls and 12 dp a side from full on a 360 dp phone. The skeleton view also printed
  "1x speed" under its transport.
- Labels appeared only as badges on notes already made; a note was added through "+" and a sheet.
- Android had fullscreen (`2026-05-05-fullscreen-video-design.md`, which deferred "annotation
  overlay in fullscreen"). iOS had none and is portrait-only in Info.plist. Neither app had a
  draggable component.

## Decisions

| Decision | Chosen | Rejected, and why |
|---|---|---|
| What the label list does | One tap tags the moment on screen: the note the "+" sheet makes, minus the sheet (`addAnnotation(ts, "", label)`). Owner only; clip-scoped labels only (`clipLabels`) | Listing the rally's existing labels to jump to: they are already in the notes list under the video. Coen delegated the call; "add the labels" to a replay reads as the courtside board's interaction |
| Not fullscreen | A wrapping chip row under the transport, with one held line beneath it: the hint, then "Tagged Good shot at 0:12" for 2.5 s. **Revised 2026-09-22:** at most two lanes, dealt alternately in label order (1 top, 2 bottom, 3 top) with a cursor per lane, scrolling sideways once the wider lane outgrows the row; one line while everything fits. The lane arithmetic is the shared `LabelLanes` | "Next to" the buttons: the row has no spare width. A sideways-scrolling row: `2026-08-30-scoreboard-label-scope-design.md` section 1.1 records that failure. A line that appears and disappears: the notes would jump on every tag. Unlimited wrapping (the 2026-09-20 choice): an account with eight clip labels gave three rows of chips to every rally, paid for in video and notes. Column-major grid with aligned columns: pairs "test" with a long label and wastes the width beside it |
| Fullscreen | `FloatingLabelPanel`: title bar (grip, "Labels", fold chevron), compact chips capped at four rows and scrollable, a held message line. Dragged by the title bar only. A sibling of the video, never in `VideoCard`'s overlay slot, which scales and pans with the frame | A fixed panel: wherever it went it would cover somebody's half of the court |
| Where it may go | The fullscreen box minus the chip row (44) and the transport (96): it is drawn above both and would otherwise take the taps for play and for the way out of fullscreen. Found on iOS, where a landscape phone left the window over the exit chip | Trusting the coach not to park it there |
| Remembering it | `PanelAnchor`, a fraction of the window's travel, in `PlaybackPreferenceRepository` (`replay_label_panel_anchor`, "x,y"; anything unparseable is the default). Default: the area's top trailing corner | A pixel position: comes back off screen on another phone or once the chips wrap. Folding holds the pixel position until the new size is measured, then re-anchors, so the title bar does not jump under the finger |
| The arithmetic | Shared and pure: `FloatingPanelMath` (clamp / position / anchor). The house pattern for gestures (DrawerDragMath, SkipMath) | Per-platform clamping nobody can test |
| The gear | Replaces the "1x" chip on the card, same sheet. Android: a computed `ShuttlIcons.Settings` in the 14-unit 1.4 stroke family (no material-icons-extended). iOS: `gearshape` | A gear in the transport row: the request names the chip |
| The speed readout | In the transport row's trailing gutter, tappable to the same sheet, with the leading gutter matched so play stays centred. `TransportRowLayout.fit` (shared) picks the spacing from measured text: mock spacing; then 6 gap / 12 pill padding; then no chevrons and signed labels ("-10s" / "+10s"); then 4 / 8. Gutter is sized for the widest speed so the row does not re-space when the speed changes. Over video the readout is lettered in the overlay colour | A sixth control with a matching spacer: does not fit any phone under ~440 dp. An unmatched sixth control: play goes off centre |
| Scope | Gear and readout on all three players (shared components; the skeleton view's own "1x speed" text is removed as a duplicate). Labels on the rally replay and the local video player, which already shared the add-note flow | Flags to keep the other players on the old row |
| iOS fullscreen | New: a fullscreen chip on the card; `.fullScreenCover` reusing the same `AVPlayer`, card and transport; landscape held by `OrientationLock` through a new `AppDelegate` (`supportedInterfaceOrientationsFor`), portrait everywhere else. The cover carries its own settings sheet, because a sheet on the covered view cannot present | Landscape app-wide: nothing else is laid out for it |
| Errors in fullscreen | Said in the window's message line: the snackbar / banner is underneath the fullscreen surface | |

## Deliberately not in this pass

- Rotation-driven fullscreen on iOS (Android has it; iOS stays button-only while the app is portrait-locked).
- Pinch-to-zoom on iOS (already unported).
- Resizing the window; remembering that it was folded.

## Verification

- Shared: `TransportRowLayoutTest` (10, incl. "whatever is chosen fits" across 360-1000), `FloatingPanelMathTest` (10),
  `PlaybackPreferenceRepositoryTest` anchor cases. Full `:shared:jvmTest` 639.
- Android: unit suite 309. On the Pixel 9a emulator: gear, readout, chip row, one-tap tag and its note,
  fullscreen window, drag, position kept across a restart, fold without a jump, clearances.
- iOS: unit 302; `ReplayPlayerUITests` drives the local player through tag, fullscreen, drag, fold, exit and clean-up;
  `PlaybackBridgingTests` for both shared helpers. Frames checked in both orientations.
- The replay view model needed no new test: label-only notes, owner gating and clip scoping were already covered.

## Owner steps

- A real-phone check of iOS fullscreen rotation and of dragging the window with a finger.
