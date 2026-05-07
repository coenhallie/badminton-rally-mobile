# Frame-step Buttons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add prev/next frame buttons below the rally video on `ClipDetailScreen`. A tap moves one frame; holding repeats continuously after a 400 ms delay at ~60 ms cadence. Buttons appear below the player in portrait and as a bottom-center overlay in fullscreen.

**Architecture:** A new self-contained `FrameStepBar` Compose composable operates directly on the existing `ExoPlayer` instance. No `ClipDetailViewModel` changes. `ClipDetailScreen` adds two call sites and configures the player for exact seeking.

**Tech Stack:** Jetpack Compose Material 3, AndroidX Media3 (ExoPlayer 1.5.1), Kotlin coroutines.

**Design doc:** [`docs/plans/2026-05-07-frame-step-buttons-design.md`](./2026-05-07-frame-step-buttons-design.md)

---

## Conventions

- All gradle commands run from the repo root.
- Build/test runners:
  - Android compile: `./gradlew :androidApp:compileDebugKotlin`
  - Android assemble: `./gradlew :androidApp:assembleDebug`
  - Android unit tests (sanity, unrelated): `./gradlew :androidApp:testDebugUnitTest`
- No new automated tests — gesture handling and a single-line `seekTo` are out of scope for unit tests under the current pattern in this repo. Manual verification at the end.
- Commit style mirrors recent history (`feat(...)`, `docs(...)`, etc.) with the Co-Authored-By trailer used in prior commits.

---

## File Structure

- **Create:** `androidApp/src/main/java/com/badmintontracker/android/clipdetail/FrameStepBar.kt`
  - Public `@Composable fun FrameStepBar(player: ExoPlayer, modifier: Modifier, backgroundColor: Color)`
  - Private `@Composable fun RepeatingIconButton(...)` for the press-and-hold gesture.
- **Modify:** `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`
  - Configure `SeekParameters.EXACT` on the `ExoPlayer` at construction.
  - Render `FrameStepBar` in the portrait `Column` after `playerSurface(...)`.
  - Render `FrameStepBar` in the fullscreen `Box` aligned bottom-center.

---

## Task 1: Create the FrameStepBar composable

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/FrameStepBar.kt`

- [ ] **Step 1: Create `FrameStepBar.kt` with both composables**

Write this exact content to `androidApp/src/main/java/com/badmintontracker/android/clipdetail/FrameStepBar.kt`:

```kotlin
package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun FrameStepBar(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val step: (Int) -> Unit = { dir ->
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        val frameMs = (1000f / fps).toLong().coerceAtLeast(1L)
        val maxPos = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + dir * frameMs).coerceIn(0L, maxPos)
        if (player.isPlaying) player.pause()
        player.seekTo(target)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = backgroundColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RepeatingIconButton(onStep = { step(-1) }) {
                Icon(Icons.Filled.FastRewind, contentDescription = "Previous frame")
            }
            RepeatingIconButton(onStep = { step(+1) }) {
                Icon(Icons.Filled.FastForward, contentDescription = "Next frame")
            }
        }
    }
}

