# Fullscreen video Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let users watch rally clips fullscreen on `ClipDetailScreen`, triggered by the PlayerView fullscreen button or device rotation, with system bars hidden.

**Architecture:** Single Compose screen, single `ExoPlayer`, single `PlayerView`. A new `isFullscreen` state flips between two host containers: the existing 16:9 `Box` and a new full-bleed `Box` over the Scaffold. The same view instance is reused, so playback survives transitions for free. Orientation lock, system bars, back handling, and the FAB/top bar are gated on the same state. `MainActivity` gets `configChanges` so rotation does not recreate the activity (which would tear down `ExoPlayer`).

**Tech Stack:** Jetpack Compose, AndroidX Activity, Media3 1.5.1 (`PlayerView` + `ExoPlayer`), `WindowInsetsControllerCompat`.

**Reference:** Design doc at `docs/plans/2026-05-05-fullscreen-video-design.md`.

**No new unit tests.** All changes are Android view-layer wiring on a screen with no existing UI tests; `ClipDetailViewModel` is untouched. Verification is by build + manual smoke test (Task 8).

---

## Task 1: Manifest configChanges so rotation does not recreate the activity

**Why first:** every later task assumes rotation does not destroy `ExoPlayer`. If we skip this, every other task appears to work in isolation but loses playback position the first time the device rotates.

**Files:**
- Modify: `androidApp/src/main/AndroidManifest.xml`

**Step 1: Add `configChanges` to the `MainActivity` element**

Open `androidApp/src/main/AndroidManifest.xml` and change the `<activity>` element from:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:theme="@android:style/Theme.Material.Light.NoActionBar">
```

to:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
    android:theme="@android:style/Theme.Material.Light.NoActionBar">
```

**Step 2: Build the app to confirm the manifest is valid**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

**Step 3: Commit**

```bash
git add androidApp/src/main/AndroidManifest.xml
git commit -m "feat(androidApp): keep MainActivity alive across config changes"
```

---

## Task 2: Add `isFullscreen` state and wire the PlayerView fullscreen button

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Add new imports**

In `ClipDetailScreen.kt`, add these imports next to the existing ones:

```kotlin
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
```

