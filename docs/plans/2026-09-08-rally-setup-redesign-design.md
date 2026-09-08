# Rally Setup Redesign - Design

**Date:** 2026-09-08
**Status:** implemented (Android)
**Builds on:** `androidApp/.../localvideo/court/CourtMarkingScreen.kt`, `androidApp/.../localanalysis/MetricSelector.kt`.
**Reference mock:** `~/Downloads/redesign/Rally Setup.dc.html`.

## Goal

Court marking is the last screen the coach passes through before a run that can
cost half an hour, and it was the last screen still built the way it was before
the design language landed. `docs/plans/2026-09-03-home-and-analytics-redesign-design.md`
put it out of scope with the note that it "had no mock". It has one now.

The mock asks for two things: the app's own visual language, and a split. Court
calibration is one question, what the run should produce is another, and the
screen had been asking both at once.

## What the old screen was

One page carrying, top to bottom: a full-bleed video frame taking twelve taps,
an instruction line, a schematic court legend, Undo and Clear, a three-option
metric list with a per-option time cost, a total estimate, and two action
buttons. On a portrait video with a metric selector open, the frame collapsed
to nothing and the second button was clipped under the navigation bar. That bug
was fixed by weighting the regions against each other; the crowding it came
from was not.

## The split

| Step | Question | The button that answers it |
| --- | --- | --- |
| 1, "Court mapping" | Where is the court in this frame? | Continue |
| 2, "Analysis" | What should this run produce? | Analyse in cloud / Analyse on device |

Two steps of **one route**, not two destinations. Three things argued for it:

1. **The keypoints, the frame count and the frame rate all come from the same
   decoded frame.** `loadFirstFrame` reads them together and step two prices its
   estimate in frames. A second destination would re-decode the video with
   `MediaMetadataRetriever`, carry three arguments through the back stack, or
   share a ViewModel across back-stack entries.
2. **The pop contract.** `AuthGate`'s `Route.CourtMarking` ends a started
   analysis with a single `popBackStack()`, and the comment above it explains
   why naming a destination is wrong there: three screens navigate in, and the
   coach must land back on whichever one they came from. A second route makes
   that two pops.
3. **Persisting to bridge them would be a behaviour change.** Keypoints are
   written to the local video entry only on the device branch today; writing
   them on Continue would store marks for a coach who backs out without running
   anything, smuggled in as plumbing.

`rememberSaveable` holds the step, so it survives rotation and process death.
The bar's back arrow and the system back gesture both unwind step two into step
one before leaving the screen, and the twelve points survive the trip.

The step that is drawn is **derived**, not the saved one: the saved step is
capped by whether the marking is actually complete. The saved-state bundle
outlives the process and `CourtMarkingViewModel` does not - it has no
`SavedStateHandle` - so a phone that reclaims the process while step two is open
restores the step onto an empty marking. Trusting the saved value there would
draw both Analyse buttons over zero points, and either one would take
`toCourtKeypoints()`'s `check(isComplete)` straight to a crash. The old screen
was immune to this because its buttons lived inside `if (marking.isComplete)`;
the split moved that gate into state that persists on a different schedule.
Verified with the process actually killed from the background, which comes back
on step one with 0 / 12 and Continue disabled.

## What the mock says, and where it is not followed

Followed: the two-line header with a step label under the title and a pair of
progress bars in the actions; the rounded video frame at the page margin; the
placement progress bar under the count; option cards with the accent around the
chosen one and a filled check; the page's own 28sp heading on step two; the
buttons pinned at the foot with the leftover height collecting above them.

Not followed, and why:

- **"Place the court points" as the step-one heading.** The mock loses which
  landmark is next. Twelve landmarks include "Service Near-Left" and "Service
  Far-Left"; the screen keeps naming the next one, in that point's own colour,
  and gains the mock's progress bar and count beside it.
- **"Reset points" alone.** The mock's single reset means clearing all twelve to
  fix the ninth. Undo and Clear both stay.
- **The mock's per-option minutes (1, 2, 3).** Placeholder numbers. The real
  costs come from `estimateAnalysis` against this phone's measured throughput,
  including the case where a second pose option costs nothing because the pose
  pass is already running.
- **The mock's hex values.** Dark-only; `#3EE27C` is 1.6:1 on white. Everything
  resolves through the colour scheme and `ShuttlTheme.extended`, so both themes
  ship.
- **One list of options over two buttons, with no caveat.** The estimate and the
  choice price the on-device run; a cloud run decides its own stages. Step two
  says so in a line rather than implying a control that does not exist.

## Layout

Step one is three regions in a column: the frame, the guidance, and the button.
The frame takes the height its aspect ratio asks for and no more, capped at 62%
of the region so a portrait video cannot squeeze the guidance out. The guidance
takes what is left and scrolls. The button is outside both, so nothing can push
it off screen - the bug the previous weighting existed to fix, kept.

Leftover height collects between the guidance and the button rather than being
split above and below a centred frame, which is what the mock's own
`margin-top: auto` does and what Home already does with its hero and its two
pills.

Step two has no frame, so it is a plain scrolling column with the two actions
pinned under it.

## Fixed on the way past

`MetricSelector` told a coach that rally clips took "no extra time, the pose
pass is already running" whenever the marginal cost rounded under a second -
including on a short video with no pose option selected at all, where no pose
pass was running. Only a pose option can be free for that reason; anything else
under a second now says "no measurable extra time".

## Not done

**iOS.** `CourtMarkingView` has no metric selection and no on-device target -
the local inference engine is Android-only until Stage 2 of
`docs/plans/2026-08-31-on-device-analysis-pipeline-design.md`. Its step two
would hold one button and no question, which is not a page. It follows when
iOS has something to ask on it.
