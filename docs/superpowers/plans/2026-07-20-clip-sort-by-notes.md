# Sort Rally Clips by Note Count Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a sort menu to the rally-clips page (both platforms) with two options: Rally order (default) and Most notes (annotation count descending, rally index tie-break).

**Architecture:** UI-only change. `RallyClip.annotationCount` already exists on the shared Kotlin model. Each platform gets a small, unit-tested sort helper (`ClipSort` enum + sort function) plus a top-bar menu wired to ephemeral view-local state. No shared-module, repository, or backend changes.

**Tech Stack:** Kotlin/Jetpack Compose (Material3 `DropdownMenu`), Swift/SwiftUI (`Menu` + `Picker`), kotest matchers + kotlin.test (Android unit tests), XCTest (iOS unit tests), XcodeGen.

**Spec:** `docs/superpowers/specs/2026-07-20-clip-sort-by-notes-design.md`

## Global Constraints

- Sort options and exact labels: "Rally order" (default, `rallyIndex` ascending) and "Most notes" (`annotationCount` descending, tie-break `rallyIndex` ascending).
- Sort choice is ephemeral — plain view-local state, no persistence.
- Android app only ships material-icons-core (no extended icons). Use `Icons.AutoMirrored.Filled.List` for the sort button.
- iOS build environment: prefix `xcodebuild` with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, run `xattr -cr iosApp` first, and pass `CODE_SIGNING_ALLOWED=NO` (see memory: sandbox xattrs break codesign; xcode-select points at CLT).
- Never hardcode `DEVELOPER_DIR` inside committed files — shell-only.

---

### Task 1: Android sort helper (`ClipSort` + `sortClips`)

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt` (add top-level enum + function at end of file)
- Test: `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipSortTest.kt` (create)

**Interfaces:**
- Consumes: `com.badmintontracker.shared.model.RallyClip` (existing; fields `rallyIndex: Int`, `annotationCount: Int`)
- Produces: `enum class ClipSort { RallyOrder, MostNotes }` and `fun sortClips(clips: List<RallyClip>, sort: ClipSort): List<RallyClip>` — both top-level in `MatchClipsScreen.kt`, used by Task 2.

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipSortTest.kt`:

```kotlin
package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class ClipSortTest {
    private fun clip(id: String, rallyIndex: Int, notes: Int) = RallyClip(
        id = id, videoId = "v", ownerId = "me", rallyIndex = rallyIndex,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = notes,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    @Test
    fun rally_order_sorts_by_rally_index() {
        val sorted = sortClips(listOf(clip("b", 2, 5), clip("a", 1, 0)), ClipSort.RallyOrder)
        sorted.map { it.id } shouldBe listOf("a", "b")
    }

    @Test
    fun most_notes_sorts_descending_with_rally_index_tiebreak() {
        val sorted = sortClips(
            listOf(clip("c", 3, 1), clip("a", 1, 1), clip("b", 2, 3)),
            ClipSort.MostNotes,
        )
        sorted.map { it.id } shouldBe listOf("b", "a", "c")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.ClipSortTest"`
Expected: FAIL to compile — `Unresolved reference: sortClips` / `Unresolved reference: ClipSort`

- [ ] **Step 3: Write minimal implementation**

Append to the end of `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt`:

```kotlin
enum class ClipSort { RallyOrder, MostNotes }

fun sortClips(clips: List<RallyClip>, sort: ClipSort): List<RallyClip> = when (sort) {
    ClipSort.RallyOrder -> clips.sortedBy { it.rallyIndex }
    ClipSort.MostNotes -> clips.sortedWith(
        compareByDescending<RallyClip> { it.annotationCount }.thenBy { it.rallyIndex },
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.ClipSortTest"`
Expected: BUILD SUCCESSFUL, 2 tests pass

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/ClipSortTest.kt
git commit -m "feat(android): ClipSort helper for rally clips — rally order / most notes"
```

---

### Task 2: Android sort menu in MatchClipsScreen top bar

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt`

