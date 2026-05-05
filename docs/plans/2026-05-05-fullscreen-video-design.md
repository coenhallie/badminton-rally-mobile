# Fullscreen video playback — design

**Date:** 2026-05-05
**Scope:** `ClipDetailScreen` only. Android app.

## Goal

Let users watch rally clips fullscreen for detailed review. Trigger via the
PlayerView fullscreen button **and** automatically on device rotation. Fullscreen
shows video only — no annotations overlay (deferred).

## Approach

A single `ExoPlayer` instance, hoisted in Compose, drives one `PlayerView`. A
`var isFullscreen by remember { mutableStateOf(false) }` decides whether
`PlayerView` is hosted inside the existing 16:9 box or in a window-filling
overlay above the rest of the UI. The same view is reused, so playback state,
buffer, and listeners survive transitions for free.

Rejected alternative: a separate `FullscreenPlayerActivity`. It would require
serializing the signed URL + current position, recreating the player, and
re-attaching listeners — more code, more failure modes, and a flicker on
launch.

## Components

### `ClipDetailScreen.kt`

- New state: `var isFullscreen by remember { mutableStateOf(false) }`.
- New state: `val activity = LocalContext.current as Activity` (cast assumed
  safe — only used from this screen).
- `PlayerView` configured once in the `factory` with
  `setFullscreenButtonClickListener { isFullscreen = !isFullscreen }`. On every
  recomposition, call `setFullscreenButtonState(isFullscreen)` via the `update`
  block of `AndroidView` so the icon reflects current state.
- The `Box(... aspectRatio(16:9))` keeps the `AndroidView` only when
  `!isFullscreen`. When fullscreen, the same `AndroidView` is rendered inside a
  top-level `Box(Modifier.fillMaxSize().background(Color.Black))` painted over
  the Scaffold content. The error `Surface` follows the same Box so retry stays
  reachable in fullscreen.
- `Scaffold` `topBar` and `floatingActionButton` are emitted only when
  `!isFullscreen`.
- `BackHandler(enabled = isFullscreen) { isFullscreen = false }` so the system
  back button exits fullscreen before leaving the screen.

### Rotation → fullscreen sync

A `LaunchedEffect(LocalConfiguration.current.orientation)` reads the current
orientation and sets `isFullscreen` to match:

- `Configuration.ORIENTATION_LANDSCAPE` → `isFullscreen = true`
- `Configuration.ORIENTATION_PORTRAIT` → `isFullscreen = false`

This makes rotation the source of truth when the user rotates the physical
device. The button override below temporarily forces orientation, which fires
the same effect and keeps state consistent.

### Orientation lock on button toggle

Wrapped in a `LaunchedEffect(isFullscreen)`:

- `isFullscreen = true` → `activity.requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE`
- `isFullscreen = false` → `activity.requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED`

`SENSOR_LANDSCAPE` (rather than plain `LANDSCAPE`) lets the user flip between
landscape-left and landscape-right while fullscreen.

### System bars

In the same `LaunchedEffect(isFullscreen)`:

- Resolve `WindowInsetsControllerCompat(activity.window, activity.window.decorView)`
- On enter: hide `statusBars()` + `navigationBars()`,
  `systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.
- On exit: show them again.

### Cleanup on screen leave

The existing `DisposableEffect(player) { ... player.release() }` is extended to
also restore orientation (`SCREEN_ORIENTATION_UNSPECIFIED`) and re-show system
bars in `onDispose`. This protects against navigating away mid-fullscreen and
landing on the next screen sideways with no status bar.

### Manifest

`AndroidManifest.xml` `MainActivity` gets:

```
android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
```

This is the standard pattern for media apps: the system delivers a config
change to the running activity instead of recreating it. Without this,
rotating the device would tear down `ExoPlayer` and lose playback position.
The Compose tree observes `LocalConfiguration` and recomposes, so this change
is invisible to other screens.

## Data flow

```
[Device sensor or fullscreen button]
        |
        v
  isFullscreen state -----> PlayerView host (16:9 box vs full-bleed overlay)
        |                      ^
        |                      |
        +----> requestedOrientation (force landscape / unlock)
        |
        +----> WindowInsetsController (hide / show bars)
        |
        +----> Scaffold (hide TopAppBar + FAB when true)
        |
        +----> BackHandler (intercept back when true)
```

## Error handling

- The existing `state.error` overlay (retry button) renders inside whichever
  Box currently hosts the player, so it stays visible in fullscreen.
- If casting `LocalContext` to `Activity` ever fails (unlikely — this screen
  always lives in `MainActivity`), the orientation/system-bar effects no-op.
  We'll cast with `as? Activity` and gate the side effects on non-null.

## Testing

- Manual on a real device:
  - Tap the fullscreen icon in portrait → device flips to landscape, bars hide,
    TopAppBar + FAB gone, video keeps playing without rebuffer.
  - Tap exit icon → returns to portrait + 16:9 box, bars back, controls back.
  - Rotate the device to landscape with no button press → same fullscreen
    state.
  - Rotate back to portrait → exits fullscreen.
  - Press system back in fullscreen → exits fullscreen, does not pop screen.
  - Press system back again → pops to clip list.
  - Trigger a playback error in fullscreen → retry overlay visible and works.
  - Navigate away mid-fullscreen → next screen renders portrait with bars.
- Unit tests: none — all behavior is Compose / Android view layer wiring.
  `ClipDetailViewModel` is unchanged.

## Out of scope

- Annotation overlay in fullscreen (deferred, may follow as option 2 from
  brainstorm).
- Tablet / foldable multi-window behavior beyond defaults.
- Picture-in-picture.
