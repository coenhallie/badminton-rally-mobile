# Frame-step buttons on rally clip detail — Design

Date: 2026-05-07
Status: Draft for review

## Goal

Let the user advance or rewind the rally video by exactly one frame at a time
on the `ClipDetailScreen`. A tap moves one frame; a hold continuously steps
until release.

## Scope

- `ClipDetailScreen.kt` (the only screen with a video player today).
- New composable `FrameStepBar` in
  `androidApp/src/main/java/com/badmintontracker/android/clipdetail/`.
- No `ClipDetailViewModel` changes — frame stepping is a UI-only concern that
  operates directly on the existing `ExoPlayer`.

Out of scope: keyboard/remote stepping, frame-accurate scrubbing on the
existing PlayerView seek bar, frame stepping anywhere else in the app.

## Behavior

### Step semantics

- Step size derives from the loaded video's frame rate, read on each step:
  ```
  val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
  val frameMs = (1000f / fps).toLong().coerceAtLeast(1L)
  ```
- Stepping uses exact seeking: configure
  `player.setSeekParameters(SeekParameters.EXACT)` once when the bar
  is first attached, so each step lands on the requested position rather than
  the nearest sync sample.
- Target position is clamped:
  ```
  val maxPos = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
  val target = (player.currentPosition + dir * frameMs).coerceIn(0L, maxPos)
  player.seekTo(target)
  ```
  At the boundaries the step is a no-op; the button still ripples normally.

### Playback state

- If `player.isPlaying` when a step fires, call `player.pause()` first, then
  seek. The pause+seek pair is one logical action so a tap that pauses still
  advances exactly one frame.
- After stepping the player stays paused. The user resumes via the existing
  `PlayerView` controls.

### Tap vs. hold

A `RepeatingIconButton` composable owns the gesture:

1. On `awaitFirstDown`: fire one step immediately (so a tap = one frame).
2. Launch a coroutine that `delay(400)` then loops `step(); delay(60)` until
   the pointer goes up or the gesture is cancelled.
3. `waitForUpOrCancellation` returning (null or otherwise) cancels the
   coroutine.

The coroutine uses `rememberCoroutineScope()`, so it dies with the composable
on dispose / orientation change.

### Placement

- **Portrait** (`!isFullscreen`): inserted in the existing `Column` between
  `playerSurface(...)` and the annotation list. A `Surface` (tonal,
  `RoundedCornerShape(50)`), a centered `Row` with two `IconButton`s,
  `Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)`.
- **Fullscreen**: inside the existing `Box(Modifier.fillMaxSize())`, the same
  bar is added with `Modifier.align(Alignment.BottomCenter).padding(bottom =
  24.dp)`. In this placement the `Surface` uses
  `color = Color.Black.copy(alpha = 0.4f)` so it stays legible over the video.

Icons: `Icons.Filled.FastRewind` (prev) and `Icons.Filled.FastForward` (next).
Content descriptions: `"Previous frame"`, `"Next frame"`.

## Components

```
FrameStepBar(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    onSurface: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
)

private RepeatingIconButton(
    onStep: () -> Unit,
    initialDelayMs: Long = 400,
    repeatPeriodMs: Long = 60,
    content: @Composable () -> Unit,
)
```

`FrameStepBar` owns the step function and passes it to two
`RepeatingIconButton`s with `dir = -1` and `dir = +1`.

## Edge cases

- `videoFormat == null` (still loading): falls back to 30 fps. Acceptable —
  user is unlikely to be hammering frame buttons before the first frame is
  decoded.
- `player.duration <= 0` (live/unknown): upper bound becomes `Long.MAX_VALUE`;
  `seekTo` past EOS is harmless.
- Pointer drag off the button: `waitForUpOrCancellation` returns null →
  coroutine cancels → repeat stops.
- Configuration change (rotation between portrait/fullscreen): the bar is part
  of two separate composable scopes; its coroutine is tied to the current
  scope and cancels on dispose. The shared `ExoPlayer` instance is unaffected.

## Testing

- No new unit tests. `ClipDetailViewModel` is untouched; the new logic is
  gesture-handling + a one-line seek.
- Manual verification before merge:
  1. Tap each button once → video pauses (if playing) and advances/rewinds
     by exactly one frame.
  2. Hold each button → ~400 ms delay, then continuous stepping at ~60 ms
     cadence. Release stops immediately.
  3. Step backwards at position 0 / forwards at end-of-clip → no crash, no
     wrap-around.
  4. Rotate to landscape → bar appears as bottom overlay, still functional.
  5. Rotate back → bar appears below the player.

## Files touched

- New: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/FrameStepBar.kt`
- Modified: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`
  - Call `player.setSeekParameters(SeekParameters.EXACT)` after the
    `ExoPlayer.Builder(ctx).build()` line.
  - Insert `FrameStepBar(player, ...)` after `playerSurface(...)` in the
    portrait `Column`.
  - Insert `FrameStepBar(player, ...)` aligned bottom-center inside the
    fullscreen `Box`.
