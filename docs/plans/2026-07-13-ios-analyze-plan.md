# iOS Analyze Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Full analyze parity on iOS — shared `AnalyzeCoordinator` + court-marking geometry promoted to commonMain, an `NSFileHandle`-streaming upload channel, the tap-in-order court-marking screen, and the analyze progress/result UI — bumping to 0.4.0 (12).

**Architecture:** Fourth and fifth shared promotions in the established pattern: `AnalyzeCoordinator` (one `synchronizedSet` → `SyncLock` swap; injected `openChannel`/`log`/`scope` seams unchanged) and the already-pure `CourtMarkingModel`. iOS supplies platform glue via an `iosMain` factory (file-streaming channel with resume offset, Darwin logging) and native SwiftUI for court marking and analyze surfaces, reusing the shared state machine and geometry verbatim.

**Tech Stack:** Kotlin 2.3.20 KMP (ktor ByteChannel, kotlinx-coroutines, supabase-kt TUS), SwiftUI iOS 17 (Canvas, MagnifyGesture), AVFoundation (AVAssetImageGenerator), XCTest, kotest/coroutines-test in commonTest, Kotlin/Native iosTest.

**Spec:** `docs/plans/2026-07-13-ios-analyze-design.md`

## Global Constraints

- Coordinator behavior byte-identical: step order `UPLOAD → CREATE_ROW → KEYPOINTS → TRIGGER → PROCESSING` with ordinal `startFrom` comparisons; retry resumes from persisted `failedStep`; reattach maps PROCESSING→PROCESSING, UPLOADING→UPLOAD; duplicate-key create errors swallowed; failure sets `resultSeen = false`; success removes the entry unless it has local annotations (then stage ANALYZED); `clips.refresh()` + clip-count check on success.
- Error copy verbatim: "No court points saved", "Couldn't register video", "Couldn't save court points", "Couldn't start analysis", "Analysis failed", "Analysis finished but found no rallies in this video.", iOS channel-open failure "Video file is missing or access was revoked".
- Status text verbatim (note the `…` ellipsis): "Uploading N%…"/"Uploading…", "Analyzing N%…"/"Analyzing…", "Analyzed"; LOCAL and FAILED show none; `canAnalyze` = LOCAL or FAILED.
- Result dialog verbatim: title "No rallies found" when the message contains "no rallies" (case-insensitive) else "Analysis failed"; body = failureMessage or "Unknown error"; buttons "Retry"/"Close"; auto-shows for the first FAILED entry with `resultSeen == false`; both buttons acknowledge (persist `resultSeen = true`).
- Routing rule: FAILED entry with non-nil keypoints → `retry(id)` directly; otherwise court marking. The local player's Analyze button ALWAYS goes to court marking (Android parity).
- Court marking spec verbatim: 12 points in order TL,TR,BR,BL,NL,NR,SNL,SNR,SFL,SFR,CTN,CTF; Android's exact colors/labels; frame at t=0.1s source resolution; zoom 1–6× display-only; title "COURT MAPPING"; copy "Tap: <full label>", "All points placed", "<n> / 12 points", "Tap each court landmark in the order shown.", "12 points give precise homography for player tracking, speeds and zones. Pinch to zoom for accuracy.", buttons "Undo"/"Clear"/"Start Analysis"; frame-load failure copy "Couldn't extract video frame"/"Couldn't load frame".
- Foreground-only uploads: `UIApplication.shared.isIdleTimerDisabled` tracks `hasActiveUpload`; `reattachToProcessing()` at app start.
- Version bump ONLY in the final task: `MARKETING_VERSION = 0.4.0`, `CURRENT_PROJECT_VERSION = 12`.
- Fakes duplication is DELIBERATE: `FakeVideosRepository`/`FakeClipsRepository` are COPIED to shared commonTest (Android unit tests cannot see commonTest sources, and Android's `LocalVideoListViewModelTest`/others still need the originals). Do not delete the Android copies.
- ENVIRONMENT: prefix xcodebuild/iOS-gradle commands with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; `xattr -cr iosApp` before each xcodebuild; `export DEVICE="iPhone 17 Pro"`; test command: `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO`. After adding/removing Swift files: `cd iosApp && xcodegen generate && cd ..` and commit the regenerated project. Commit hygiene: stage only files each task names. Do NOT sign out in the simulator.

---

### Task 1: Promote CourtMarkingModel (+ its test) to shared

**Files:**
- Move (git mv, then edit): `androidApp/src/main/java/com/badmintontracker/android/localvideo/court/CourtMarkingModel.kt` → `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/court/CourtMarkingModel.kt`
- Move (git mv, then edit): `androidApp/src/test/java/com/badmintontracker/android/localvideo/court/CourtMarkingModelTest.kt` → `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/court/CourtMarkingModelTest.kt`
- Modify (imports only): remaining `androidApp` court files that reference the moved types — known: `court/CourtMarkingViewModel.kt`, `court/CourtMarkingScreen.kt`, `AuthGate.kt` if it names them; fix whatever the compiler flags.

**Interfaces:**
- Produces (in `com.badmintontracker.shared.localvideo.court`, verbatim API): `data class CourtPoint(x: Float, y: Float)`; `object CourtMarkingSpec` (`TOTAL = 12`, `shortLabels`, `fullLabels`, `colors: List<Long>`); `data class CourtMarkingState(videoWidth: Int, videoHeight: Int, points: List<CourtPoint>)` with `isComplete`, `nextIndex`, `place(displayX, displayY, displayWidth, displayHeight)`, `undo()`, `clear()`, `toCourtKeypoints()`. Tasks 4-5 consume these from Swift via SKIE.

- [ ] **Step 1: Move the test first (RED)**

```bash
mkdir -p shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/court
git mv androidApp/src/test/java/com/badmintontracker/android/localvideo/court/CourtMarkingModelTest.kt \
       shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/court/CourtMarkingModelTest.kt
```
Edit its package line to `package com.badmintontracker.shared.localvideo.court`; keep everything else (kotest + kotlin.test are commonTest deps).

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.court.CourtMarkingModelTest" 2>&1 | tail -4`
Expected: FAIL — unresolved references (model not moved yet).

- [ ] **Step 2: Move the model (GREEN)**

```bash
mkdir -p shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/court
git mv androidApp/src/main/java/com/badmintontracker/android/localvideo/court/CourtMarkingModel.kt \
       shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/court/CourtMarkingModel.kt
```
Edit ONLY the package line to `package com.badmintontracker.shared.localvideo.court` (the file's single import, `CourtKeypoints`, already points at shared). Then fix Android references compiler-driven: run `./gradlew :androidApp:compileDebugKotlin 2>&1 | grep "Unresolved"` and add `import com.badmintontracker.shared.localvideo.court.CourtMarkingSpec` / `...CourtMarkingState` / `...CourtPoint` where flagged (same-package usage previously needed no imports). Imports only.

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.court.CourtMarkingModelTest"`
Expected: BUILD SUCCESSFUL (6 tests).

- [ ] **Step 3: Full verification**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug && export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL everywhere (Android court ViewModel/Screen tests untouched and green).

- [ ] **Step 4: Commit**

```bash
git add -A -- shared/src androidApp/src
git commit -m "refactor(shared): promote CourtMarkingModel to commonMain"
```
(Verify `git status --short` shows nothing outside those trees first.)

---

### Task 2: Promote AnalyzeCoordinator to shared (+ test move, fake copies, acknowledge helper)

**Files:**
- Move (git mv, then edit): `androidApp/src/main/java/com/badmintontracker/android/localvideo/AnalyzeCoordinator.kt` → `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinator.kt`
- Move (git mv, then edit): `androidApp/src/test/java/com/badmintontracker/android/localvideo/AnalyzeCoordinatorTest.kt` → `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinatorTest.kt`
- Copy (cp, then edit — Android originals STAY): `androidApp/src/test/java/com/badmintontracker/android/testing/{FakeVideosRepository,FakeClipsRepository}.kt` → `shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoRepository.kt` (append `acknowledgeResult` helper)
- Modify (imports/wiring only): `androidApp/.../RallyAndroidApp.kt`, `MainActivity.kt`, `AuthGate.kt`, `localvideo/LocalVideoListViewModel.kt`, `cliplist/ClipListScreen.kt` — whatever the compiler flags.
- Test: extend the moved `AnalyzeCoordinatorTest` (one new test for `acknowledgeResult`) OR add it to `LocalVideoRepositoryTest` — put it in `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalVideoRepositoryTest.kt`.

**Interfaces:**
- Produces (in `com.badmintontracker.shared.localvideo`): `class AnalyzeCoordinator(localVideos, videos, clips, scope: CoroutineScope, openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel, log: (String) -> Unit = {}, localAnnotations)` with `progress: StateFlow<Map<String, AnalyzeProgress>>`, `hasActiveUpload: StateFlow<Boolean>`, `startAnalysis(entryId, keypoints)`, `retry(entryId)`, `reattachToProcessing()`; `data class AnalyzeProgress(entryId, uploadProgress: Float?, pipelineProgress: Float?)`.
- Produces: `fun LocalVideoRepository.acknowledgeResult(id: String)` — persists `resultSeen = true` (Swift-friendly; avoids bridging a data-class copy closure).
- Consumes: `SyncLock`/`withLock` from `com.badmintontracker.shared.util`.

- [ ] **Step 1: Copy the fakes into commonTest**

```bash
mkdir -p shared/src/commonTest/kotlin/com/badmintontracker/shared/testing
cp androidApp/src/test/java/com/badmintontracker/android/testing/FakeVideosRepository.kt \
   androidApp/src/test/java/com/badmintontracker/android/testing/FakeClipsRepository.kt \
   shared/src/commonTest/kotlin/com/badmintontracker/shared/testing/
```
Edit both copies' package line to `package com.badmintontracker.shared.testing`. Content otherwise unchanged. (Deliberate duplication — see Global Constraints; do NOT delete the Android originals.)

- [ ] **Step 2: Move the coordinator test (RED)**

```bash
git mv androidApp/src/test/java/com/badmintontracker/android/localvideo/AnalyzeCoordinatorTest.kt \
       shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinatorTest.kt
```
Edits: package line → `package com.badmintontracker.shared.localvideo`; fake imports `com.badmintontracker.android.testing.FakeClipsRepository`/`FakeVideosRepository` → `com.badmintontracker.shared.testing.…`; drop the now-same-package imports of `AnalyzeStage`/`AnalyzeStep`/`LocalAnnotationsRepository`/`LocalVideoEntry`/`LocalVideoRepository` (or leave them — same-module imports are harmless). All other imports (kotest, coroutines-test, MapSettings, ktor ByteReadChannel, kotlinx.datetime.Instant) are commonTest-safe.

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.AnalyzeCoordinatorTest" 2>&1 | tail -4`
Expected: FAIL — `AnalyzeCoordinator` unresolved (not moved yet).

- [ ] **Step 3: Move the coordinator (GREEN)**

```bash
git mv androidApp/src/main/java/com/badmintontracker/android/localvideo/AnalyzeCoordinator.kt \
       shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinator.kt
```
Edits in the moved file — exactly three:
1. Package line → `package com.badmintontracker.shared.localvideo`; delete now-same-package imports of shared localvideo types if the compiler flags them as redundant errors (unused imports are warnings, leaving them is fine).
2. Replace the JVM line
   ```kotlin
   private val active = java.util.Collections.synchronizedSet(mutableSetOf<String>())
   ```
   with
   ```kotlin
   private val activeLock = SyncLock()
   private val active = mutableSetOf<String>()
   ```
   and update the two usages in `launchPipeline`:
   ```kotlin
   if (!activeLock.withLock { active.add(entryId) }) return
   ```
   and in the `finally`:
   ```kotlin
   activeLock.withLock { active.remove(entryId) }
   ```
   Add imports `com.badmintontracker.shared.util.SyncLock` and `com.badmintontracker.shared.util.withLock`.
3. Nothing else — step logic, error strings, progress handling stay byte-identical.

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.AnalyzeCoordinatorTest"`
Expected: BUILD SUCCESSFUL (10-11 tests green).

- [ ] **Step 4: acknowledgeResult helper (TDD)**

Add to `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalVideoRepositoryTest.kt`:
```kotlin
    @Test
    fun acknowledgeResult_sets_resultSeen() {
        val repo = LocalVideoRepository(MapSettings())
        repo.add(entry("a"))
        repo.acknowledgeResult("a")
        repo.get("a")?.resultSeen shouldBe true
    }
```
Run it → FAIL (unresolved `acknowledgeResult`). Then append to `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoRepository.kt` (top level, after the class):
```kotlin

/** Marks a FAILED entry's result dialog as seen (Swift-friendly single-purpose mutation). */
fun LocalVideoRepository.acknowledgeResult(id: String) =
    update(id) { it.copy(resultSeen = true) }
```
Run again → PASS.

- [ ] **Step 5: Android wiring sweep**

`RallyAndroidApp.kt`: swap the import to `com.badmintontracker.shared.localvideo.AnalyzeCoordinator`; construction is unchanged (same named args). Then compiler-driven imports across `MainActivity.kt`, `AuthGate.kt`, `LocalVideoListViewModel.kt` (uses `AnalyzeProgress`/coordinator), `ClipListScreen.kt`, and remaining test files (`LocalVideoListViewModelTest.kt` imports `AnalyzeProgress`) — imports only, until `:androidApp:compileDebugKotlin` and `:androidApp:compileDebugUnitTestKotlin` are clean.

- [ ] **Step 6: Full verification**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug && export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL everywhere; the moved 10-11-test suite green in shared; all Android suites green.

- [ ] **Step 7: Commit**

```bash
git add -A -- shared/src androidApp/src
git commit -m "refactor(shared): promote AnalyzeCoordinator to commonMain with SyncLock guard"
```

---

### Task 3: iosMain upload channel + coordinator factory + app wiring

**Files:**
- Create: `shared/src/iosMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoChannel.kt`
- Create: `shared/src/iosMain/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinatorIos.kt`
- Test: `shared/src/iosTest/kotlin/com/badmintontracker/shared/localvideo/LocalVideoChannelTest.kt`
- Modify: `iosApp/Sources/RallyIOSApp.swift`
- Modify: `iosApp/Sources/RootView.swift`

**Interfaces:**
- Produces: `fun openLocalVideoChannel(absolutePath: String, offset: Long): ByteReadChannel` (throws `IllegalStateException("Video file is missing or access was revoked")` for a missing file); `fun createIosAnalyzeCoordinator(rally: RallyApp, documentsPath: String): AnalyzeCoordinator` (Swift: `AnalyzeCoordinatorIosKt.createIosAnalyzeCoordinator(rally:documentsPath:)`).
- Produces (Swift): `RallyIOSApp` holds `let analyze: AnalyzeCoordinator`, calls `reattachToProcessing()` at init, passes it to `RootView(rally:analyze:)`; RootView forwards to `ClipListView(rally:analyze:)` (Task 5 updates that initializer — until then RootView keeps calling `ClipListView(rally:)` and only observes the idle timer). Idle-timer binding lives in RootView.

- [ ] **Step 1: Write the failing iosTest**

`shared/src/iosTest/kotlin/com/badmintontracker/shared/localvideo/LocalVideoChannelTest.kt`:
```kotlin
package com.badmintontracker.shared.localvideo

import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalVideoChannelTest {

    private fun writeTempFile(bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + "channel-test-${bytes.size}-${bytes.hashCode()}.bin"
        val ok = NSFileManager.defaultManager.createFileAtPath(
            path, contents = bytes.toNSData(), attributes = null
        )
        assertTrue(ok, "temp file created")
        return path
    }

    @Test
    fun streams_full_file_from_offset_zero() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }   // > one chunk
        val path = writeTempFile(bytes)
        val channel = openLocalVideoChannel(path, offset = 0)
        val read = channel.readRemaining().readBytes()
        assertContentEquals(bytes, read)
    }

    @Test
    fun streams_from_resume_offset() = runTest {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val path = writeTempFile(bytes)
        val channel = openLocalVideoChannel(path, offset = 65_536)
        val read = channel.readRemaining().readBytes()
        assertContentEquals(bytes.copyOfRange(65_536, bytes.size), read)
    }

    @Test
    fun missing_file_throws_with_parity_message() {
        assertFailsWith<IllegalStateException> {
            openLocalVideoChannel(NSTemporaryDirectory() + "does-not-exist.bin", offset = 0)
        }.also {
            assertTrue(it.message!!.contains("missing or access was revoked"))
        }
    }
}
```
(`toNSData` is produced in Step 2; if `readRemaining().readBytes()` needs different ktor-io imports in this ktor version, adapt the read side — the assertions are the contract.)

- [ ] **Step 2: Run to verify it fails**

Run: `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:iosSimulatorArm64Test --tests "com.badmintontracker.shared.localvideo.LocalVideoChannelTest" 2>&1 | tail -5`
Expected: compile FAILURE — `openLocalVideoChannel`/`toNSData` unresolved.

- [ ] **Step 3: Implement the channel**

`shared/src/iosMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoChannel.kt`:
```kotlin
package com.badmintontracker.shared.localvideo

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.fileHandleForReadingAtPath
import platform.posix.memcpy

private const val CHUNK_BYTES = 65_536uL

/**
 * Streams a local video file into a ByteReadChannel starting at [offset] —
 * the iOS mirror of Android's contentResolver + skipExactly + toByteReadChannel.
 * Never loads the whole file into memory.
 */
fun openLocalVideoChannel(absolutePath: String, offset: Long): ByteReadChannel {
    val handle = NSFileHandle.fileHandleForReadingAtPath(absolutePath)
        ?: error("Video file is missing or access was revoked")
    handle.seekToFileOffset(offset.toULong())
    val channel = ByteChannel(autoFlush = true)
    CoroutineScope(Dispatchers.Default).launch {
        try {
            while (true) {
                val data = handle.readDataOfLength(CHUNK_BYTES)
                val bytes = data.toByteArray()
                if (bytes.isEmpty()) break
                channel.writeFully(bytes, 0, bytes.size)
            }
            channel.close(null)
        } catch (t: Throwable) {
            channel.close(t)
        } finally {
            handle.closeFile()
        }
    }
    return channel
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = this.usePinned { pinned ->
    NSData.create(bytes = if (isEmpty()) null else pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
```
(Add `import platform.Foundation.create` if the `NSData.create` overload requires it; adjust ktor `close` signature if this ktor version uses `close()`/`cancel(t)` — behavior contract: normal close at EOF, failure close on error.)

- [ ] **Step 4: Run iosTest to verify it passes**

Run: `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:iosSimulatorArm64Test --tests "com.badmintontracker.shared.localvideo.LocalVideoChannelTest" 2>&1 | tail -4`
Expected: BUILD SUCCESSFUL (3 tests). (This runs the whole shared test suite on the simulator the first time — several minutes.)

- [ ] **Step 5: The factory**

`shared/src/iosMain/kotlin/com/badmintontracker/shared/localvideo/AnalyzeCoordinatorIos.kt`:
```kotlin
package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.RallyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * iOS composition helper: wires the shared AnalyzeCoordinator with a
 * file-streaming channel (entry.uri is a Documents-relative path on iOS).
 */
fun createIosAnalyzeCoordinator(rally: RallyApp, documentsPath: String): AnalyzeCoordinator =
    AnalyzeCoordinator(
        localVideos = rally.localVideos,
        videos = rally.videos,
        clips = rally.clips,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openChannel = { uri, offset ->
            openLocalVideoChannel("$documentsPath/$uri", offset)
        },
        log = { println("AnalyzeCoordinator: $it") },
        localAnnotations = rally.localAnnotations,
    )
```

- [ ] **Step 6: Swift wiring (app holder + reattach + idle timer)**

`iosApp/Sources/RallyIOSApp.swift` — hold and start the coordinator:
```swift
import SwiftUI
import Shared

@main
struct RallyIOSApp: App {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator

    init() {
        let info = Bundle.main.infoDictionary
        rally = RallyAppIosKt.createRallyApp(
            url: info?["SUPABASE_URL"] as? String ?? "",
            anonKey: info?["SUPABASE_ANON_KEY"] as? String ?? ""
        )
        analyze = AnalyzeCoordinatorIosKt.createIosAnalyzeCoordinator(
            rally: rally,
            documentsPath: LocalVideoFiles.documents.path
        )
        analyze.reattachToProcessing()
    }

    var body: some Scene {
        WindowGroup {
            RootView(rally: rally, analyze: analyze)
        }
    }
}
```
`iosApp/Sources/RootView.swift` — accept the coordinator and bind the idle timer (add one property + one `.task`; nothing else changes):
```swift
struct RootView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    ...
        .task {
            for await active in analyze.hasActiveUpload {
                UIApplication.shared.isIdleTimerDisabled = (active as? Bool) ?? false
            }
        }
```
(SKIE bridges `StateFlow<Boolean>` elements as `KotlinBoolean`/`Bool` — use whichever cast form compiles, e.g. `active.boolValue`. Add `import UIKit` if needed. `ClipListView(rally: rally)` call is unchanged in this task — Task 5 threads `analyze` into it.)

- [ ] **Step 7: Verify the app builds and runs**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command; then install + launch + screenshot (app must reach the clip list as before).
Expected: `** TEST SUCCEEDED **`; app runs.

- [ ] **Step 8: Commit**

```bash
git add shared/src/iosMain/kotlin/com/badmintontracker/shared/localvideo \
        shared/src/iosTest iosApp/Sources/RallyIOSApp.swift iosApp/Sources/RootView.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): analyze coordinator wiring with file-streaming upload channel"
```

---

### Task 4: Court marking screen (SwiftUI)

**Files:**
- Create: `iosApp/Sources/CourtMarking/CourtFrameLoader.swift`
- Create: `iosApp/Sources/CourtMarking/CourtTapMath.swift`
- Create: `iosApp/Sources/CourtMarking/CourtMarkingView.swift`
- Create: `iosApp/Sources/CourtMarking/SchematicCourtGuide.swift`
- Test: `iosApp/Tests/CourtTapMathTests.swift`

**Interfaces:**
- Consumes: shared `CourtMarkingSpec`/`CourtMarkingState` (Task 1; Swift enums of colors come from the spec's `Long` values — convert with the existing `Color(rgb:)` by truncating to 24-bit: `Color(rgb: UInt32(longValue & 0xFFFFFF))`), `LocalVideoFiles.resolve(relativePath:)`.
- Produces: `struct CourtMarkingRoute: Hashable { let entryId: String }`; `CourtMarkingView(rally: RallyApp, analyze: AnalyzeCoordinator, entryId: String)` — pops itself after `startAnalysis`. Task 5 registers the navigationDestination.
- Produces: `CourtTapMath.inverse(tapX:tapY:offsetX:offsetY:scale:) -> CGPoint`.

- [ ] **Step 1: Failing CourtTapMath tests**

`iosApp/Tests/CourtTapMathTests.swift`:
```swift
import XCTest
@testable import iosApp

/// Port of Android CourtMarkingScreen's tap inverse-transform:
/// x = (tap.x - offset.x) / scale (origin top-left).
final class CourtTapMathTests: XCTestCase {
    func testIdentityAtScaleOne() {
        let p = CourtTapMath.inverse(tapX: 120, tapY: 40, offsetX: 0, offsetY: 0, scale: 1)
        XCTAssertEqual(p.x, 120, accuracy: 0.001)
        XCTAssertEqual(p.y, 40, accuracy: 0.001)
    }

    func testZoomedAndPanned() {
        // scale 2, content translated by (-100, -50): tap at (300, 250)
        // maps back to ((300 - (-100)) / 2, (250 - (-50)) / 2) = (200, 150).
        let p = CourtTapMath.inverse(tapX: 300, tapY: 250, offsetX: -100, offsetY: -50, scale: 2)
        XCTAssertEqual(p.x, 200, accuracy: 0.001)
        XCTAssertEqual(p.y, 150, accuracy: 0.001)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Standard xcodebuild test command. Expected: compile FAILURE — `CourtTapMath` undefined.

- [ ] **Step 3: Implement CourtTapMath + frame loader**

`iosApp/Sources/CourtMarking/CourtTapMath.swift`:
```swift
import CoreGraphics

/// Inverse of the zoom/pan transform (transform origin top-left), matching
/// Android's tap handler in CourtMarkingScreen.
enum CourtTapMath {
    static func inverse(tapX: CGFloat, tapY: CGFloat, offsetX: CGFloat, offsetY: CGFloat, scale: CGFloat) -> CGPoint {
        CGPoint(x: (tapX - offsetX) / scale, y: (tapY - offsetY) / scale)
    }
}
```

`iosApp/Sources/CourtMarking/CourtFrameLoader.swift`:
```swift
import AVFoundation
import UIKit

/// First frame at t = 0.1s, source resolution — mirror of Android's loadFirstFrame.
enum CourtFrameLoader {
    static func loadFirstFrame(relativePath: String) async throws -> UIImage {
        let url = LocalVideoFiles.resolve(relativePath: relativePath)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .positiveInfinity
        let time = CMTime(value: 1, timescale: 10)   // 0.1s, like Android's 100_000µs
        do {
            let cgImage = try await generator.image(at: time).image
            return UIImage(cgImage: cgImage)
        } catch {
            throw NSError(
                domain: "CourtFrameLoader", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Couldn't extract video frame"]
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Standard xcodebuild test command. Expected: `** TEST SUCCEEDED **` (2 new CourtTapMathTests).

- [ ] **Step 5: The marking view**

`iosApp/Sources/CourtMarking/CourtMarkingView.swift`:
```swift
import Shared
import SwiftUI

struct CourtMarkingRoute: Hashable {
    let entryId: String
}

struct CourtMarkingView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let entryId: String
    @Environment(\.dismiss) private var dismiss
    @State private var frame: UIImage? = nil
    @State private var marking: CourtMarkingState? = nil
    @State private var error: String? = nil
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @GestureState private var magnifyFrom: CGFloat = 1

    var body: some View {
        VStack(spacing: 0) {
            if let error {
                ErrorBanner(message: error)
                Spacer()
            } else if let frame, let marking {
                content(frame: frame, marking: marking)
            } else {
                Spacer()
                ProgressView()
                Spacer()
            }
        }
        .navigationTitle("COURT MAPPING")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard marking == nil, error == nil else { return }
            guard let entry = rally.localVideos.get(id: entryId) else {
                dismiss()
                return
            }
            do {
                let image = try await CourtFrameLoader.loadFirstFrame(relativePath: entry.uri)
                frame = image
                marking = CourtMarkingState(
                    videoWidth: Int32(image.size.width * image.scale),
                    videoHeight: Int32(image.size.height * image.scale),
                    points: []
                )
            } catch {
                self.error = error.localizedDescription   // "Couldn't extract video frame"
            }
        }
    }

    @ViewBuilder
    private func content(frame: UIImage, marking: CourtMarkingState) -> some View {
        GeometryReader { geo in
            let aspect = CGFloat(truncating: marking.videoWidth as NSNumber)
                / CGFloat(truncating: marking.videoHeight as NSNumber)
            let displayW = min(geo.size.width, geo.size.height * aspect)
            let displayH = displayW / aspect
            ZStack(alignment: .topLeading) {
                frameCanvas(frame: frame, marking: marking, displaySize: CGSize(width: displayW, height: displayH))
                    .frame(width: displayW, height: displayH)
                    .scaleEffect(scale, anchor: .topLeading)
                    .offset(offset)
            }
            .frame(width: displayW, height: displayH)
            .clipped()
            .contentShape(Rectangle())
            .position(x: geo.size.width / 2, y: geo.size.height / 2)
            .gesture(tapGesture(displaySize: CGSize(width: displayW, height: displayH)))
            .simultaneousGesture(magnifyGesture(displaySize: CGSize(width: displayW, height: displayH)))
            .simultaneousGesture(panGesture)
        }

        instructionRow(marking: marking)
        SchematicCourtGuide(placedCount: Int(marking.points.count))
            .padding(.vertical, 8)

        HStack(spacing: 12) {
            Button("Undo") { self.marking = marking.undo() }
                .disabled(marking.points.isEmpty)
            Button("Clear") { self.marking = marking.clear() }
                .disabled(marking.points.isEmpty)
        }
        .padding(.horizontal, 16)

        if marking.isComplete {
            Button("Start Analysis") {
                analyze.startAnalysis(entryId: entryId, keypoints: marking.toCourtKeypoints())
                dismiss()
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(16)
        }
    }

    private func tapGesture(displaySize: CGSize) -> some Gesture {
        SpatialTapGesture().onEnded { value in
            guard let marking, !marking.isComplete else { return }
            let p = CourtTapMath.inverse(
                tapX: value.location.x, tapY: value.location.y,
                offsetX: offset.width, offsetY: offset.height, scale: scale
            )
            guard p.x >= 0, p.y >= 0, p.x <= displaySize.width, p.y <= displaySize.height else { return }
            self.marking = marking.place(
                displayX: Float(p.x), displayY: Float(p.y),
                displayWidth: Float(displaySize.width), displayHeight: Float(displaySize.height)
            )
        }
    }

    private func magnifyGesture(displaySize: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let zoom = value.magnification / magnifyFrom
                let newScale = min(max(scale * zoom, 1), 6)
                let effectiveZoom = newScale / scale
                let centroid = value.startLocation
                offset = CGSize(
                    width: centroid.x - (centroid.x - offset.width) * effectiveZoom,
                    height: centroid.y - (centroid.y - offset.height) * effectiveZoom
                )
                scale = newScale
                if scale == 1 { offset = .zero }
            }
            .updating($magnifyFrom) { value, state, _ in state = value.magnification }
    }

    private var panGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard scale > 1 else { return }
                offset = CGSize(
                    width: offset.width + value.translation.width * 0.15,
                    height: offset.height + value.translation.height * 0.15
                )
            }
    }

    private func instructionRow(marking: CourtMarkingState) -> some View {
        HStack {
            if marking.isComplete {
                Text("All points placed")
                    .font(.body.weight(.medium))
                    .foregroundStyle(Shuttl.accent)
            } else {
                Text("Tap: \(CourtMarkingSpec.shared.fullLabels[Int(marking.nextIndex)])")
                    .font(.body.weight(.medium))
                    .foregroundStyle(specColor(Int(marking.nextIndex)))
            }
            Spacer()
            Text("\(marking.points.count) / \(CourtMarkingSpec.shared.TOTAL) points")
                .font(.footnote)
                .foregroundStyle(Shuttl.textSecondary)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    @ViewBuilder
    private func frameCanvas(frame: UIImage, marking: CourtMarkingState, displaySize: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            Image(uiImage: frame)
                .resizable()
                .frame(width: displaySize.width, height: displaySize.height)
            Canvas { context, size in
                drawCourtGuide(context: &context, size: size)
                drawCornerRectangle(context: &context, size: size, marking: marking)
                drawPlacedPoints(context: &context, size: size, marking: marking)
            }
        }
    }

    private func drawCourtGuide(context: inout GraphicsContext, size: CGSize) {
        let guideGreen = Color(red: 0x22/255, green: 0xC5/255, blue: 0x5E/255).opacity(0.2)
        let connectGreen = Color(red: 0x22/255, green: 0xC5/255, blue: 0x5E/255).opacity(0.6)
        let margin: CGFloat = 0.15
        let x1 = size.width * margin, x2 = size.width * (1 - margin)
        let y1 = size.height * margin, y2 = size.height * (1 - margin)
        let dash = StrokeStyle(lineWidth: 1, dash: [4, 4])
        var rect = Path(); rect.addRect(CGRect(x: x1, y: y1, width: x2 - x1, height: y2 - y1))
        context.stroke(rect, with: .color(guideGreen), style: dash)
        let netY = size.height / 2
        var net = Path(); net.move(to: CGPoint(x: x1, y: netY)); net.addLine(to: CGPoint(x: x2, y: netY))
        context.stroke(net, with: .color(connectGreen), style: dash)
        let serviceY1 = y1 + (netY - y1) * 0.6
        let serviceY2 = y2 - (y2 - netY) * 0.6
        for sy in [serviceY1, serviceY2] {
            var line = Path(); line.move(to: CGPoint(x: x1, y: sy)); line.addLine(to: CGPoint(x: x2, y: sy))
            context.stroke(line, with: .color(guideGreen), style: dash)
        }
        var center = Path()
        center.move(to: CGPoint(x: size.width / 2, y: serviceY1))
        center.addLine(to: CGPoint(x: size.width / 2, y: serviceY2))
        context.stroke(center, with: .color(guideGreen), style: dash)
    }

    private func drawCornerRectangle(context: inout GraphicsContext, size: CGSize, marking: CourtMarkingState) {
        guard marking.points.count >= 4 else { return }
        let fx = size.width / CGFloat(truncating: marking.videoWidth as NSNumber)
        let fy = size.height / CGFloat(truncating: marking.videoHeight as NSNumber)
        var path = Path()
        for i in 0..<4 {
            let p = marking.points[i]
            let dp = CGPoint(x: CGFloat(p.x) * fx, y: CGFloat(p.y) * fy)
            if i == 0 { path.move(to: dp) } else { path.addLine(to: dp) }
        }
        path.closeSubpath()
        context.stroke(path, with: .color(.white.opacity(0.8)), style: StrokeStyle(lineWidth: 1, dash: [8, 4]))
    }

    private func drawPlacedPoints(context: inout GraphicsContext, size: CGSize, marking: CourtMarkingState) {
        let fx = size.width / CGFloat(truncating: marking.videoWidth as NSNumber)
        let fy = size.height / CGFloat(truncating: marking.videoHeight as NSNumber)
        let radius = 10 / scale
        for (i, p) in marking.points.enumerated() {
            let dp = CGPoint(x: CGFloat(p.x) * fx, y: CGFloat(p.y) * fy)
            let circle = Path(ellipseIn: CGRect(x: dp.x - radius, y: dp.y - radius, width: radius * 2, height: radius * 2))
            context.fill(circle, with: .color(specColor(i)))
            context.stroke(circle, with: .color(.black), lineWidth: 2 / scale)
            context.draw(
                Text(CourtMarkingSpec.shared.shortLabels[i])
                    .font(.system(size: 9 / scale, weight: .bold))
                    .foregroundColor(.black),
                at: dp
            )
        }
    }
}

/// Spec colors are 0xAARRGGBB Longs from shared CourtMarkingSpec.
func specColor(_ index: Int) -> Color {
    let raw = CourtMarkingSpec.shared.colors[index]
    let rgb = UInt32(truncating: raw) & 0x00FFFFFF
    return Color(rgb: rgb)
}
```
SKIE bridging notes for the implementer: `CourtMarkingSpec` is a Kotlin `object` → `CourtMarkingSpec.shared`; `colors` arrives as `[KotlinLong]` (hence `UInt32(truncating:)`); `videoWidth/videoHeight` are `Int32`; `marking.points` is `[CourtPoint]` with `Float` fields; `place/undo/clear` return NEW `CourtMarkingState` instances (immutable) — always reassign `self.marking`. If any bridged accessor differs (e.g. `colors` as `[NSNumber]`), follow the generated header; the geometry contract is fixed. The pan gesture's 0.15 damping factor is an iOS-feel adjustment (SwiftUI drag deltas are cumulative per gesture, unlike Compose's incremental pan) — implementer may tune ONLY that factor for usable panning, noting the final value.

- [ ] **Step 6: The schematic guide**

`iosApp/Sources/CourtMarking/SchematicCourtGuide.swift`:
```swift
import Shared
import SwiftUI

/// Mini-court showing which landmark is next — mirror of Android's SchematicCourtGuide.
/// Court meters from homography.ts: 6.1 wide × 13.4 long, service line 1.98 from net.
struct SchematicCourtGuide: View {
    let placedCount: Int

    private static let courtW: CGFloat = 6.1
    private static let courtL: CGFloat = 13.4
    private static let serviceLine: CGFloat = 1.98

    /// Landmark positions in court meters (x across width, y along length),
    /// index-aligned with CourtMarkingSpec order.
    private static let positions: [CGPoint] = [
        CGPoint(x: 0, y: 0),                      // TL
        CGPoint(x: courtW, y: 0),                 // TR
        CGPoint(x: courtW, y: courtL),            // BR
        CGPoint(x: 0, y: courtL),                 // BL
        CGPoint(x: 0, y: courtL / 2),             // NL
        CGPoint(x: courtW, y: courtL / 2),        // NR
        CGPoint(x: 0, y: courtL / 2 + serviceLine),        // SNL
        CGPoint(x: courtW, y: courtL / 2 + serviceLine),   // SNR
        CGPoint(x: 0, y: courtL / 2 - serviceLine),        // SFL
        CGPoint(x: courtW, y: courtL / 2 - serviceLine),   // SFR
        CGPoint(x: courtW / 2, y: courtL / 2 + serviceLine), // CTN
        CGPoint(x: courtW / 2, y: courtL / 2 - serviceLine), // CTF
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 12) {
                canvas
                    .frame(width: 160 * Self.courtW / Self.courtL, height: 160)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Tap each court landmark in the order shown.")
                        .font(.footnote)
                        .foregroundStyle(Shuttl.text)
                    Text("12 points give precise homography for player tracking, speeds and zones. Pinch to zoom for accuracy.")
                        .font(.footnote)
                        .foregroundStyle(Shuttl.textSecondary)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private var canvas: some View {
        Canvas { context, size in
            let sx = size.width / Self.courtW
            let sy = size.height / Self.courtL
            let line = Color(rgb: 0x22C55E)
            var outline = Path(); outline.addRect(CGRect(origin: .zero, size: size))
            context.stroke(outline, with: .color(line), lineWidth: 1)
            for y in [Self.courtL / 2, Self.courtL / 2 - Self.serviceLine, Self.courtL / 2 + Self.serviceLine] {
                var p = Path()
                p.move(to: CGPoint(x: 0, y: y * sy)); p.addLine(to: CGPoint(x: size.width, y: y * sy))
                context.stroke(p, with: .color(line.opacity(y == Self.courtL / 2 ? 1 : 0.5)), lineWidth: 1)
            }
            var center = Path()
            center.move(to: CGPoint(x: size.width / 2, y: (Self.courtL / 2 - Self.serviceLine) * sy))
            center.addLine(to: CGPoint(x: size.width / 2, y: (Self.courtL / 2 + Self.serviceLine) * sy))
            context.stroke(center, with: .color(line.opacity(0.5)), lineWidth: 1)

            for (i, pos) in Self.positions.enumerated() {
                let dp = CGPoint(x: pos.x * sx, y: pos.y * sy)
                let isNext = i == placedCount
                let isPlaced = i < placedCount
                let radius: CGFloat = isNext ? 6 : 3.5
                let color = specColor(i).opacity(isPlaced || isNext ? 1 : 0.35)
                let circle = Path(ellipseIn: CGRect(x: dp.x - radius, y: dp.y - radius, width: radius * 2, height: radius * 2))
                context.fill(circle, with: .color(color))
                if isNext {
                    context.stroke(circle, with: .color(.black), lineWidth: 1)
                }
            }
        }
    }
}
```

- [ ] **Step 7: Build + suite**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **` (all suites incl. the 2 new tap-math tests). The screen is unreachable until Task 5 wires navigation — compile + tests are this task's gate.

- [ ] **Step 8: Commit**

```bash
git add iosApp/Sources/CourtMarking iosApp/Tests/CourtTapMathTests.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): tap-in-order court marking screen over shared geometry"
```

---

### Task 5: Analyze UI wiring — status text, buttons, routing, result dialog

**Files:**
- Create: `iosApp/Sources/LocalVideo/LocalVideoStatus.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalVideoSection.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerView.swift`
- Modify: `iosApp/Sources/RootView.swift` (thread `analyze` into ClipListView)
- Test: `iosApp/Tests/LocalVideoStatusTests.swift`

**Interfaces:**
- Consumes: `analyze.progress` (SKIE AsyncSequence of `[String: AnalyzeProgress]`), `analyze.retry(entryId:)`, `analyze.startAnalysis` (used in Task 4), `rally.localVideos.acknowledgeResult(id:)` (SwiftInterop-style top-level: `LocalVideoRepositoryKt.acknowledgeResult(rally.localVideos, id:)` — use whichever call form the header offers), `CourtMarkingRoute` + `CourtMarkingView` (Task 4).
- Produces: `LocalVideoStatus.text(stage:uploadProgress:pipelineProgress:) -> String?` and `LocalVideoStatus.canAnalyze(stage:) -> Bool`.

- [ ] **Step 1: Failing status-mapping tests**

`iosApp/Tests/LocalVideoStatusTests.swift`:
```swift
import Shared
import XCTest
@testable import iosApp

final class LocalVideoStatusTests: XCTestCase {
    func testLocalAndFailedShowNothing() {
        XCTAssertNil(LocalVideoStatus.text(stage: .local, uploadProgress: nil, pipelineProgress: nil))
        XCTAssertNil(LocalVideoStatus.text(stage: .failed, uploadProgress: nil, pipelineProgress: nil))
    }

    func testUploadingWithAndWithoutProgress() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .uploading, uploadProgress: 0.42, pipelineProgress: nil),
            "Uploading 42%…"
        )
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .uploading, uploadProgress: nil, pipelineProgress: nil),
            "Uploading…"
        )
    }

    func testProcessingWithAndWithoutProgress() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .processing, uploadProgress: nil, pipelineProgress: 0.8),
            "Analyzing 80%…"
        )
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .processing, uploadProgress: nil, pipelineProgress: nil),
            "Analyzing…"
        )
    }

    func testAnalyzed() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .analyzed, uploadProgress: nil, pipelineProgress: nil),
            "Analyzed"
        )
    }

    func testCanAnalyze() {
        XCTAssertTrue(LocalVideoStatus.canAnalyze(stage: .local))
        XCTAssertTrue(LocalVideoStatus.canAnalyze(stage: .failed))
        XCTAssertFalse(LocalVideoStatus.canAnalyze(stage: .uploading))
        XCTAssertFalse(LocalVideoStatus.canAnalyze(stage: .processing))
        XCTAssertFalse(LocalVideoStatus.canAnalyze(stage: .analyzed))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Standard xcodebuild test command. Expected: compile FAILURE — `LocalVideoStatus` undefined.

- [ ] **Step 3: Implement the mapping**

`iosApp/Sources/LocalVideo/LocalVideoStatus.swift`:
```swift
import Shared

/// Verbatim port of Android LocalVideoListViewModel.toRow's status mapping.
enum LocalVideoStatus {
    static func text(stage: AnalyzeStage, uploadProgress: Float?, pipelineProgress: Float?) -> String? {
        switch stage {
        case .local, .failed:
            return nil
        case .uploading:
            if let p = uploadProgress { return "Uploading \(Int(p * 100))%…" }
            return "Uploading…"
        case .processing:
            if let p = pipelineProgress { return "Analyzing \(Int(p * 100))%…" }
            return "Analyzing…"
        case .analyzed:
            return "Analyzed"
        default:
            return nil
        }
    }

    static func canAnalyze(stage: AnalyzeStage) -> Bool {
        stage == .local || stage == .failed
    }
}
```

- [ ] **Step 4: Run to verify pass**

Standard xcodebuild test command. Expected: `** TEST SUCCEEDED **` (5 new tests).

- [ ] **Step 5: Row UI — Analyze button, spinner, status line**

`iosApp/Sources/LocalVideo/LocalVideoSection.swift` — extend `LocalVideoRowView` (new signature; ClipListView call site updated in Step 6):
```swift
struct LocalVideoRowView: View {
    let entry: LocalVideoEntry
    let thumbnails: LocalThumbnails
    let progress: AnalyzeProgress?
    let onAnalyze: () -> Void
    let onRemove: () -> Void
    ...
    // inside the VStack, after the subtitle Text, add:
                    if let status = LocalVideoStatus.text(
                        stage: entry.stage,
                        uploadProgress: progress?.uploadProgress?.floatValue,
                        pipelineProgress: progress?.pipelineProgress?.floatValue
                    ) {
                        Text(status)
                            .font(.footnote)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
    // between the VStack and the Menu (after Spacer()), add:
                if LocalVideoStatus.canAnalyze(stage: entry.stage) {
                    Button("Analyze") { onAnalyze() }
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Shuttl.accent)
                        .buttonStyle(.borderless)
                } else {
                    ProgressView()
                        .controlSize(.small)
                }
```
(SKIE bridges `AnalyzeProgress.uploadProgress: Float?` as `KotlinFloat?` — hence `.floatValue`; if it arrives as `Float?` directly, drop the accessor. The plain `else` spinner matches Android exactly — `if (row.canAnalyze) ShuttlButton("Analyze") else CircularProgressIndicator` — which shows the small spinner for UPLOADING, PROCESSING, and ANALYZED alike.)

- [ ] **Step 6: ClipListView — progress observation, routing, result dialog**

In `iosApp/Sources/ClipList/ClipListView.swift`:
1. New property + init param (threaded from RootView):
```swift
    let analyze: AnalyzeCoordinator
    @State private var progressById: [String: AnalyzeProgress] = [:]
    @State private var resultEntry: LocalVideoEntry? = nil
    @State private var courtMarkingPath: [CourtMarkingRoute] = []   // only if needed; see note

    init(rally: RallyApp, analyze: AnalyzeCoordinator) {
        self.rally = rally
        self.analyze = analyze
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
    }
```
2. Observe progress (new `.task` alongside the entries one):
```swift
        .task {
            for await map in analyze.progress {
                progressById = (map as? [String: AnalyzeProgress]) ?? [:]
            }
        }
```
3. Auto result dialog — add a `.onChange(of: localEntries)`-equivalent by computing inside the entries loop: after `localEntries = entries` add
```swift
                if resultEntry == nil {
                    resultEntry = entries.first { $0.stage == .failed && !$0.resultSeen }
                }
```
4. The analyze action (Android's AuthGate routing rule):
```swift
    private func analyzeAction(_ entry: LocalVideoEntry) {
        if entry.stage == .failed && entry.keypoints != nil {
            analyze.retry(entryId: entry.id)
        } else {
            navigationTarget = CourtMarkingRoute(entryId: entry.id)
        }
    }
```
   Navigation: add `@State private var navigationTarget: CourtMarkingRoute? = nil` and register ONE destination — item-based, for programmatic pushes — in `content(_:)` next to the existing registrations:
```swift
        .navigationDestination(item: $navigationTarget) { route in
            CourtMarkingView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
```
   (Do NOT also register a value-based `navigationDestination(for: CourtMarkingRoute.self)` — one mechanism only. The local player pushes court marking with its own item-based destination in Step 7; the behavior contract is that the Analyze button, the dialog's Retry, and the player's Analyze all push programmatically.)
5. Row call site update:
```swift
                        LocalVideoRowView(
                            entry: entry,
                            thumbnails: thumbnails,
                            progress: progressById[entry.id],
                            onAnalyze: { analyzeAction(entry) },
                            onRemove: {
                                intake.remove(entry: entry)
                                thumbnails.evict(id: entry.id)
                            }
                        )
```
6. Result dialog (alert on the outer VStack):
```swift
        .alert(
            (resultEntry?.failureMessage ?? "").localizedCaseInsensitiveContains("no rallies")
                ? "No rallies found" : "Analysis failed",
            isPresented: Binding(
                get: { resultEntry != nil },
                set: { if !$0 { resultEntry = nil } }
            ),
            presenting: resultEntry
        ) { entry in
            Button("Retry") {
                LocalVideoRepositoryKt.acknowledgeResult(rally.localVideos, id: entry.id)
                resultEntry = nil
                analyzeAction(entry)
            }
            Button("Close", role: .cancel) {
                LocalVideoRepositoryKt.acknowledgeResult(rally.localVideos, id: entry.id)
                resultEntry = nil
            }
        } message: { entry in
            Text(entry.failureMessage ?? "Unknown error")
        }
```
7. `RootView.swift`: change the authenticated branch to `NavigationStack { ClipListView(rally: rally, analyze: analyze) }`.

- [ ] **Step 7: Local player Analyze button**

`iosApp/Sources/LocalVideo/LocalPlayerView.swift` — thread the coordinator in and push court marking programmatically (item-based, consistent with Step 6):
1. Add properties `let analyze: AnalyzeCoordinator` and `@State private var courtTarget: CourtMarkingRoute? = nil`; update ClipListView's `LocalPlayerRoute` destination call site to `LocalPlayerView(rally: rally, analyze: analyze, entryId: route.entryId)`.
2. Add a second toolbar item (before the + annotation button):
```swift
            ToolbarItem(placement: .topBarTrailing) {
                if LocalVideoStatus.canAnalyze(stage: model.entry.stage) {
                    Button("Analyze") { courtTarget = CourtMarkingRoute(entryId: model.entry.id) }
                        .font(.footnote.weight(.semibold))
                }
            }
```
3. Register the destination on the player's content (same pattern as Step 6):
```swift
        .navigationDestination(item: $courtTarget) { route in
            CourtMarkingView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
```

- [ ] **Step 8: Verify**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **` (all suites). Install + launch + screenshot: the local rows render with Analyze buttons; the rest of the flow (court marking → upload → progress → result) is verified in the milestone acceptance walkthrough.

- [ ] **Step 9: Commit**

```bash
git add iosApp/Sources/LocalVideo/LocalVideoStatus.swift iosApp/Sources/LocalVideo/LocalVideoSection.swift \
        iosApp/Sources/ClipList/ClipListView.swift iosApp/Sources/LocalVideo/LocalPlayerView.swift \
        iosApp/Sources/RootView.swift iosApp/Tests/LocalVideoStatusTests.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): analyze buttons, progress status, result dialog, retry routing"
```

---

### Task 6: Version bump to 0.4.0 (12) + changelog

**Files:**
- Modify: `Config/Version.xcconfig`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Bump**

`Config/Version.xcconfig`: `MARKETING_VERSION = 0.4.0`, `CURRENT_PROJECT_VERSION = 12` (comment header unchanged).

- [ ] **Step 2: Changelog**

Under `## [Unreleased]` → `### Added`, at the top of the list:
```markdown
- iOS: full analyze pipeline — 12-point court mapping, resumable upload with
  progress, live processing status, and failure/retry dialogs. iOS and Android
  are now at full feature parity.
- Shared: analyze orchestration (AnalyzeCoordinator) and court-marking geometry
  promoted to the shared module.
```
Existing entries untouched.

- [ ] **Step 3: Verify**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`, then `xattr -cr iosApp` + the standard xcodebuild test command, then `plutil -p iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app/Info.plist | grep -E 'CFBundleShortVersionString|CFBundleVersion'` after a fresh build.
Expected: all green; 0.4.0 / 12.

- [ ] **Step 4: Commit**

```bash
git add Config/Version.xcconfig CHANGELOG.md
git commit -m "chore(version): bump to 0.4.0 (12) — analyze pipeline parity"
```

---

## Verification checklist (end of plan)

1. Shared: moved suites green in `:shared:jvmTest` (CourtMarkingModelTest + AnalyzeCoordinatorTest + acknowledgeResult); iosTest channel suite green on `:shared:iosSimulatorArm64Test`; framework links.
2. Android: full suites green with import-only production diffs; behavior unchanged.
3. iOS unit: CourtTapMathTests + LocalVideoStatusTests + all prior suites green.
4. Milestone acceptance (user walkthrough): import a short real match video → Analyze → tap the 12 court points (pinch-zoom works) → Start Analysis → watch "Uploading N%…" then "Analyzing N%…" → the match's rally clips appear; a no-rallies/failure case shows the dialog with Retry; killing the app mid-upload and relaunching resumes; screen stays awake during upload.
5. Both platforms report Version 0.4.0 (12); tag `v0.4.0` when releasing.

## Known interop watch-points (for the executor)

- SKIE: Kotlin `object` → `.shared` accessor (`CourtMarkingSpec.shared`); `List<Long>` may bridge as `[KotlinLong]`/`[NSNumber]` (`UInt32(truncating:)`); `Float?` fields as `KotlinFloat?` (`.floatValue`); `StateFlow<Map<String, AnalyzeProgress>>` elements may need `as? [String: AnalyzeProgress]`.
- Kotlin data-class `copy` is NOT Swift-friendly — that's why `acknowledgeResult` exists; never bridge `update(id) { copy(...) }` closures from Swift.
- `CourtMarkingState` is immutable — every `place/undo/clear` returns a new instance; always reassign the `@State`.
- ktor's `ByteChannel`/`close` signatures vary slightly across versions — the iosTest contract (full read, offset read, missing-file message) is the source of truth, not the exact API calls in the plan.
- `navigationDestination(item:)` requires iOS 17 — available (min target is 17.0).