@Composable
private fun RepeatingIconButton(
    onStep: () -> Unit,
    initialDelayMs: Long = 400L,
    repeatPeriodMs: Long = 60L,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier.pointerInput(onStep) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    val job = scope.launch {
                        onStep()
                        delay(initialDelayMs)
                        while (isActive) {
                            onStep()
                            delay(repeatPeriodMs)
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                }
            }
        },
    ) {
        // onClick is intentionally a no-op: gesture is fully handled in the
        // outer Box's pointerInput. IconButton still provides ripple, sizing,
        // and accessibility semantics.
        IconButton(onClick = {}) {
            content()
        }
    }
}
```

Notes for the implementer:

- The outer `Box`'s `pointerInput` runs in parallel with the inner `IconButton`'s built-in click handling. We do not consume the down event, so `IconButton`'s clickable still fires its ripple animation. Its `onClick` is a no-op so the click does not double-step.
- `step` reads `videoFormat.frameRate` on every invocation — this is intentional. It is `null` until the first video frame is rendered; falling back to 30 fps before that is acceptable.
- `player.duration` returns `C.TIME_UNSET` (a negative value) for unprepared/live media; `takeIf { it > 0L }` falls through to `Long.MAX_VALUE` so `seekTo` is harmless.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. No unresolved references, no warnings about the new file.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/FrameStepBar.kt
git commit -m "$(cat <<'EOF'
feat(clipdetail): FrameStepBar composable with hold-to-repeat

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Wire FrameStepBar into ClipDetailScreen

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

- [ ] **Step 1: Add the SeekParameters import**

Open `ClipDetailScreen.kt`. In the import block (alphabetically with the other `androidx.media3` imports), add:

```kotlin
import androidx.media3.exoplayer.SeekParameters
```

The full media3 import block should now be:

```kotlin
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
```

- [ ] **Step 2: Configure the player for exact seeking**

In `ClipDetailScreen`, replace the current player construction:

```kotlin
val player = remember { ExoPlayer.Builder(ctx).build() }
```

with:

```kotlin
val player = remember {
    ExoPlayer.Builder(ctx).build().apply {
        setSeekParameters(SeekParameters.EXACT)
    }
}
```

This makes `seekTo(targetMs)` land on the requested position rather than the nearest sync sample, which is required for accurate single-frame stepping.

- [ ] **Step 3: Add FrameStepBar to the portrait layout**

Find the `Column` inside the `Scaffold` content lambda. The current code is:

```kotlin
Column(modifier = Modifier.padding(padding).fillMaxSize()) {
    if (!isFullscreen) {
        playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    }

    if (state.annotations.isEmpty()) {
```

Replace it with:

```kotlin
Column(modifier = Modifier.padding(padding).fillMaxSize()) {
    if (!isFullscreen) {
        playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        FrameStepBar(
            player = player,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp),
        )
    }

    if (state.annotations.isEmpty()) {
```

The `Modifier.align(Alignment.CenterHorizontally)` here is a `ColumnScope` extension that takes an `Alignment.Horizontal`; `Alignment.CenterHorizontally` is the correct value. `Alignment` is already imported.

- [ ] **Step 4: Add FrameStepBar to the fullscreen overlay**

Find the fullscreen branch — currently:

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

Replace it with:

```kotlin
if (isFullscreen) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        playerSurface(Modifier.fillMaxSize())
        FrameStepBar(
            player = player,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            backgroundColor = Color.Black.copy(alpha = 0.4f),
        )
    }
}
```

`Modifier.align(Alignment.BottomCenter)` is the `BoxScope` extension that takes `Alignment`. `Color.Black` and `Alignment` are already imported.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. No unresolved references.

- [ ] **Step 6: Run the existing unit tests as a sanity check**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. None of these tests cover the changed code, so this is purely a regression check.

- [ ] **Step 7: Build a debug APK**

Run: `./gradlew :androidApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`. APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "$(cat <<'EOF'
feat(clipdetail): wire FrameStepBar into portrait and fullscreen layouts

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Manual verification

This task is the source of truth for "works" — there are no automated tests. Run all checks on a real device or emulator before declaring done. **Do not mark this task complete on build success alone.**

**Setup:**
- Install the debug APK on a device or emulator.
- Open a match clip with at least a few seconds of decoded video. Wait for the first frame to render before testing (so `videoFormat.frameRate` is populated).

- [ ] **Check 1: Single tap = exactly one frame, in both directions**

  - With the video paused at a recognizable frame, tap the right (Next frame) button once. Position should advance by one frame's worth of time (33 ms at 30 fps; ~17 ms at 60 fps). Visual change should be subtle but present.
  - Tap the left (Previous frame) button once. Position should return to the original frame.

- [ ] **Check 2: Tap during playback pauses, then steps**

  - Start playback. While playing, tap Next frame. The video must pause and advance one frame in a single user action.

- [ ] **Check 3: Hold-to-repeat cadence feels right**

  - Press and hold Next frame. For ~400 ms nothing additional happens (just the initial step). After that, the video should advance continuously at roughly 16 frames per second (one step every 60 ms).
  - Release. Stepping must stop immediately — no trailing steps.
  - Repeat with Previous frame.

- [ ] **Check 4: Drag-off cancels the hold**

  - Press and hold Next frame, then drag your finger off the button without releasing. Stepping must stop.

- [ ] **Check 5: Boundary clamping does not crash**

  - Seek to position 0 via the player's scrub bar. Tap Previous frame several times. No crash; position stays at 0.
  - Seek to the end of the clip. Tap Next frame several times. No crash; position stays near duration.

- [ ] **Check 6: Portrait placement looks right**

  - In portrait, the bar sits centered horizontally directly below the 16:9 player, with comfortable spacing above the annotations list. It does not overlap the player or push the list off-screen.

- [ ] **Check 7: Fullscreen placement looks right**

  - Rotate to landscape (or tap the player's fullscreen button). The bar appears at bottom-center over the video with a translucent dark background. It does not block the central play/pause control.
  - Rotate back to portrait. The bar reappears below the player and the fullscreen overlay disappears.

- [ ] **Check 8: Rotation while holding does not leak the coroutine**

  - Hold Next frame and rotate the device mid-hold. The repeat coroutine should cancel cleanly with no crash. (The composable scope dies on the orientation-change recomposition path.)

- [ ] **Check 9: Visual feedback on press**

  - Each button shows the standard ripple on press. No press = no ripple lingering after release.

If any check fails, fix the implementation, re-run `./gradlew :androidApp:compileDebugKotlin && ./gradlew :androidApp:assembleDebug`, reinstall, and re-verify before declaring the task complete.

---

## Self-review notes

- **Spec coverage**
  - Step semantics + `SeekParameters.EXACT` → Task 1 Step 1 (the `step` lambda) and Task 2 Step 2.
  - Pause-on-step → Task 1 Step 1 (`if (player.isPlaying) player.pause()`).
  - Tap vs. hold (initial step + 400 ms delay + 60 ms repeat) → Task 1 Step 1 (`RepeatingIconButton`).
  - Portrait placement → Task 2 Step 3.
  - Fullscreen placement → Task 2 Step 4.
  - Edge cases (videoFormat null, duration <= 0, drag-off, rotation) → Task 1 Step 1 + Task 3 Checks 4, 5, 8.
  - Manual verification cases → Task 3 Checks 1–7 mirror the design's testing list.
- **Placeholder scan:** No TBDs, no "add appropriate error handling", every code step shows the actual code.
- **Type consistency:** `FrameStepBar(player, modifier, backgroundColor)` — same signature in Task 1 (definition) and Task 2 Steps 3 & 4 (call sites). `RepeatingIconButton(onStep, initialDelayMs, repeatPeriodMs, content)` is private and only called internally.