**Interfaces:**
- Consumes: `ClipSort`, `sortClips(...)` from Task 1.
- Produces: nothing new — UI wiring only.

- [ ] **Step 1: Add sort state and switch the list computation**

In `MatchClipsScreen` (composable body), next to the existing `var sheetOpen` state add:

```kotlin
var sortMenuOpen by remember { mutableStateOf(false) }
var sort by remember { mutableStateOf(ClipSort.RallyOrder) }
```

Replace the existing `clipsForMatch` computation:

```kotlin
val clipsForMatch = state.clips
    .filter { it.videoId == videoId }
    .sortedBy { it.rallyIndex }
```

with:

```kotlin
val clipsForMatch = sortClips(state.clips.filter { it.videoId == videoId }, sort)
```

- [ ] **Step 2: Add the sort menu to TopAppBar actions**

In the `TopAppBar` `actions` block, insert BEFORE the existing share `if (match?.isOwned == true)` block:

```kotlin
IconButton(onClick = { sortMenuOpen = true }) {
    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sort")
}
DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
    DropdownMenuItem(
        text = { Text("Rally order") },
        leadingIcon = {
            if (sort == ClipSort.RallyOrder) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        },
        onClick = { sort = ClipSort.RallyOrder; sortMenuOpen = false },
    )
    DropdownMenuItem(
        text = { Text("Most notes") },
        leadingIcon = {
            if (sort == ClipSort.MostNotes) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        },
        onClick = { sort = ClipSort.MostNotes; sortMenuOpen = false },
    )
}
```

