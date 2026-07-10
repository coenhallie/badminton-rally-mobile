# iOS Local Video (Capture, Library, Player) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Android's local-video features to iOS — record/import, an "On this phone" library section, and a local player with on-device annotations and frame-accurate stepping — by promoting the persistence layer to `shared/commonMain` and building SwiftUI on top.

**Architecture:** Approach A from the spec: `LocalVideoEntry`/`LocalAnnotation` models and both local repositories move to `shared/commonMain` (byte-compatible JSON in multiplatform-settings; Android keeps its data), exposed as `RallyApp.localVideos`/`RallyApp.localAnnotations`. iOS adds a file store under `Documents/LocalVideos/` (entries persist paths relative to Documents), PHPicker/camera intake, and a local player whose frame stepping is a pure, tested Swift port of Android's `seekFrames` math. No analyze UI on iOS (milestone 3).

**Tech Stack:** Kotlin 2.3.20 KMP (multiplatform-settings, kotlinx-serialization, SKIE), SwiftUI iOS 17 (`@Observable`), PhotosUI (PHPicker), UIKit camera capture, AVFoundation (AVPlayer zero-tolerance seek, AVAssetImageGenerator), XCTest, kotest/kotlin-test in commonTest.

**Spec:** `docs/plans/2026-07-11-ios-local-video-design.md`

## Global Constraints

- **Persistence compatibility is a hard requirement:** Settings keys `"local_videos"` / `"local_annotations"`, field names, and JSON shapes stay exactly as today — existing Android installs must keep their data across the update.
- Enum order of `AnalyzeStep { UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING }` and values of `AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED, ANALYZED }` are load-bearing (analyze retry uses ordinal comparison) — preserve verbatim.
- No analyze UI on iOS: no Analyze buttons, no stage badges/status text, no result dialog. iOS entries stay `AnalyzeStage.LOCAL`.
- Exact user-facing copy (byte-for-byte): size cap **"Video is larger than 1GB. Please use a shorter recording."**; playback error **"Couldn't play this video. The file may have been moved or deleted."**; storage note **"Annotations are saved on this phone and are removed if you remove the video from the app."**; menu items **"Record video"**, **"Import video"**, **"Remove from app"**; section header **"On this phone"**; empty state **"No matches yet. Record one with the + button above."**; delete dialog title **"Delete annotation?"**.
- Size cap constant: `1_073_741_824` bytes (1 GB).
- iOS files: `Documents/LocalVideos/<uuid>.mp4`; the entry `uri` field stores the path **relative to Documents** (e.g. `LocalVideos/<uuid>.mp4`), never absolute.
- Recording display name pattern: `shuttl_<epochMillis>.mp4`; recordings are ALSO saved to the Photos library (failure non-fatal).
- Version bump happens ONLY in the final task: `Config/Version.xcconfig` → `MARKETING_VERSION = 0.3.0`, `CURRENT_PROJECT_VERSION = 10`.
- ENVIRONMENT: prefix every xcodebuild/iOS-link Gradle command with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; run `xattr -cr iosApp` before each xcodebuild; simulator device `export DEVICE="iPhone 17 Pro"`; test command: `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=$DEVICE" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO`. Install/launch: `xcrun simctl install booted iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/iosApp.app && xcrun simctl launch booted com.badmintontracker.ios`. After adding/removing Swift files run `cd iosApp && xcodegen generate && cd ..` and commit the regenerated `iosApp/iosApp.xcodeproj`.
- Commit hygiene: stage only the files each task names.
- Camera capture cannot run on the simulator; simulator verification covers import, and camera paths are verified by code inspection + the availability guard.

---