(`mutableStateOf`/`remember` are already imported — only add what's missing.) Also add:

```kotlin
import androidx.media3.ui.PlayerView
```

(already there) and no other new imports yet.

**Step 2: Introduce the state**

Inside `ClipDetailScreen`, just under the existing `var pendingDelete by ...` line, add:

```kotlin
var isFullscreen by remember { mutableStateOf(false) }
```

**Step 3: Wire the button listener and keep the icon in sync**

Find the existing `AndroidView` call inside the 16:9 `Box` (currently `factory = { PlayerView(ctx).apply { this.player = player } }`) and replace it with a stub that we will move in the next task. For now, change it to:

```kotlin
AndroidView(
    factory = { c ->
        PlayerView(c).apply {
            this.player = player
            setFullscreenButtonClickListener { isFullscreen = !isFullscreen }
        }
    },
    update = { it.setFullscreenButtonState(isFullscreen) },
    modifier = Modifier.fillMaxSize(),
)
```

**Step 4: Build**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. (The fullscreen button now toggles `isFullscreen`, but nothing visual changes yet — that comes in Task 3.)

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "feat(clipDetail): add isFullscreen state wired to PlayerView button"
```

---

## Task 3: Render PlayerView in a fullscreen overlay and hide the chrome

This task does the visual half of fullscreen: hosting the `PlayerView` in a window-filling `Box` while hiding the `TopAppBar` and `FloatingActionButton`. Orientation and system bars come in the next tasks.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Add imports**

```kotlin
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
```

**Step 2: Hoist the player UI into a reusable composable**

Inside the `ClipDetailScreen` composable, above the `Scaffold(...)` call, define a local function that takes a `Modifier` and renders the `AndroidView` plus the existing error overlay:

```kotlin
@Composable
fun playerSurface(modifier: Modifier) {
    Box(modifier = modifier) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    setFullscreenButtonClickListener { isFullscreen = !isFullscreen }
                }
            },
            update = { it.setFullscreenButtonState(isFullscreen) },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.error != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    ShuttlButton(
                        text = "Retry",
                        onClick = vm::onManualRetry,
                        variant = ShuttlButtonVariant.Primary,
                    )
                }
            }
        }
    }
}
```

Note: `@Composable` local functions capture surrounding state (`player`, `state`, `vm`, `isFullscreen`) — this is the idiomatic way to share Compose UI inside a screen.

**Step 3: Replace the inline 16:9 player block with a call to `playerSurface`**

Inside the existing `Column` body of the Scaffold's content lambda, replace the existing `Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { ... }` block (which contains the `AndroidView` and the error `Surface`) with:

```kotlin
if (!isFullscreen) {
    playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
}
```

**Step 4: Hide the TopAppBar and FAB in fullscreen**

In the `Scaffold(...)` call, change `topBar = { TopAppBar(...) }` to:

```kotlin
topBar = {
    if (!isFullscreen) {
        TopAppBar(
            title = { Text(state.clip?.title ?: state.clip?.let { "Rally #${it.rallyIndex}" } ?: "") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }
},
```

And change the `floatingActionButton` block from the current `if (state.clip != null) { FloatingActionButton(...) }` to:

```kotlin
floatingActionButton = {
    if (!isFullscreen && state.clip != null) {
        FloatingActionButton(onClick = {
            val ms = player.currentPosition.coerceAtLeast(0L)
            addDialog = ms / 1000f
        }) {
            Icon(Icons.Default.Add, contentDescription = "Add annotation")
        }
    }
},
```

**Step 5: Render the fullscreen overlay**

After the closing `}` of the entire `Scaffold(...)` call (i.e., still inside `ClipDetailScreen` but outside the Scaffold), and before the `addDialog?.let { ... }` block, add:

```kotlin
if (isFullscreen) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        playerSurface(Modifier.fillMaxSize())
    }
}
```

**Step 6: Build and install on a device**

Run: `./gradlew :androidApp:installDebug`
Expected: `BUILD SUCCESSFUL`. Open a clip, tap the fullscreen icon on the player → player should now fill the screen, top bar and FAB gone. Tap the icon again → returns to 16:9. (Orientation and system bars will still be portrait/visible — fixed in Task 5.)

**Step 7: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "feat(clipDetail): host PlayerView in full-bleed overlay in fullscreen"
```

---

## Task 4: Back button exits fullscreen before leaving the screen

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Add import**

```kotlin
import androidx.activity.compose.BackHandler
```

**Step 2: Add the handler**

Just below the `var isFullscreen by ...` line, add:

```kotlin
BackHandler(enabled = isFullscreen) { isFullscreen = false }
```

**Step 3: Build and verify on device**

Run: `./gradlew :androidApp:installDebug`
Expected: `BUILD SUCCESSFUL`. Enter fullscreen, press system back → returns to 16:9 view, screen does not pop. Press back again → screen pops to clip list.

**Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "feat(clipDetail): back exits fullscreen before leaving the screen"
```

---

## Task 5: Force landscape and hide system bars while fullscreen

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Add imports**

```kotlin
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
```

**Step 2: Resolve the hosting activity**

Just below the `val ctx = LocalContext.current` line, add:

```kotlin
val activity = ctx as? Activity
```

(Defensive: if the cast ever fails, the side effects below will no-op rather than crash.)

**Step 3: Add the side effect**

Just below the `BackHandler(...)` line added in Task 4, add:

```kotlin
LaunchedEffect(isFullscreen, activity) {
    val a = activity ?: return@LaunchedEffect
    val controller = WindowCompat.getInsetsController(a.window, a.window.decorView)
    if (isFullscreen) {
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
```

**Step 4: Build and verify on device**

Run: `./gradlew :androidApp:installDebug`
Expected: `BUILD SUCCESSFUL`. Tap fullscreen → device flips to landscape, status + nav bars hide, video continues without rebuffer. Tap exit → returns to portrait with bars visible.

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "feat(clipDetail): force landscape and hide system bars in fullscreen"
```

---

## Task 6: Auto-enter fullscreen when the device rotates to landscape

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Add imports**

```kotlin
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
```

**Step 2: Read the current orientation**

Just below the `val activity = ctx as? Activity` line, add:

```kotlin
val orientation = LocalConfiguration.current.orientation
```

**Step 3: Sync `isFullscreen` with orientation**

Just below the `LaunchedEffect(isFullscreen, activity) { ... }` block from Task 5, add:

```kotlin
LaunchedEffect(orientation) {
    isFullscreen = (orientation == Configuration.ORIENTATION_LANDSCAPE)
}
```

This works in both directions: rotating the device flips the state, and the Task 5 effect reacts to the new state. When the user taps the in-player button, that effect forces the orientation, which fires this effect with the new value — no oscillation, because the boolean is already in the desired state.

**Step 4: Build and verify on device**

Run: `./gradlew :androidApp:installDebug`
Expected: `BUILD SUCCESSFUL`. With the device unlocked from portrait (use system Auto-rotate on), rotate the phone to landscape while on the clip detail screen → automatically enters fullscreen. Rotate to portrait → exits fullscreen.

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "feat(clipDetail): auto-enter fullscreen on landscape rotation"
```

---

## Task 7: Restore orientation and system bars when leaving the screen

Without this, navigating away mid-fullscreen leaves the next screen sideways with no status bar.

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Extend the existing `DisposableEffect(player)` cleanup**

Find the current block:

```kotlin
DisposableEffect(player) {
    val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { vm.onPlayerError() }
    }
    player.addListener(listener)
    onDispose {
        player.removeListener(listener)
        player.release()
    }
}
```

Replace it with:

```kotlin
DisposableEffect(player) {
    val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { vm.onPlayerError() }
    }
    player.addListener(listener)
    onDispose {
        player.removeListener(listener)
        player.release()
        activity?.let { a ->
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(a.window, a.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
```

**Step 2: Build and verify on device**

Run: `./gradlew :androidApp:installDebug`
Expected: `BUILD SUCCESSFUL`. Enter fullscreen, then press the in-app `Back` arrow (top bar) — wait, that's hidden in fullscreen. Instead: enter fullscreen, press system back twice (first exits fullscreen, second pops). Then test the harder path: enter fullscreen via rotation, navigate away by some other means if possible, or just verify that exiting via system back leaves the next screen with portrait + bars. The clip list should be portrait with status/nav bars visible.

**Step 3: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "feat(clipDetail): restore orientation and bars when leaving screen"
```

---

## Task 8: Manual smoke test

No code changes — just walk through the full feature on a real device and confirm everything works together. If a step fails, file a bug rather than patching ad hoc; the design assumes each previous task is complete and correct.

**Files:** none.

**Step 1: Install latest debug build**

Run: `./gradlew :androidApp:installDebug`
Expected: `BUILD SUCCESSFUL`.

**Step 2: Run the checklist**

On a real device with system Auto-rotate enabled, open a clip and verify each:

- [ ] Tap the fullscreen icon on the player in portrait → flips to landscape, bars hide, top bar + FAB gone, video keeps playing without a visible rebuffer (no spinner).
- [ ] Tap the fullscreen icon again → returns to portrait + 16:9 box, bars and chrome back, video keeps playing.
- [ ] Rotate the device to landscape with no button press → enters fullscreen.
- [ ] Rotate back to portrait → exits fullscreen.
- [ ] In fullscreen, press system back → exits fullscreen, screen does not pop.
- [ ] Press system back again → pops to the clip list.
- [ ] Force a network error (turn off Wi-Fi + mobile data, then trigger retry / scrub) while fullscreen → red retry overlay is visible and the Retry button works.
- [ ] Add an annotation in portrait, enter fullscreen, exit, verify the annotation is still in the list (i.e., we did not lose state).
- [ ] Navigate away from the clip detail screen via the back arrow (in portrait) — clip list should still render correctly, portrait, with bars.

**Step 3: If everything passes, no commit needed.** If something fails, fix it as a follow-up commit referencing the failing checklist item.

---

## Notes for the executor

- All tasks except Task 1 modify the same file. Resist the urge to combine them — each commit should be reviewable on its own and leave the app in a working state.
- Do not add unit tests for these changes. There is no UI test infrastructure for screens in this project, and the `ClipDetailViewModel` is intentionally untouched.
- When in doubt about Compose recomposition or PlayerView callbacks, prefer the smallest possible change and verify on a device — the design doc is the source of truth.