Add these imports (keep the file's alphabetical import order):

```kotlin
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
```

- [ ] **Step 3: Verify it compiles and tests still pass**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no test failures

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchClipsScreen.kt
git commit -m "feat(android): sort menu on match clips page — rally order / most notes"
```

---

### Task 3: iOS sort helper (`ClipSort.swift`)

**Files:**
- Create: `iosApp/Sources/ClipList/ClipSort.swift`
- Test: `iosApp/Tests/ClipSortTests.swift` (create)
- Modify: `iosApp/iosApp.xcodeproj` (regenerated via `xcodegen`, not hand-edited)

**Interfaces:**
- Consumes: `ClipInfo` (existing Swift mirror in `MatchGrouping.swift`, fields `rallyIndex: Int32`, `annotationCount: Int32`); `RallyClip` from the Shared Kotlin framework (same fields as `Int32` in Swift).
- Produces: `protocol ClipSortKeys` (`rallyIndex`, `annotationCount`), `enum ClipSort { case rallyOrder, mostNotes }` with `func sorted<T: ClipSortKeys>(_ clips: [T]) -> [T]`, and conformances `RallyClip: ClipSortKeys`, `ClipInfo: ClipSortKeys`. Used by Task 4.

- [ ] **Step 1: Write the failing test**

Create `iosApp/Tests/ClipSortTests.swift`:

```swift
import XCTest
@testable import iosApp

final class ClipSortTests: XCTestCase {
    private func clip(id: String, rallyIndex: Int32, notes: Int32) -> ClipInfo {
        ClipInfo(
            id: id, videoId: "v", ownerId: "me", rallyIndex: rallyIndex,
            createdAtMillis: 0, title: nil, durationSeconds: 10,
            annotationCount: notes
        )
    }

    func testRallyOrderSortsByRallyIndex() {
        let sorted = ClipSort.rallyOrder.sorted([
            clip(id: "b", rallyIndex: 2, notes: 5),
            clip(id: "a", rallyIndex: 1, notes: 0),
        ])
        XCTAssertEqual(sorted.map(\.id), ["a", "b"])
    }

    func testMostNotesSortsDescendingWithRallyIndexTiebreak() {
        let sorted = ClipSort.mostNotes.sorted([
            clip(id: "c", rallyIndex: 3, notes: 1),
            clip(id: "a", rallyIndex: 1, notes: 1),
            clip(id: "b", rallyIndex: 2, notes: 3),
        ])
        XCTAssertEqual(sorted.map(\.id), ["b", "a", "c"])
    }
}
```

- [ ] **Step 2: Write the implementation** (created together with the test because running iOS tests requires regenerating the Xcode project either way; the "verify it fails" signal is compile failure, which is not worth a 5-minute xcodebuild cycle)

Create `iosApp/Sources/ClipList/ClipSort.swift`:

```swift
import Foundation
import Shared

/// Sort keys as a protocol so sorting is unit-testable via ClipInfo without Kotlin construction.
protocol ClipSortKeys {
    var rallyIndex: Int32 { get }
    var annotationCount: Int32 { get }
}

extension RallyClip: ClipSortKeys {}
extension ClipInfo: ClipSortKeys {}

enum ClipSort {
    case rallyOrder
    case mostNotes

    func sorted<T: ClipSortKeys>(_ clips: [T]) -> [T] {
        switch self {
        case .rallyOrder:
            return clips.sorted { $0.rallyIndex < $1.rallyIndex }
        case .mostNotes:
            return clips.sorted {
                if $0.annotationCount != $1.annotationCount {
                    return $0.annotationCount > $1.annotationCount
                }
                return $0.rallyIndex < $1.rallyIndex
            }
        }
    }
}
```

- [ ] **Step 3: Regenerate the Xcode project so both new files are included**

Run: `cd iosApp && xcodegen generate && cd ..`
Expected: `Created project at .../iosApp.xcodeproj`

- [ ] **Step 4: Run the iOS tests**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData \
  -only-testing:iosAppTests/ClipSortTests \
  CODE_SIGNING_ALLOWED=NO
```

Expected: `** TEST SUCCEEDED **` with 2 tests passed

- [ ] **Step 5: Commit**

```bash
git add iosApp/Sources/ClipList/ClipSort.swift iosApp/Tests/ClipSortTests.swift iosApp/iosApp.xcodeproj
git commit -m "feat(ios): ClipSort helper for rally clips — rally order / most notes"
```

---

### Task 4: iOS sort menu in MatchClipsView toolbar

**Files:**
- Modify: `iosApp/Sources/ClipList/MatchClipsView.swift`

**Interfaces:**
- Consumes: `ClipSort` from Task 3 (`ClipSort.rallyOrder`, `.mostNotes`, `.sorted(_:)`).
- Produces: nothing new — UI wiring only.

- [ ] **Step 1: Add sort state, computed sorted list, and toolbar menu**

In `MatchClipsView.swift`:

Add state below the existing `@State private var clips`:

```swift
@State private var sort: ClipSort = .rallyOrder

private var sortedClips: [RallyClip] { sort.sorted(clips) }
```

Change the `ForEach` to iterate the computed property:

```swift
ForEach(sortedClips, id: \.id) { clip in
```

In the `.task` observer, drop the eager sort (the computed property owns ordering now):

```swift
.task {
    for await latest in rally.clips.observeClips() {
        clips = latest.filter { $0.videoId == videoId }
    }
}
```

Add a toolbar between `.navigationBarTitleDisplayMode(.inline)` and `.task`:

```swift
.toolbar {
    ToolbarItem(placement: .topBarTrailing) {
        Menu {
            Picker("Sort", selection: $sort) {
                Text("Rally order").tag(ClipSort.rallyOrder)
                Text("Most notes").tag(ClipSort.mostNotes)
            }
        } label: {
            Image(systemName: "arrow.up.arrow.down")
        }
    }
}
```

- [ ] **Step 2: Build the app to verify it compiles**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData \
  CODE_SIGNING_ALLOWED=NO
```

Expected: `** BUILD SUCCEEDED **`

- [ ] **Step 3: Commit**

```bash
git add iosApp/Sources/ClipList/MatchClipsView.swift
git commit -m "feat(ios): sort menu on match clips page — rally order / most notes"
```

---

### Final verification (manual, per spec)

- Android: launch in emulator, open a match, tap the sort icon, choose "Most notes" — clips reorder by note count descending; equal-note clips stay in rally order. Reopen the match — back to rally order.
- iOS: same flow in the simulator via the toolbar menu.