### Task 1: Shared concurrency + id/time helpers

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/util/SyncLock.kt`
- Create: `shared/src/androidMain/kotlin/com/badmintontracker/shared/util/SyncLock.android.kt`
- Create: `shared/src/jvmMain/kotlin/com/badmintontracker/shared/util/SyncLock.jvm.kt`
- Create: `shared/src/iosMain/kotlin/com/badmintontracker/shared/util/SyncLock.ios.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/util/Ids.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/util/IdsTest.kt`

**Interfaces:**
- Produces: `internal class SyncLock` with `lock()`/`unlock()` and `internal inline fun <T> SyncLock.withLock(block: () -> T): T` in `com.badmintontracker.shared.util`.
- Produces: `internal fun randomUuid(): String` and `internal fun nowEpochMs(): Long` in `com.badmintontracker.shared.util`. Task 2's repositories consume all three.

- [ ] **Step 1: Write the failing test**

`shared/src/commonTest/kotlin/com/badmintontracker/shared/util/IdsTest.kt`:
```kotlin
package com.badmintontracker.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {
    @Test
    fun randomUuid_is_36_chars_and_unique() {
        val a = randomUuid()
        val b = randomUuid()
        assertEquals(36, a.length)
        assertNotEquals(a, b)
    }

    @Test
    fun nowEpochMs_is_plausible() {
        // Any moment after 2020-01-01 and monotonic-ish.
        assertTrue(nowEpochMs() > 1_577_836_800_000)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.util.IdsTest" 2>&1 | tail -5`
Expected: FAIL — unresolved reference `randomUuid` (compilation error is the failing state).

- [ ] **Step 3: Implement**

`shared/src/commonMain/kotlin/com/badmintontracker/shared/util/Ids.kt`:
```kotlin
package com.badmintontracker.shared.util

import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun randomUuid(): String = Uuid.random().toString()

internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
```
(If the compiler reports that `ExperimentalUuidApi` no longer exists because `kotlin.uuid` was stabilized, drop the `@OptIn` line and the import. If `kotlinx.datetime.Clock` is deprecated in favor of `kotlin.time.Clock` in this repo's kotlinx-datetime version, prefer whichever compiles without warnings — the repo currently uses kotlinx-datetime 0.6.1 where `Clock.System` is correct.)

`shared/src/commonMain/kotlin/com/badmintontracker/shared/util/SyncLock.kt`:
```kotlin
package com.badmintontracker.shared.util

/** Tiny portable mutual-exclusion lock for repository read-modify-write cycles. */
internal expect class SyncLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> SyncLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
```

`shared/src/androidMain/kotlin/com/badmintontracker/shared/util/SyncLock.android.kt`:
```kotlin
package com.badmintontracker.shared.util

import java.util.concurrent.locks.ReentrantLock

internal actual class SyncLock {
    private val delegate = ReentrantLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
```

`shared/src/jvmMain/kotlin/com/badmintontracker/shared/util/SyncLock.jvm.kt` — identical content to the androidMain actual above (same package, same body; the two JVM-family source sets each need their own actual because the default KMP hierarchy has no shared JVM intermediate — this small duplication is deliberate).

`shared/src/iosMain/kotlin/com/badmintontracker/shared/util/SyncLock.ios.kt`:
```kotlin
package com.badmintontracker.shared.util

import platform.Foundation.NSRecursiveLock

internal actual class SyncLock {
    private val delegate = NSRecursiveLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
```

- [ ] **Step 4: Run tests to verify they pass (all targets compile)**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.util.IdsTest" && export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileDebugKotlinAndroid 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL for both (if the Android compile task has a different name in this AGP version, find it with `./gradlew :shared:tasks --all | grep -i compile`).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/util \
        shared/src/androidMain/kotlin/com/badmintontracker/shared/util \
        shared/src/jvmMain/kotlin/com/badmintontracker/shared/util \
        shared/src/iosMain/kotlin/com/badmintontracker/shared/util \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/util
git commit -m "feat(shared): portable SyncLock, randomUuid, nowEpochMs helpers"
```

---

### Task 2: Promote local-video models + repositories to shared/commonMain

**Files:**
- Move (git mv, then edit): `androidApp/src/main/java/com/badmintontracker/android/localvideo/{LocalVideoEntry,LocalAnnotation,LocalVideoRepository,LocalAnnotationsRepository}.kt` → `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/`
- Move (git mv, then edit): `androidApp/src/test/java/com/badmintontracker/android/localvideo/{LocalVideoRepositoryTest,LocalAnnotationsRepositoryTest}.kt` → `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`
- Modify (imports only): every remaining `androidApp` file that references the moved types — known set: `localvideo/{AnalyzeCoordinator,LocalPlayerScreen,LocalPlayerViewModel,LocalVideoListViewModel,LocalVideoSection,VideoIntake}.kt`, `localvideo/court/*.kt` (whichever reference `LocalVideoEntry`), `AuthGate.kt`, `cliplist/ClipListScreen.kt`, and test files `localvideo/{AnalyzeCoordinatorTest,LocalVideoListViewModelTest,LocalPlayerViewModelTest}.kt` plus any `court/*Test.kt` the compiler flags.

**Interfaces:**
- Produces (in `com.badmintontracker.shared.localvideo`, public API unchanged):
  - `enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED, ANALYZED }`
  - `enum class AnalyzeStep { UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING }`
  - `@Serializable data class LocalVideoEntry(id, uri, displayName, durationMs, sizeBytes, addedAtEpochMs, keypoints: CourtKeypoints? = null, stage: AnalyzeStage = LOCAL, failedStep: AnalyzeStep? = null, failureMessage: String? = null, resultSeen: Boolean = false)`
  - `@Serializable data class LocalAnnotation(id, timestampSeconds: Float, body: String, kind: AnnotationKind? = null, createdAtEpochMs: Long)`
  - `class LocalVideoRepository(settings: Settings)` — `entries: StateFlow<List<LocalVideoEntry>>`, `add`, `update(id, transform)`, `remove(id)`, `get(id)`
  - `class LocalAnnotationsRepository(settings: Settings)` — `byVideoId: StateFlow<Map<String, List<LocalAnnotation>>>`, `annotationsFor`, `hasAnnotations`, `add(videoId, timestampSeconds, body, kind): LocalAnnotation`, `delete(videoId, annotationId)`, `removeAllFor(videoId)`
- Produces: `RallyApp.localVideos: LocalVideoRepository` and `RallyApp.localAnnotations: LocalAnnotationsRepository` — Tasks 3-5 consume these from Swift (`rally.localVideos.entries` arrives via SKIE as an AsyncSequence of `[LocalVideoEntry]`).
- Consumes: `SyncLock`/`withLock`, `randomUuid()`, `nowEpochMs()` from Task 1.

- [ ] **Step 1: Move the four main files and adjust them**

```bash
mkdir -p shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo
A=androidApp/src/main/java/com/badmintontracker/android/localvideo
S=shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo
git mv $A/LocalVideoEntry.kt $A/LocalAnnotation.kt $A/LocalVideoRepository.kt $A/LocalAnnotationsRepository.kt $S/
```

Then edit each moved file:
1. All four: change the package line to `package com.badmintontracker.shared.localvideo`.
2. `LocalVideoRepository.kt` — replace the lock field and `mutate`:
   - `private val lock = Any()` → `private val lock = SyncLock()`
   - `private fun mutate(...) = synchronized(lock) { ... }` → `private fun mutate(...) = lock.withLock { ... }` (body unchanged)
   - add imports `com.badmintontracker.shared.util.SyncLock` and `com.badmintontracker.shared.util.withLock`.
3. `LocalAnnotationsRepository.kt`:
   - same lock replacement (`Any()`→`SyncLock()`, both `synchronized(lock) {`→`lock.withLock {` in `removeAllFor` and `mutate`)
   - `import java.util.UUID` → remove; `id = UUID.randomUUID().toString()` → `id = randomUuid()`
   - `createdAtEpochMs = System.currentTimeMillis()` → `createdAtEpochMs = nowEpochMs()`
   - add imports `com.badmintontracker.shared.util.SyncLock`, `com.badmintontracker.shared.util.withLock`, `com.badmintontracker.shared.util.randomUuid`, `com.badmintontracker.shared.util.nowEpochMs`.
4. Nothing else changes — same Settings keys (`"local_videos"`, `"local_annotations"`), serializers, sorting, and corrupt-JSON fallbacks (this is the persistence-compatibility guarantee).

- [ ] **Step 2: Move the two test files and adjust them**

```bash
mkdir -p shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo
AT=androidApp/src/test/java/com/badmintontracker/android/localvideo
ST=shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo
git mv $AT/LocalVideoRepositoryTest.kt $AT/LocalAnnotationsRepositoryTest.kt $ST/
```

Edits in both files:
1. Package line → `package com.badmintontracker.shared.localvideo`.
2. `runBlocking` is JVM/native-only, not common — convert the concurrency tests to `kotlinx.coroutines.test.runTest`: replace `import kotlinx.coroutines.runBlocking` with `import kotlinx.coroutines.test.runTest`, and each
   ```kotlin
   runBlocking {
       withContext(Dispatchers.Default) { ... }
   }
   ```
   becomes
   ```kotlin
   runTest {
       withContext(Dispatchers.Default) { ... }
   }
   ```
   (bodies unchanged — `withContext(Dispatchers.Default)` inside `runTest` runs the launches on real parallel threads on JVM, preserving the lost-write detection).
3. Everything else stays verbatim (kotest matchers and `MapSettings` are already commonTest dependencies).

- [ ] **Step 3: Run the moved tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.localvideo.*" 2>&1 | tail -4`
Expected: BUILD SUCCESSFUL (13 tests: 6 repository + 7 annotations... exact count 6+9=15 — trust the green, not the count).

- [ ] **Step 4: Expose the repositories on RallyApp**

In `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt` add imports
`com.badmintontracker.shared.localvideo.LocalVideoRepository` and
`com.badmintontracker.shared.localvideo.LocalAnnotationsRepository`, and after the
`authState` property add:
```kotlin
    // On-device local video registry + annotations (shared persistence, native UI).
    val localVideos:      LocalVideoRepository       = LocalVideoRepository(settings)
    val localAnnotations: LocalAnnotationsRepository = LocalAnnotationsRepository(settings)
```
Note: `settings` is currently a plain constructor parameter; change the declaration to `private val settings: Settings` in the constructor so the properties can use it.

- [ ] **Step 5: Update Android to consume the shared classes**

`androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`:
- Delete imports `com.badmintontracker.android.localvideo.LocalAnnotationsRepository` and `com.badmintontracker.android.localvideo.LocalVideoRepository`; add `com.badmintontracker.shared.localvideo.LocalAnnotationsRepository` and `com.badmintontracker.shared.localvideo.LocalVideoRepository`.
- Replace the two constructions with references to the shared instances:
  ```kotlin
  localVideos = rally.localVideos
  localAnnotations = rally.localAnnotations
  ```
  (keep the `lateinit var` fields and their types — only the assignment source changes; same `Settings` instance flows through `RallyApp`, so stored data is untouched).

Then fix the remaining references mechanically: run
`./gradlew :androidApp:compileDebugKotlin 2>&1 | grep "Unresolved reference"` and for every flagged file add the matching import(s) from:
```
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.AnalyzeStep
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
```
The moved types were previously same-package (no imports), so the known set is: `localvideo/{AnalyzeCoordinator,LocalPlayerScreen,LocalPlayerViewModel,LocalVideoListViewModel,LocalVideoSection,VideoIntake}.kt`, `court/` files referencing `LocalVideoEntry`, `AuthGate.kt`, `cliplist/ClipListScreen.kt`, and the three test files. Iterate until the compiler is clean. Do NOT change any call sites — only imports.

- [ ] **Step 6: Full verification**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug && export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL everywhere — all Android tests (including AnalyzeCoordinator/LocalPlayerViewModel/LocalVideoListViewModel suites) still green with zero behavior change, and the iOS framework links with the new API.

- [ ] **Step 7: Commit**

```bash
git add -A -- shared/src androidApp/src
git commit -m "refactor(shared): promote local video models and repositories to commonMain"
```
(`git add -A` is safe here scoped to the two source trees: this task's changes are moves + import edits within them; verify with `git status --short` that nothing unrelated is listed first.)

---

### Task 3: iOS file store + intake logic (pure parts, TDD)

**Files:**
- Create: `iosApp/Sources/LocalVideo/LocalVideoFiles.swift`
- Create: `iosApp/Sources/LocalVideo/LocalVideoLogic.swift`
- Create: `iosApp/Sources/LocalVideo/LocalVideoIntake.swift`
- Test: `iosApp/Tests/LocalVideoLogicTests.swift`

**Interfaces:**
- Consumes: `rally.localVideos` (`LocalVideoRepository`) from Task 2; `LocalVideoEntry` initializer from the Shared framework (Swift: `LocalVideoEntry(id:uri:displayName:durationMs:sizeBytes:addedAtEpochMs:keypoints:stage:failedStep:failureMessage:resultSeen:)` — pass `keypoints: nil, stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false`).
- Produces: `LocalVideoFiles` (`directory`, `resolve(relativePath:)`, `store(tempURL:) throws -> String`, `delete(relativePath:)`), `LocalVideoLogic.oversizeMessage(bytes:) -> String?`, `LocalVideoLogic.formatDuration(ms:) -> String`, `LocalVideoIntake` (`@MainActor @Observable`, `error: String?`, `func add(tempURL:suggestedName:isRecording:) async`). Tasks 4-5 consume all of these.

- [ ] **Step 1: Write the failing tests**

`iosApp/Tests/LocalVideoLogicTests.swift`:
```swift
import XCTest
@testable import iosApp

final class LocalVideoLogicTests: XCTestCase {
    func testOversizeMessageExactlyAtCapIsAllowed() {
        XCTAssertNil(LocalVideoLogic.oversizeMessage(bytes: 1_073_741_824))
    }

    func testOversizeMessageAboveCap() {
        XCTAssertEqual(
            LocalVideoLogic.oversizeMessage(bytes: 1_073_741_825),
            "Video is larger than 1GB. Please use a shorter recording."
        )
    }

    func testFormatDuration() {
        XCTAssertEqual(LocalVideoLogic.formatDuration(ms: 65_000), "1:05")
        XCTAssertEqual(LocalVideoLogic.formatDuration(ms: 0), "0:00")
        XCTAssertEqual(LocalVideoLogic.formatDuration(ms: 599_999), "9:59")
    }

    func testRelativePathRoundTrip() {
        let rel = "LocalVideos/abc.mp4"
        let url = LocalVideoFiles.resolve(relativePath: rel)
        XCTAssertTrue(url.path.hasSuffix("/Documents/LocalVideos/abc.mp4"))
        XCTAssertFalse(rel.hasPrefix("/"))
    }

    func testStoreCopiesIntoLocalVideosAndDeleteRemoves() throws {
        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("store-test-\(UUID().uuidString).mp4")
        try Data([0x00, 0x01]).write(to: temp)
        let rel = try LocalVideoFiles.store(tempURL: temp)
        XCTAssertTrue(rel.hasPrefix("LocalVideos/"))
        let stored = LocalVideoFiles.resolve(relativePath: rel)
        XCTAssertTrue(FileManager.default.fileExists(atPath: stored.path))
        LocalVideoFiles.delete(relativePath: rel)
        XCTAssertFalse(FileManager.default.fileExists(atPath: stored.path))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run (after `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` and with the environment exports from Global Constraints): the standard xcodebuild test command.
Expected: compile FAILURE — `LocalVideoLogic`/`LocalVideoFiles` not defined.

- [ ] **Step 3: Implement the pure logic and file store**

`iosApp/Sources/LocalVideo/LocalVideoLogic.swift`:
```swift
import Foundation

enum LocalVideoLogic {
    /// Same cap as Android's VideoIntake (and the web app): 1 GB.
    static let maxSizeBytes: Int64 = 1_073_741_824

    /// Returns the user-facing rejection message, or nil when the size is acceptable.
    static func oversizeMessage(bytes: Int64) -> String? {
        bytes > maxSizeBytes
            ? "Video is larger than 1GB. Please use a shorter recording."
            : nil
    }

    /// m:ss — matches Android's LocalVideoListViewModel.formatDuration.
    static func formatDuration(ms: Int64) -> String {
        let totalSec = ms / 1000
        return String(format: "%d:%02d", totalSec / 60, totalSec % 60)
    }
}
```

`iosApp/Sources/LocalVideo/LocalVideoFiles.swift`:
```swift
import Foundation

/// File store for on-device videos. Entries persist paths RELATIVE to Documents
/// (the app container path changes across app updates).
enum LocalVideoFiles {
    static let folderName = "LocalVideos"

    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static var directory: URL {
        documents.appendingPathComponent(folderName, isDirectory: true)
    }

    static func resolve(relativePath: String) -> URL {
        documents.appendingPathComponent(relativePath)
    }

    /// Moves (or copies, if moving across volumes fails) the temp file into the
    /// store under a fresh UUID name. Returns the relative path to persist.
    static func store(tempURL: URL) throws -> String {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = "\(UUID().uuidString).mp4"
        let dest = directory.appendingPathComponent(name)
        do {
            try FileManager.default.moveItem(at: tempURL, to: dest)
        } catch {
            try FileManager.default.copyItem(at: tempURL, to: dest)
        }
        return "\(folderName)/\(name)"
    }

    static func delete(relativePath: String) {
        try? FileManager.default.removeItem(at: resolve(relativePath: relativePath))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: the standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **` (new LocalVideoLogicTests + existing MatchGroupingTests/SmokeTests).

- [ ] **Step 5: Implement the intake model (integration glue, no unit test)**

`iosApp/Sources/LocalVideo/LocalVideoIntake.swift`:
```swift
import AVFoundation
import Foundation
import Photos
import Shared

/// Mirrors Android's VideoIntake.addEntryFromUri: copy into the store, extract
/// metadata, enforce the 1 GB cap, persist a LOCAL-stage entry.
@MainActor @Observable
final class LocalVideoIntake {
    let rally: RallyApp
    var error: String? = nil

    init(rally: RallyApp) { self.rally = rally }

    /// tempURL: file handed over by the picker/camera (consumed by this call).
    /// suggestedName: picker-provided name; recordings pass nil and get the
    /// shuttl_<epochMillis>.mp4 pattern. isRecording additionally saves to Photos.
    func add(tempURL: URL, suggestedName: String?, isRecording: Bool) async {
        error = nil
        let sizeBytes = (try? FileManager.default
            .attributesOfItem(atPath: tempURL.path)[.size] as? Int64).flatMap { $0 } ?? 0
        if let message = LocalVideoLogic.oversizeMessage(bytes: sizeBytes) {
            try? FileManager.default.removeItem(at: tempURL)
            error = message
            return
        }

        let epochMs = Int64(Date().timeIntervalSince1970 * 1000)
        let displayName = suggestedName ?? "shuttl_\(epochMs).mp4"

        if isRecording {
            await saveToPhotos(tempURL) // best-effort; in-app copy is authoritative
        }

        let relativePath: String
        do {
            relativePath = try LocalVideoFiles.store(tempURL: tempURL)
        } catch {
            self.error = "Couldn't save the video. Please try again."
            return
        }

        let durationMs = await loadDurationMs(LocalVideoFiles.resolve(relativePath: relativePath))
        rally.localVideos.add(entry: LocalVideoEntry(
            id: UUID().uuidString,
            uri: relativePath,
            displayName: displayName,
            durationMs: durationMs,
            sizeBytes: sizeBytes,
            addedAtEpochMs: epochMs,
            keypoints: nil,
            stage: .local,
            failedStep: nil,
            failureMessage: nil,
            resultSeen: false
        ))
    }

    func remove(entry: LocalVideoEntry) {
        rally.localVideos.remove(id: entry.id)
        rally.localAnnotations.removeAllFor(videoId: entry.id)
        LocalVideoFiles.delete(relativePath: entry.uri)
    }

    /// Best-effort, mirrors Android's runCatching retriever (0 on failure).
    private func loadDurationMs(_ url: URL) async -> Int64 {
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration) else { return 0 }
        let seconds = CMTimeGetSeconds(duration)
        return seconds.isFinite ? Int64(seconds * 1000) : 0
    }

    private func saveToPhotos(_ url: URL) async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return }
        // Copy first: creationRequestForAssetFromVideo needs the file to outlive
        // the change block, and store(tempURL:) moves the original afterwards.
        let photosCopy = FileManager.default.temporaryDirectory
            .appendingPathComponent("photos-\(UUID().uuidString).mp4")
        guard (try? FileManager.default.copyItem(at: url, to: photosCopy)) != nil else { return }
        try? await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: photosCopy)
        }
        try? FileManager.default.removeItem(at: photosCopy)
    }
}
```
Note the SKIE call forms: `rally.localVideos.add(entry:)`, `rally.localVideos.remove(id:)`, `rally.localAnnotations.removeAllFor(videoId:)` — if the compiler asks for different argument labels, use what autocomplete offers on the generated header; behavior must stay identical. `"Couldn't save the video. Please try again."` is new iOS-only copy (Android's copy failure can't happen with content URIs) — keep exactly this string.

- [ ] **Step 6: Build to verify the intake compiles, then run the suite once**

Run: standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/LocalVideo iosApp/Tests/LocalVideoLogicTests.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): local video file store, intake logic, and 1GB cap with tests"
```

---

### Task 4: Pickers, camera, and clip-list integration

**Files:**
- Create: `iosApp/Sources/LocalVideo/VideoPicker.swift`
- Create: `iosApp/Sources/LocalVideo/CameraRecorder.swift`
- Create: `iosApp/Sources/LocalVideo/LocalThumbnails.swift`
- Create: `iosApp/Sources/LocalVideo/LocalVideoSection.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Modify: `iosApp/project.yml` (Info.plist usage strings)

**Interfaces:**
- Consumes: `LocalVideoIntake` (Task 3), `rally.localVideos.entries` (Task 2), `Shuttl` theme + `ErrorBanner` (existing), `LocalVideoLogic.formatDuration(ms:)`, `formatMatchDate(millis:)` (existing, pinned locale).
- Produces: `VideoPicker(onPicked: (URL, String?) -> Void)` SwiftUI representable; `CameraRecorder(onRecorded: (URL) -> Void)` representable; `LocalThumbnails` (`@MainActor @Observable`, `image(for entry:) -> UIImage?` + `load(for entry:) async`); `LocalVideoRowView`; `LocalPlayerRoute: Hashable` navigation value. Task 5 consumes `LocalPlayerRoute`.

- [ ] **Step 1: Info.plist usage strings**

In `iosApp/project.yml`, add to `targets.iosApp.info.properties`:
```yaml
        NSCameraUsageDescription: Shuttl uses the camera to record match videos.
        NSMicrophoneUsageDescription: Shuttl records audio with your match videos.
        NSPhotoLibraryAddUsageDescription: Shuttl saves your recordings to the Photos library.
```

- [ ] **Step 2: Implement the two representables**

`iosApp/Sources/LocalVideo/VideoPicker.swift`:
```swift
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// PHPicker (videos only) — no permission prompt; hands over a temp file copy.
struct VideoPicker: UIViewControllerRepresentable {
    let onPicked: (URL, String?) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .videos
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: VideoPicker
        init(_ parent: VideoPicker) { self.parent = parent }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            parent.dismiss()
            guard let provider = results.first?.itemProvider,
                  provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) else { return }
            let suggestedName = provider.suggestedName.map { "\($0).mp4" }
            provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                guard let url else { return }
                // The provider deletes its file when this closure returns — move it out now.
                let temp = FileManager.default.temporaryDirectory
                    .appendingPathComponent("import-\(UUID().uuidString).mp4")
                guard (try? FileManager.default.copyItem(at: url, to: temp)) != nil else { return }
                DispatchQueue.main.async {
                    self.parent.onPicked(temp, suggestedName ?? "video.mp4")
                }
            }
        }
    }
}
```

`iosApp/Sources/LocalVideo/CameraRecorder.swift`:
```swift
import SwiftUI
import UniformTypeIdentifiers

/// System camera in movie mode — mirrors Android's ActivityResultContracts.CaptureVideo.
struct CameraRecorder: UIViewControllerRepresentable {
    let onRecorded: (URL) -> Void
    @Environment(\.dismiss) private var dismiss

    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.movie.identifier]
        picker.videoQuality = .typeHigh
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraRecorder
        init(_ parent: CameraRecorder) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            parent.dismiss()
            if let url = info[.mediaURL] as? URL {
                parent.onRecorded(url)
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}
```

- [ ] **Step 3: Implement local thumbnails**

`iosApp/Sources/LocalVideo/LocalThumbnails.swift`:
```swift
import AVFoundation
import Shared
import SwiftUI

/// First-frame thumbnails for local videos (Android uses Coil's VideoFrameDecoder).
@MainActor @Observable
final class LocalThumbnails {
    private(set) var images: [String: UIImage] = [:]   // entry id -> frame

    func load(for entry: LocalVideoEntry) async {
        guard images[entry.id] == nil else { return }
        let url = LocalVideoFiles.resolve(relativePath: entry.uri)
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 192, height: 108)
        let time = CMTime(value: 1, timescale: 10) // 100ms in, like Android court marking
        if let cgImage = try? await generator.image(at: time).image {
            images[entry.id] = UIImage(cgImage: cgImage)
        }
    }
}
```

- [ ] **Step 4: Implement the list section + row**

`iosApp/Sources/LocalVideo/LocalVideoSection.swift`:
```swift
import Shared
import SwiftUI

/// Navigation value for opening the local player (distinct from the String
/// destination used for remote match ids).
struct LocalPlayerRoute: Hashable {
    let entryId: String
}

struct LocalVideoRowView: View {
    let entry: LocalVideoEntry
    let thumbnails: LocalThumbnails
    let onRemove: () -> Void

    private var subtitle: String {
        let duration = LocalVideoLogic.formatDuration(ms: entry.durationMs)
        let date = formatMatchDate(millis: entry.addedAtEpochMs)
        return "\(duration) · \(date)".uppercased()
    }

    var body: some View {
        NavigationLink(value: LocalPlayerRoute(entryId: entry.id)) {
            HStack(spacing: 12) {
                Group {
                    if let image = thumbnails.images[entry.id] {
                        Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
                    } else {
                        Shuttl.bgTertiary
                    }
                }
                .frame(width: 96, height: 54)
                .clipped()
                .task { await thumbnails.load(for: entry) }

                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.displayName)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.system(size: 11, weight: .medium))
                        .kerning(0.55)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                Spacer()
                Menu {
                    Button("Remove from app", role: .destructive) { onRemove() }
                } label: {
                    Image(systemName: "ellipsis")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Local video menu")
            }
        }
    }
}

extension LocalVideoEntry: Identifiable {}
```

- [ ] **Step 5: Wire into ClipListView**

Modify `iosApp/Sources/ClipList/ClipListView.swift`:

1. Add state and the intake next to the existing properties:
```swift
    @State private var intake: LocalVideoIntake?
    @State private var thumbnails = LocalThumbnails()
    @State private var localEntries: [LocalVideoEntry] = []
    @State private var showImporter = false
    @State private var showRecorder = false
```
2. In the `.task` block (after the model creation), create the intake and start observing local entries by adding a second task modifier after the existing one:
```swift
        .task {
            if intake == nil { intake = LocalVideoIntake(rally: rally) }
            for await entries in rally.localVideos.entries {
                localEntries = entries
            }
        }
```
3. Add the **+** toolbar menu as a second `ToolbarItem` BEFORE the existing ellipsis one:
```swift
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Record video") {
                        if CameraRecorder.isAvailable {
                            showRecorder = true
                        } else {
                            intake?.error = "Camera is not available on this device."
                        }
                    }
                    Button("Import video") { showImporter = true }
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add video")
            }
```
4. Present the pickers (after `.sheet(item: $shareTarget)`):
```swift
        .sheet(isPresented: $showImporter) {
            VideoPicker { tempURL, suggestedName in
                Task { await intake?.add(tempURL: tempURL, suggestedName: suggestedName, isRecording: false) }
            }
        }
        .fullScreenCover(isPresented: $showRecorder) {
            CameraRecorder { tempURL in
                Task { await intake?.add(tempURL: tempURL, suggestedName: nil, isRecording: true) }
            }
            .ignoresSafeArea()
        }
```
5. In `content(_:)`, render the intake error and the local section at the TOP of the List (before the remote error banner):
```swift
            if let intakeError = intake?.error {
                ErrorBanner(message: intakeError)
                    .listRowInsets(EdgeInsets())
            }
            if !localEntries.isEmpty {
                Section {
                    ForEach(localEntries) { entry in
                        LocalVideoRowView(entry: entry, thumbnails: thumbnails) {
                            intake?.remove(entry: entry)
                        }
                    }
                } header: { Shuttl.sectionLabel("On this phone") }
            }
```
6. Update the empty state: the condition gains `localEntries.isEmpty` and the copy becomes Android's full string:
```swift
            if localEntries.isEmpty && model.owned.isEmpty && model.shared.isEmpty && !model.isRefreshing {
                Text("No matches yet. Record one with the + button above.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
```
7. Register the local-player destination next to the existing String one (Task 5 provides the real view; until then add a placeholder at the bottom of this file, which Task 5 deletes):
```swift
        .navigationDestination(for: LocalPlayerRoute.self) { route in
            LocalPlayerView(rally: rally, entryId: route.entryId)
        }
```
```swift
struct LocalPlayerView: View {
    let rally: RallyApp
    let entryId: String
    var body: some View { Text("Local player — TODO Task 5") }
}
```

- [ ] **Step 6: Build, test, and verify import end-to-end in the simulator**

Run: `cd iosApp && xcodegen generate && cd ..` then the standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **`.

Then build/install/launch (Global Constraints recipe) and verify live: tap **+** → "Import video" → pick any video from the simulator's photo library (add one first with `xcrun simctl addmedia booted <some .mp4>` if empty — any small mp4 works, e.g. one exported from the repo owner's data or generated with `ffmpeg` if available; otherwise verify with a screenshot that the picker presents). After import: an "On this phone" section appears with the row (name, duration · date, thumbnail); "Remove from app" deletes it. "Record video" on the simulator must surface the banner "Camera is not available on this device." Screenshot each state and Read the screenshots to confirm.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/LocalVideo iosApp/Sources/ClipList/ClipListView.swift iosApp/project.yml iosApp/iosApp.xcodeproj
git commit -m "feat(ios): record/import intake and On-this-phone library section"
```

---

### Task 5: Local player — FrameStepMath (TDD), FrameStepBar, annotations

**Files:**
- Create: `iosApp/Sources/LocalVideo/FrameStepMath.swift`
- Create: `iosApp/Sources/LocalVideo/FrameStepBar.swift`
- Create: `iosApp/Sources/LocalVideo/LocalPlayerModel.swift`
- Create: `iosApp/Sources/LocalVideo/LocalPlayerView.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift` (delete the `LocalPlayerView` placeholder)
- Test: `iosApp/Tests/FrameStepMathTests.swift`

**Interfaces:**
- Consumes: `LocalPlayerRoute` navigation (Task 4), `rally.localVideos.get(id:)`, `rally.localAnnotations` (`byVideoId`, `add(videoId:timestampSeconds:body:kind:)`, `delete(videoId:annotationId:)`), `LocalVideoFiles.resolve(relativePath:)` (Task 3), existing `AddAnnotationSheet` (callback `(AnnotationKind?, String) -> Void`), `KindBadge`, `formatTimestamp(_:)`, `Shuttl` theme, `ErrorBanner`, `PrimaryButtonStyle`.
- Produces: `FrameStepMath.targetSeconds(currentSeconds:fps:delta:durationSeconds:) -> Double`; final `LocalPlayerView(rally:entryId:)`.

- [ ] **Step 1: Write the failing FrameStepMath tests**

`iosApp/Tests/FrameStepMathTests.swift`:
```swift
import XCTest
@testable import iosApp

/// Port of Android FrameStepBar.seekFrames semantics (see the Kotlin source
/// for the two slack rules: half-frame index offset + land 1ms past the PTS).
final class FrameStepMathTests: XCTestCase {
    private let fps30Dur = 1.0 / 30.0

    func testStepForwardFromTruncatedPosition() {
        // Frame 1 at 30fps has PTS 33.333ms; a truncating player reports 0.033.
        // +1 must land in frame 2 (not re-seek frame 1 and get stuck).
        let target = FrameStepMath.targetSeconds(
            currentSeconds: 0.033, fps: 30, delta: 1, durationSeconds: 10
        )
        XCTAssertEqual(target, 2.0 * fps30Dur + 0.001, accuracy: 0.0001)
    }

    func testRepeatedForwardStepsNeverStick() {
        // Simulate a player that truncates positions to whole milliseconds.
        var position = 0.0
        var frames: [Int] = []
        for _ in 0..<5 {
            let target = FrameStepMath.targetSeconds(
                currentSeconds: position, fps: 30, delta: 1, durationSeconds: 60
            )
            frames.append(Int(target / fps30Dur))
            position = (target * 1000).rounded(.down) / 1000  // ms truncation
        }
        XCTAssertEqual(frames, [1, 2, 3, 4, 5])
    }

    func testBackwardClampsAtZero() {
        let target = FrameStepMath.targetSeconds(
            currentSeconds: 0, fps: 30, delta: -1, durationSeconds: 10
        )
        XCTAssertEqual(target, 0.001, accuracy: 0.0001)   // frame 0 + 1ms slack
    }

    func testForwardClampsAtDuration() {
        let target = FrameStepMath.targetSeconds(
            currentSeconds: 0.99, fps: 30, delta: 300, durationSeconds: 1.0
        )
        XCTAssertEqual(target, 1.0, accuracy: 0.0001)
    }

    func testFpsFallbackTo30() {
        let withZeroFps = FrameStepMath.targetSeconds(
            currentSeconds: 0.033, fps: 0, delta: 1, durationSeconds: 10
        )
        let with30Fps = FrameStepMath.targetSeconds(
            currentSeconds: 0.033, fps: 30, delta: 1, durationSeconds: 10
        )
        XCTAssertEqual(withZeroFps, with30Fps, accuracy: 0.000001)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command.
Expected: compile FAILURE — `FrameStepMath` not defined.

- [ ] **Step 3: Implement FrameStepMath**

`iosApp/Sources/LocalVideo/FrameStepMath.swift`:
```swift
import Foundation

/// Pure port of Android FrameStepBar.seekFrames (androidApp .../clipdetail/FrameStepBar.kt):
///   1. add half a frame before dividing so a truncated reported position still
///      maps to the frame that is actually displayed;
///   2. target 1ms past the frame's start so an exact seek lands inside the
///      frame's display interval, not on the boundary.
enum FrameStepMath {
    static func targetSeconds(
        currentSeconds: Double,
        fps: Float,
        delta: Int64,
        durationSeconds: Double
    ) -> Double {
        let effectiveFps = fps > 0 ? Double(fps) : 30.0
        let frameDur = 1.0 / effectiveFps
        let currentFrame = Int64((currentSeconds + frameDur / 2.0) / frameDur)
        let next = max(0, currentFrame + delta)
        let target = Double(next) * frameDur + 0.001
        let maxPos = durationSeconds > 0 ? durationSeconds : .greatestFiniteMagnitude
        return min(max(target, 0), maxPos)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **` (FrameStepMathTests 5/5 + all prior suites).

- [ ] **Step 5: Implement the player model**

`iosApp/Sources/LocalVideo/LocalPlayerModel.swift`:
```swift
import AVFoundation
import Foundation
import Shared

@Observable @MainActor
final class LocalPlayerModel {
    let rally: RallyApp
    let entry: LocalVideoEntry
    private(set) var player: AVPlayer
    private(set) var annotations: [LocalAnnotation] = []
    var playbackError: String? = nil
    private var fps: Float = 0
    private var statusObservation: NSKeyValueObservation?

    init(rally: RallyApp, entry: LocalVideoEntry) {
        self.rally = rally
        self.entry = entry
        self.player = AVPlayer(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        observeFailure()
    }

    func loadMetadata() async {
        let asset = AVURLAsset(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        if let track = try? await asset.loadTracks(withMediaType: .video).first,
           let rate = try? await track.load(.nominalFrameRate) {
            fps = rate
        }
    }

    func observeAnnotations() async {
        for await map in rally.localAnnotations.byVideoId {
            annotations = (map[entry.id] as? [LocalAnnotation]) ?? []
        }
    }

    func currentTimestampSeconds() -> Float {
        let time = player.currentTime()
        guard time.isNumeric else { return 0 }
        return max(0, Float(CMTimeGetSeconds(time)))
    }

    func add(kind: AnnotationKind?, body: String, atSeconds: Float) {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty && kind == nil { return }
        _ = rally.localAnnotations.add(
            videoId: entry.id,
            timestampSeconds: max(0, atSeconds),
            body: trimmed,
            kind: kind
        )
    }

    func delete(annotationId: String) {
        rally.localAnnotations.delete(videoId: entry.id, annotationId: annotationId)
    }

    func seek(toSeconds seconds: Float) {
        player.seek(
            to: CMTime(seconds: Double(seconds), preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func stepFrames(_ delta: Int64) {
        if player.rate != 0 { player.pause() }
        let current = CMTimeGetSeconds(player.currentTime())
        let durationTime = player.currentItem?.duration
        let duration = (durationTime?.isNumeric == true) ? CMTimeGetSeconds(durationTime!) : 0
        let target = FrameStepMath.targetSeconds(
            currentSeconds: current.isFinite ? current : 0,
            fps: fps,
            delta: delta,
            durationSeconds: duration.isFinite ? duration : 0
        )
        player.seek(
            to: CMTime(seconds: target, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func retry() {
        playbackError = nil
        statusObservation?.invalidate()
        player.pause()
        player = AVPlayer(url: LocalVideoFiles.resolve(relativePath: entry.uri))
        observeFailure()
    }

    private func observeFailure() {
        statusObservation = player.currentItem?.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor [weak self] in
                self?.playbackError = "Couldn't play this video. The file may have been moved or deleted."
            }
        }
    }
}
```
(SKIE bridging note: `byVideoId` elements should arrive as `[String: [LocalAnnotation]]`; the defensive `as?` cast covers `NSDictionary` bridging. If the compiler rejects the cast because the type is already exact, drop the cast: `annotations = map[entry.id] ?? []`.)

- [ ] **Step 6: Implement the FrameStepBar**

`iosApp/Sources/LocalVideo/FrameStepBar.swift`:
```swift
import SwiftUI

/// Port of Android's FrameStepBar: tap = single frame step; hold-left = -3
/// frames every 100ms; hold-right = real playback until release. 400ms hold
/// activation, like Android's HoldButton.
struct FrameStepBar: View {
    let model: LocalPlayerModel

    var body: some View {
        HStack(spacing: 0) {
            HoldButton(
                text: "Previous frame",
                onTap: { model.stepFrames(-1) },
                onHoldTick: { model.stepFrames(-3) }
            )
            HoldButton(
                text: "Next frame",
                onTap: { model.stepFrames(1) },
                onHoldActivate: { model.player.play() },
                onRelease: { if model.player.rate != 0 { model.player.pause() } }
            )
        }
    }
}

private struct HoldButton: View {
    let text: String
    var onTap: () -> Void = {}
    var onHoldActivate: () -> Void = {}
    var onHoldTick: () -> Void = {}
    var onRelease: () -> Void = {}
    @State private var pressed = false
    @State private var holdTask: Task<Void, Never>? = nil

    var body: some View {
        Text(text)
            .font(.body.weight(.semibold))
            .foregroundStyle(Shuttl.text)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(pressed ? Shuttl.bgSecondary : Shuttl.bgTertiary)
            .overlay(Rectangle().stroke(Shuttl.borderSecondary, lineWidth: 1))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        onTap()
                        holdTask = Task {
                            try? await Task.sleep(for: .milliseconds(400))
                            guard !Task.isCancelled else { return }
                            onHoldActivate()
                            while !Task.isCancelled {
                                onHoldTick()
                                try? await Task.sleep(for: .milliseconds(100))
                            }
                        }
                    }
                    .onEnded { _ in
                        holdTask?.cancel()
                        holdTask = nil
                        pressed = false
                        onRelease()
                    }
            )
    }
}
```

- [ ] **Step 7: Implement the player view; delete the Task 4 placeholder**

`iosApp/Sources/LocalVideo/LocalPlayerView.swift`:
```swift
import AVKit
import Shared
import SwiftUI

struct LocalPlayerView: View {
    let rally: RallyApp
    let entryId: String
    @Environment(\.dismiss) private var dismiss
    @State private var model: LocalPlayerModel?
    @State private var addSheetTimestamp: Float? = nil
    @State private var deleteTarget: LocalAnnotation? = nil

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                SplashView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard model == nil else { return }
            guard let entry = rally.localVideos.get(id: entryId) else {
                dismiss()   // entry vanished — mirror Android's popBackStack
                return
            }
            let m = LocalPlayerModel(rally: rally, entry: entry)
            model = m
            await m.loadMetadata()
            await m.observeAnnotations()
        }
    }

    @ViewBuilder
    private func content(_ model: LocalPlayerModel) -> some View {
        VStack(spacing: 0) {
            ZStack {
                VideoPlayer(player: model.player)
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .background(Color.black)
                if let error = model.playbackError {
                    VStack(spacing: 8) {
                        ErrorBanner(message: error)
                        Button("Retry") { model.retry() }
                            .buttonStyle(PrimaryButtonStyle())
                            .frame(maxWidth: 160)
                    }
                    .padding(16)
                }
            }

            FrameStepBar(model: model)

            List {
                ForEach(model.annotations, id: \.id) { annotation in
                    annotationRow(annotation, model: model)
                }
                Text("Annotations are saved on this phone and are removed if you remove the video from the app.")
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textTertiary)
            }
            .listStyle(.plain)
        }
        .navigationTitle(model.entry.displayName)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    addSheetTimestamp = model.currentTimestampSeconds()
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add annotation")
            }
        }
        .sheet(item: $addSheetTimestamp) { timestamp in
            AddAnnotationSheet { kind, body in
                model.add(kind: kind, body: body, atSeconds: timestamp)
            }
            .presentationDetents([.medium])
        }
        .alert("Delete annotation?", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let target = deleteTarget { model.delete(annotationId: target.id) }
                deleteTarget = nil
            }
        } message: {
            if let target = deleteTarget {
                Text(target.body.isEmpty ? "This annotation" : "\"\(target.body)\"")
            }
        }
    }

    private func annotationRow(_ annotation: LocalAnnotation, model: LocalPlayerModel) -> some View {
        HStack(spacing: 12) {
            Text(formatTimestamp(annotation.timestampSeconds))
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Shuttl.textSecondary)
            if let kind = annotation.kind {
                KindBadge(kind: kind)
            }
            if !annotation.body.isEmpty {
                Text(annotation.body)
                    .font(.subheadline)
                    .foregroundStyle(Shuttl.text)
            }
            Spacer()
            Button {
                deleteTarget = annotation
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Delete annotation")
        }
        .contentShape(Rectangle())
        .onTapGesture { model.seek(toSeconds: annotation.timestampSeconds) }
    }
}

/// Float timestamp as a sheet item (needs Identifiable).
extension Float: @retroactive Identifiable {
    public var id: Float { self }
}
```
If the `@retroactive` conformance produces a warning or conflicts, replace the `.sheet(item:)` with a `showAddSheet: Bool` + stored `addSheetTimestamp: Float` pair (the ClipDetail pattern) — behavior must stay: the timestamp is captured at the moment the + is tapped.

Delete the `LocalPlayerView` placeholder struct at the bottom of `iosApp/Sources/ClipList/ClipListView.swift`.

- [ ] **Step 8: Full test run and simulator verification**

Run: `cd iosApp && xcodegen generate && cd .. && xattr -cr iosApp` then the standard xcodebuild test command.
Expected: `** TEST SUCCEEDED **` (all suites).

Install + launch (Global Constraints recipe). With a video imported in Task 6 of the previous task (or import one now): tap the row → the local player opens; the video plays; "Previous frame"/"Next frame" visibly step; + opens the annotation sheet; adding a kind-only and a body annotation lists them sorted by timestamp; tapping a row seeks; delete confirms with the quoted body; the storage note is visible. Screenshot the player and the annotation list and Read them.

- [ ] **Step 9: Commit**

```bash
git add iosApp/Sources/LocalVideo iosApp/Sources/ClipList/ClipListView.swift iosApp/Tests/FrameStepMathTests.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): local player with frame stepping and on-device annotations"
```

---

### Task 6: Version bump to 0.3.0 (10) + changelog

**Files:**
- Modify: `Config/Version.xcconfig`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: the shared version plumbing from milestone 1 (Android gradle parse + both version footers + CI tag check) — no code changes needed anywhere else.

- [ ] **Step 1: Bump the shared version**

In `Config/Version.xcconfig` change:
```
MARKETING_VERSION = 0.3.0
CURRENT_PROJECT_VERSION = 10
```
(keep the comment header unchanged).

- [ ] **Step 2: Changelog**

Under `## [Unreleased]` in `CHANGELOG.md`, add to the `### Added` list (create the heading only if absent; do not touch existing entries):
```markdown
- iOS: record/import local videos with an "On this phone" library section
  (1 GB cap, recordings also saved to Photos), local playback with
  frame-accurate stepping, and on-device annotations.
- Shared: local video registry and local annotations persistence promoted to
  the shared module (identical storage format; Android data is preserved).
```

- [ ] **Step 3: Verify both platforms carry the new version and all tests pass**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug && export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && xattr -cr iosApp` then the standard xcodebuild test command; finally rebuild + install + launch, open the clip-list overflow menu (do NOT sign out — the session must survive), and screenshot it.
Expected: all Gradle tasks BUILD SUCCESSFUL, `** TEST SUCCEEDED **`, and the menu shows "Version 0.3.0 (10)".

- [ ] **Step 4: Commit**

```bash
git add Config/Version.xcconfig CHANGELOG.md
git commit -m "chore(version): bump to 0.3.0 (10) for local video milestone"
```

---

## Verification checklist (end of plan)

1. Shared: `:shared:jvmTest` green including the moved localvideo suites; iOS framework links.
2. Android: full unit-test suite green with ONLY import-level diffs in app code — a fresh install and an upgraded install both see their existing local videos/annotations (same Settings keys).
3. iOS simulator walkthrough: import → row with thumbnail/duration/date → play → frame-step both directions → add kind-only + note annotations → tap-to-seek → delete annotation → "Remove from app" clears row; "Record video" on simulator shows the camera-unavailable banner.
4. Camera + Photos-save path: user-verified on a physical device (or accepted as inspected-only for now).
5. Both platforms report Version 0.3.0 (10).
6. User tags `v0.3.0` at release time; CI validates.

## Known interop watch-points (for the executor)

- SKIE call forms: `rally.localVideos.entries` / `rally.localAnnotations.byVideoId` arrive as typed AsyncSequences; repository methods may surface as members with slightly different labels (`add(entry:)` vs `add(_:)`) — follow the generated header, keep behavior identical.
- `LocalVideoEntry` construction from Swift requires ALL constructor arguments (Kotlin default values don't cross the ObjC bridge) — the plan's call in Task 3 passes every field explicitly.
- `AnalyzeStage.local` in Swift corresponds to Kotlin `LOCAL` (SKIE lowercases enum cases).
- Kotlin `Long` bridges as `Int64`, `Float` as `Float` — `durationMs`/`sizeBytes`/`addedAtEpochMs` are Int64 in Swift.
- If `kotlin.uuid`'s `ExperimentalUuidApi` opt-in is rejected (API stabilized), remove the annotation; if `Uuid` is missing entirely, fall back to an expect/actual `randomUuid` (JVM `java.util.UUID.randomUUID()`, iOS `NSUUID().UUIDString.lowercased()`).
