# Scoreboard label scope - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner mark each label as usable on the scoreboard, on clips, or
both, so the courtside board draws a small chosen set that fits without
scrolling, with `Note` always reachable.

**Architecture:** A `usage` column on `annotation_labels`, defaulting to `both`,
surfaces as a `LabelUsage` enum derived from a raw string on `AnnotationLabel`.
`AnnotationLabelsRepository` gains two derived `StateFlow`s (`scoreboardLabels`,
`clipLabels`) so no call site filters for itself. Both boards swap their
horizontally scrolling label row for a wrapping container and move `Note` out to
the fixed caption row. The Labels screen gains a three-way segmented control and
becomes the only place a label is created, on both platforms.

**Tech Stack:** Kotlin Multiplatform (`shared`), Jetpack Compose + Material 3
(`androidApp`), SwiftUI (`iosApp`), Supabase Postgres, kotlinx.serialization,
kotest matchers + `kotlin.test` (shared/Android), XCTest (iOS).

**Spec:** `docs/plans/2026-08-30-scoreboard-label-scope-design.md`

## Global Constraints

- **No em dashes** in any prose you write - code comments, commit messages,
  changelog entries, doc text. Use a plain dash `-`. This is a repo-wide rule.
- **Never run `supabase db push`.** The migration is committed here; applying it
  to the hosted project is the owner's step. Surface it in the final report.
- **No agent attribution in commits.** No `Co-Authored-By` trailer, no
  "Generated with Claude Code" footer.
- **`DEVELOPER_DIR` is required for every iOS command.** `xcode-select` on this
  machine points at the CommandLineTools. Prefix with
  `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- **Run `xattr -cr iosApp` before any iOS build.** Sandbox-created files carry
  `com.apple.provenance` xattrs that break codesign.
- **Scope values are exactly** `both`, `scoreboard`, `clips`. Enforced by a DB
  CHECK and mirrored by `LabelUsage`.
- **`AnnotationLabel` stores the raw string `usage` and derives `scope`.** Never
  make `usage` a `@Serializable enum` - the list is decoded with
  `decodeList<AnnotationLabel>()`, so one unknown value would take down the whole
  palette instead of one row.
- **Unknown or absent scope resolves to `LabelUsage.BOTH`**, never to an error
  and never to "hidden everywhere".
- **Kotlin default arguments do not cross the ObjC interop boundary.** Any
  parameter Swift must pass has to be explicit in the Kotlin signature.
- **Match the surrounding comment density.** This codebase writes long "why"
  comments on non-obvious decisions. Follow it; do not strip existing ones.

### Commands

| What | Command |
| --- | --- |
| Shared tests | `./gradlew :shared:jvmTest` |
| Android tests | `./gradlew :androidApp:testDebugUnitTest` |
| Android build | `./gradlew :androidApp:assembleDebug` |
| Regenerate Xcode project (after adding/removing a Swift file) | `cd iosApp && xcodegen generate && cd ..` |
| iOS tests | `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer && xattr -cr iosApp && xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "id=25A81330-906B-42F9-A880-716162614A46" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO` |

`iosApp/project.yml` declares `sources: [Sources, Assets.xcassets]` as
directories, so **adding or deleting a `.swift` file requires `xcodegen
generate`** before the build will see it. Editing an existing file does not.

---

## File Structure

**Created:**

| File | Responsibility |
| --- | --- |
| `supabase/migrations/20260830000000_label_usage.sql` | The `usage` column and its CHECK |
| `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/LabelUsage.kt` | The three scopes and the two predicates over them |
| `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/LabelUsageTest.kt` | Predicate partition |
| `iosApp/Sources/Components/ChipFlow.swift` | The wrapping layout, moved out of `PlaybackControlBar.swift` so two surfaces can share it |

**Modified:**

| File | Change |
| --- | --- |
| `shared/.../model/AnnotationLabel.kt` | `usage` field, `scope` derivation |
| `shared/.../repo/AnnotationLabelsRepository.kt` | Two derived flows, `setUsage`, `create` gains `usage` |
| `shared/src/iosMain/.../SwiftInterop.kt` | Delete name-only create overload, add `usage`, add `setLabelUsageOrMessage` |
| `androidApp/.../scoring/ScoringScreen.kt` | Wrapping label container, `Note` moves out, two-way empty state |
| `androidApp/.../scoring/ScoringViewModel.kt` | Reads `scoreboardLabels` |
| `androidApp/.../labels/LabelsScreen.kt` | Scope segmented control, draft carries scope, collapsed-row caption |
| `androidApp/.../labels/LabelsViewModel.kt` | `setUsage`, `create` carries scope |
| `androidApp/.../clipdetail/ClipDetailViewModel.kt` | Reads `clipLabels` |
| `androidApp/.../localvideo/LocalPlayerViewModel.kt` | Reads `clipLabels` |
| `iosApp/Sources/Scoring/ScoringView.swift` | Wrapping label container, `Note` moves out, two-way empty state |
| `iosApp/Sources/Scoring/ScoringModel.swift` | Reads `scoreboardLabels`, test seam renamed |
| `iosApp/Sources/Labels/LabelsView.swift` | Scope picker, draft carries scope, collapsed-row caption |
| `iosApp/Sources/Labels/LabelsModel.swift` | `setUsage`, `create` carries scope |
| `iosApp/Sources/ClipDetail/AddAnnotationSheet.swift` | Inline creation removed |
| `iosApp/Sources/ClipDetail/ClipDetailView.swift` | Drops the two removed arguments |
| `iosApp/Sources/ClipDetail/ClipDetailModel.swift` | `createLabel` deleted, reads `clipLabels` |
| `iosApp/Sources/LocalVideo/LocalPlayerView.swift` | Drops the two removed arguments |
| `iosApp/Sources/LocalVideo/LocalPlayerModel.swift` | `createLabel` deleted, reads `clipLabels` |
| `iosApp/Sources/Components/PlaybackControlBar.swift` | `ChipFlow` moves out |
| `androidApp/src/test/.../testing/FakeAnnotationLabelsRepository.kt` | New interface members |
| `CHANGELOG.md` | Unreleased entry |

---

## Task order and why

1. **Shared foundation** first - the column and the model, both backward
   compatible, nothing else touched.
2. **The iOS removal** second, *before* the repository signature change. Doing it
   in this order deletes the name-only `createLabelForSwift` overload while it
   still compiles cleanly, instead of writing a throwaway `usage` argument into
   an overload a later task deletes.
3. **The repository** third, once there are only two `create` callers left.
4-5. **The two boards**, each rewired and relaid-out together.
6-7. **The two Labels screens**, which is where scope becomes settable.
8. **The clip pickers**, the last consumers to be rewired.
9. **Changelog and on-device verification.**

Every task compiles and its whole suite passes before its commit.

---

### Task 1: The column, the enum, and the model field

**Files:**
- Create: `supabase/migrations/20260830000000_label_usage.sql`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/LabelUsage.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationLabel.kt`
- Create test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/LabelUsageTest.kt`
- Modify test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/AnnotationLabelSerializationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `LabelUsage` with `BOTH`/`SCOREBOARD`/`CLIPS`, each carrying
  `val key: String`; instance properties `onScoreboard: Boolean` and
  `onClips: Boolean`; `LabelUsage.from(key: String?): LabelUsage?`.
  `AnnotationLabel` gains `val usage: String` (defaulted) and
  `val scope: LabelUsage`.

- [ ] **Step 1: Write the migration**

Create `supabase/migrations/20260830000000_label_usage.sql`:

```sql
-- Where a label may be offered: the courtside board, the clip note pickers, or
-- both. See docs/plans/2026-08-30-scoreboard-label-scope-design.md
--
-- The default is the whole upgrade story: every label that already exists keeps
-- appearing everywhere it appears today, with no backfill statement and no
-- client-side migration. seed_annotation_labels() needs no change either - its
-- insert does not name this column, so newly signed-up accounts get 'both'.

alter table public.annotation_labels
    add column if not exists usage text not null default 'both';

-- Added separately from the column so a re-run over a database that already has
-- it still converges, matching the drop-then-add shape 20260824000000 uses.
alter table public.annotation_labels
    drop constraint if exists annotation_labels_usage_check,
    add constraint annotation_labels_usage_check
        check (usage in ('both','scoreboard','clips'));
```

Do **not** run `supabase db push`. Committing it is the whole task here.

- [ ] **Step 2: Write the failing tests**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/LabelUsageTest.kt`:

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LabelUsageTest {

    @Test
    fun both_is_offered_on_both_surfaces() {
        LabelUsage.BOTH.onScoreboard shouldBe true
        LabelUsage.BOTH.onClips shouldBe true
    }

    @Test
    fun scoreboard_is_offered_on_the_board_only() {
        LabelUsage.SCOREBOARD.onScoreboard shouldBe true
        LabelUsage.SCOREBOARD.onClips shouldBe false
    }

    @Test
    fun clips_is_offered_on_the_pickers_only() {
        LabelUsage.CLIPS.onScoreboard shouldBe false
        LabelUsage.CLIPS.onClips shouldBe true
    }

    @Test
    fun resolves_a_stored_key() {
        LabelUsage.from("scoreboard") shouldBe LabelUsage.SCOREBOARD
        LabelUsage.from("clips") shouldBe LabelUsage.CLIPS
        LabelUsage.from("both") shouldBe LabelUsage.BOTH
    }

    @Test
    fun returns_null_for_a_key_this_build_does_not_know() {
        LabelUsage.from("courtside").shouldBeNull()
        LabelUsage.from(null).shouldBeNull()
    }
}
```

Append these to
`shared/src/commonTest/kotlin/com/badmintontracker/shared/model/AnnotationLabelSerializationTest.kt`,
inside the existing class:

```kotlin
    @Test
    fun a_row_with_no_usage_key_reads_as_both() {
        // The on-disk Settings cache written by the build before this column
        // existed. Without the default this would fail to decode on the first
        // launch after upgrade, and the offline picker would come up empty.
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l3","name":"Net kill","color_key":"teal",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        label.scope shouldBe LabelUsage.BOTH
    }

    @Test
    fun usage_round_trips() {
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l4","name":"Serve","color_key":"blue",
                "created_at":"2026-08-24T12:00:00Z","usage":"scoreboard"}"""
        )

        label.scope shouldBe LabelUsage.SCOREBOARD
        json.encodeToString(AnnotationLabel.serializer(), label)
            .contains("\"usage\":\"scoreboard\"") shouldBe true
    }

    @Test
    fun an_unknown_usage_reads_as_both_and_the_list_still_decodes() {
        // The reason `usage` is a raw string and not a @Serializable enum: the
        // palette is fetched with decodeList, so one row written by a newer
        // build must not take down the other nine.
        val labels = json.decodeFromString<List<AnnotationLabel>>(
            """[{"id":"l5","name":"Drive","color_key":"red",
                 "created_at":"2026-08-24T12:00:00Z","usage":"courtside"},
                {"id":"l6","name":"Lift","color_key":"green",
                 "created_at":"2026-08-24T12:00:00Z","usage":"clips"}]"""
        )

        labels shouldHaveSize 2
        labels[0].scope shouldBe LabelUsage.BOTH
        labels[1].scope shouldBe LabelUsage.CLIPS
    }
```

Add this import to that file:

```kotlin
import io.kotest.matchers.collections.shouldHaveSize
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests '*LabelUsageTest*' --tests '*AnnotationLabelSerializationTest*'`
Expected: FAIL - `Unresolved reference: LabelUsage`, and `scope` unresolved on
`AnnotationLabel`.

- [ ] **Step 4: Write `LabelUsage`**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/LabelUsage.kt`:

```kotlin
package com.badmintontracker.shared.model

/**
 * Where a label may be offered. One value rather than two booleans: two
 * booleans admit a fourth state - neither - which is a label that exists and
 * appears nowhere, and that state has no meaning worth defending against.
 *
 * The keys match the `usage` column's CHECK constraint exactly. Nothing here
 * knows about colours or ordering; see [LabelColor] for the parallel case.
 */
enum class LabelUsage(val key: String) {
    BOTH("both"),
    SCOREBOARD("scoreboard"),
    CLIPS("clips"),
    ;

    // Written as negations rather than as whitelists so BOTH cannot drift out
    // of either set if a value is ever added. Each predicate exists once.
    val onScoreboard: Boolean get() = this != CLIPS
    val onClips: Boolean get() = this != SCOREBOARD

    companion object {
        /**
         * Resolves a stored key, or null when this build does not know it.
         * Callers fall back to [BOTH] rather than hiding the label: a chip you
         * did not expect beats a chip that has silently vanished.
         */
        fun from(key: String?): LabelUsage? = entries.firstOrNull { it.key == key }
    }
}
```

- [ ] **Step 5: Add the field to `AnnotationLabel`**

Replace the body of
`shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationLabel.kt`:

```kotlin
package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A label the signed-in user owns and may apply to annotations. */
@Serializable
data class AnnotationLabel(
    val id: String,
    val name: String,
    @SerialName("color_key")  val colorKey: String,
    @SerialName("created_at") val createdAt: Instant,
    /**
     * The raw `usage` column, not a @Serializable enum. The palette is fetched
     * with decodeList, so a value written by a newer build would take the whole
     * list down rather than one row; keeping it a string contains that to the
     * one row and lets [scope] decide what to do about it. The default covers a
     * payload with no `usage` key at all - the on-disk cache written by the
     * build before this column existed.
     */
    val usage: String = LabelUsage.BOTH.key,
) {
    /** Null when the stored key is not in this build's palette. */
    val color: LabelColor? get() = LabelColor.from(colorKey)

    /**
     * Where this label may be offered. An unknown key resolves to
     * [LabelUsage.BOTH]: the safe direction is a label that shows up somewhere
     * unexpected, not one that has disappeared with no way to find it.
     */
    val scope: LabelUsage get() = LabelUsage.from(usage) ?: LabelUsage.BOTH
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :shared:jvmTest`
Expected: PASS, whole shared suite.

- [ ] **Step 7: Confirm nothing else broke**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: PASS. `usage` is defaulted, so every existing `AnnotationLabel(...)`
call site still compiles untouched.

- [ ] **Step 8: Commit**

```bash
git add supabase/migrations/20260830000000_label_usage.sql \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/model/LabelUsage.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationLabel.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/LabelUsageTest.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/AnnotationLabelSerializationTest.kt
git commit -m "feat: labels carry a usage scope, defaulting to both"
```

---

### Task 2: Remove inline label creation from iOS

**Files:**
- Modify: `iosApp/Sources/ClipDetail/AddAnnotationSheet.swift`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailView.swift:87-97`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailModel.swift:79-92`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerView.swift:140-151`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerModel.swift:49-62`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt:69-73`
- Test: none exist for these symbols; the compiler is the check.

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `AddAnnotationSheet(labels:onAdd:)` - a three-argument initializer
  down from five. `createLabelForSwift(name:)` no longer exists;
  `createLabelForSwift(name:color:)` is the only overload.

Why this comes before the repository change: `create` gains a required `usage`
parameter in Task 3. Deleting the name-only overload now means Task 3 never has
to invent a scope for a caller that is about to disappear.

- [ ] **Step 1: Verify nothing tests these symbols**

Run:

```bash
grep -rn "createLabel\|canCreateLabel\|AddAnnotationSheet" iosApp/Tests iosApp/UITests
```

Expected: no output. If anything matches, stop and report it - the plan assumed
no coverage here.

- [ ] **Step 2: Strip `AddAnnotationSheet`**

Replace `iosApp/Sources/ClipDetail/AddAnnotationSheet.swift` entirely:

```swift
import SwiftUI
import Shared

/// Picks one label for a note and takes its body. Labels are made on the Labels
/// screen and nowhere else: a label carries a scope now (see `LabelUsage`), and
/// this sheet has no room to ask about one - so a label created here would have
/// its scope guessed on the owner's behalf. `labels` is already the clip-scoped
/// subset when this is presented.
struct AddAnnotationSheet: View {
    let labels: [AnnotationLabel]
    let onAdd: (AnnotationLabel?, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selected: AnnotationLabel? = nil
    @State private var body_ = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add note")
                .font(.title2.weight(.semibold))
            HStack(spacing: 8) {
                ForEach(labels, id: \.id) { label in
                    chip(label)
                }
            }
            TextField("Note (optional)", text: $body_, axis: .vertical)
                .padding(12)
                .background(Shuttl.bgInput)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Add") {
                    onAdd(selected, body_)
                    dismiss()
                }
                .disabled(selected == nil && body_.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private func chip(_ label: AnnotationLabel) -> some View {
        let isSelected = selected?.id == label.id
        return Button {
            selected = isSelected ? nil : label   // tapping selected chip deselects
        } label: {
            Text(label.name)
                .font(.footnote)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(isSelected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(isSelected ? .black : Shuttl.text)
        }
    }
}
```

Gone with it: `canCreateLabel`, `onCreateLabel`, the `creating` / `newName`
state and the `TextField` they gated, and the `seenIds` state with its
`.task` / `.onChange(of: labels)` pair - that pair existed only to auto-select a
label the sheet had just created.

- [ ] **Step 3: Drop the arguments at both call sites**

In `iosApp/Sources/ClipDetail/ClipDetailView.swift`, the `.sheet` body becomes:

```swift
            AddAnnotationSheet(
                labels: model.labels,
                onAdd: { label, body in
                    Task { await model.add(label: label, body: body) }
                }
            )
            .presentationDetents([.medium])
```

In `iosApp/Sources/LocalVideo/LocalPlayerView.swift`, the `.sheet(item:)` body
becomes:

```swift
            AddAnnotationSheet(
                labels: model.labels,
                onAdd: { label, body in
                    model.add(label: label, body: body, atSeconds: item.timestamp)
                }
            )
            .presentationDetents([.medium])
```

- [ ] **Step 4: Delete both `createLabel` methods**

Delete `func createLabel(_ name: String) async { ... }` from
`iosApp/Sources/ClipDetail/ClipDetailModel.swift` and from
`iosApp/Sources/LocalVideo/LocalPlayerModel.swift`, including each one's doc
comment. Both are the only reference to `createLabelForSwift(name:)` in their
file; leave every other method alone.

- [ ] **Step 5: Delete the name-only interop overload**

In `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`,
delete these two lines and any doc comment attached to them:

```kotlin
suspend fun AnnotationLabelsRepository.createLabelForSwift(name: String): CreateLabelOutcome =
    createLabelForSwift(name, color = null)
```

Leave `class CreateLabelOutcome` and the two-argument overload in place. The
Labels screen still needs `CreateLabelOutcome` to tell a rejected duplicate name
from a created row.

- [ ] **Step 6: Build and test iOS**

Run:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "id=25A81330-906B-42F9-A880-716162614A46" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: `** TEST SUCCEEDED **`. No `xcodegen generate` needed - no file was
added or removed, only edited.

- [ ] **Step 7: Commit**

```bash
git add iosApp/Sources/ClipDetail/AddAnnotationSheet.swift \
        iosApp/Sources/ClipDetail/ClipDetailView.swift \
        iosApp/Sources/ClipDetail/ClipDetailModel.swift \
        iosApp/Sources/LocalVideo/LocalPlayerView.swift \
        iosApp/Sources/LocalVideo/LocalPlayerModel.swift \
        shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt
git commit -m "refactor: labels are created on the Labels screen only, on iOS too"
```

---

### Task 3: The repository learns about scope

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepository.kt`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationLabelsRepository.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/labels/LabelsViewModel.kt`
- Modify: `iosApp/Sources/Labels/LabelsModel.swift`
- Modify: `iosApp/Sources/Labels/LabelsView.swift` (one call site only)
- Modify test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepositoryTest.kt`

**Interfaces:**
- Consumes: `LabelUsage`, `AnnotationLabel.scope` from Task 1.
- Produces:
  - `AnnotationLabelsRepository.scoreboardLabels: StateFlow<List<AnnotationLabel>>`
  - `AnnotationLabelsRepository.clipLabels: StateFlow<List<AnnotationLabel>>`
  - `AnnotationLabelsRepository.setUsage(id: String, usage: LabelUsage): Result<Unit>`
  - `AnnotationLabelsRepository.create(name: String, color: LabelColor?, usage: LabelUsage): Result<AnnotationLabel>`
  - `SwiftInteropKt.createLabelForSwift(_:name:color:usage:) -> CreateLabelOutcome`
  - `SwiftInteropKt.setLabelUsageOrMessage(_:id:usage:) -> String?`
  - `LabelsViewModel.create(name: String, color: LabelColor, usage: LabelUsage): Boolean`
  - `LabelsModel.create(_ name: String, color: LabelColor, usage: LabelUsage) async -> Bool`
  - `LabelsModel.setUsage(_ id: String, to usage: LabelUsage) async`

Tasks 6 and 7 add the UI that lets the owner choose a scope. Until then both
Labels screens pass `LabelUsage.BOTH`, which is the same behaviour they have
today.

- [ ] **Step 1: Write the failing repository tests**

Append to
`shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepositoryTest.kt`,
inside the existing class. Use the file's existing fake-client helper - read the
top of the file and follow whatever `client(...)` / row-fixture helper the
neighbouring tests use rather than inventing one.

```kotlin
    @Test
    fun scoreboard_and_clip_flows_partition_the_palette() = runTest {
        val repo = AnnotationLabelsRepositoryImpl(
            client(
                labelRow(id = "l1", name = "Good shot", color = "green", usage = "both"),
                labelRow(id = "l2", name = "Serve", color = "blue", usage = "scoreboard"),
                labelRow(id = "l3", name = "Footwork", color = "teal", usage = "clips"),
            ),
            MapSettings(),
        )
        repo.refresh()

        repo.scoreboardLabels.value.map { it.id } shouldBe listOf("l1", "l2")
        repo.clipLabels.value.map { it.id } shouldBe listOf("l1", "l3")
    }

    @Test
    fun an_unknown_usage_lands_in_both_flows() {
        // Fail-safe: a label written by a newer build is still reachable rather
        // than silently absent from every picker.
        val repo = AnnotationLabelsRepositoryImpl(
            client(labelRow(id = "l9", name = "Drive", color = "red", usage = "courtside")),
            MapSettings(),
        )
        runTest {
            repo.refresh()
            repo.scoreboardLabels.value.map { it.id } shouldBe listOf("l9")
            repo.clipLabels.value.map { it.id } shouldBe listOf("l9")
        }
    }

    @Test
    fun set_usage_moves_a_label_between_the_flows() = runTest {
        val repo = AnnotationLabelsRepositoryImpl(
            client(labelRow(id = "l1", name = "Good shot", color = "green", usage = "both")),
            MapSettings(),
        )
        repo.refresh()

        repo.setUsage("l1", LabelUsage.CLIPS).isSuccess shouldBe true

        repo.scoreboardLabels.value.shouldBeEmpty()
        repo.clipLabels.value.map { it.id } shouldBe listOf("l1")
    }

    @Test
    fun set_usage_persists_through_the_cache() = runTest {
        val settings = MapSettings()
        val warm = AnnotationLabelsRepositoryImpl(
            client(labelRow(id = "l1", name = "Good shot", color = "green", usage = "both")),
            settings,
        )
        warm.refresh()
        warm.setUsage("l1", LabelUsage.SCOREBOARD)

        // A cold start, offline: the scope has to come back off disk with the
        // label, or the board would forget the choice every launch.
        val cold = AnnotationLabelsRepositoryImpl(offlineClient(), settings)
        cold.labels.value.single().scope shouldBe LabelUsage.SCOREBOARD
    }
```

The existing file already builds fake clients and an owner-stamped `MapSettings`;
`labelRow(...)` and `offlineClient()` above stand for whatever those helpers are
actually called there. **Read the file first and use its real helper names** -
if `labelRow` does not exist, extend the existing row builder with a `usage`
parameter defaulting to `"both"` rather than adding a second builder.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :shared:jvmTest --tests '*AnnotationLabelsRepositoryTest*'`
Expected: FAIL - `scoreboardLabels`, `clipLabels`, `setUsage` unresolved.

- [ ] **Step 3: Extend the interface**

In `shared/.../repo/AnnotationLabelsRepository.kt`, the interface becomes:

```kotlin
interface AnnotationLabelsRepository {
    /** Last known list, creation order. Survives a cold start offline. */
    val labels: StateFlow<List<AnnotationLabel>>

    /**
     * The subset the courtside board may tag a rally with, and the subset the
     * clip and local-video note pickers offer.
     *
     * Derived here rather than exported as a predicate for each screen to apply:
     * six surfaces want a subset, and six copies of the same filter is six
     * chances for two of them to disagree about what "on the board" means.
     */
    val scoreboardLabels: StateFlow<List<AnnotationLabel>>
    val clipLabels: StateFlow<List<AnnotationLabel>>

    suspend fun refresh(): Result<Unit>
    /** [color] null picks a swatch automatically. */
    suspend fun create(name: String, color: LabelColor?, usage: LabelUsage): Result<AnnotationLabel>
    suspend fun rename(id: String, name: String): Result<Unit>
    suspend fun recolor(id: String, color: LabelColor): Result<Unit>
    suspend fun setUsage(id: String, usage: LabelUsage): Result<Unit>
    suspend fun delete(id: String): Result<Unit>
}
```

Add the import `com.badmintontracker.shared.model.LabelUsage`.

- [ ] **Step 4: Implement in `AnnotationLabelsRepositoryImpl`**

Add the two derived flows just below the existing `labels` declaration:

```kotlin
    /**
     * Eagerly started on the scope this class already owns, for the same reason
     * the board's own state is eager: the value has to be correct the instant a
     * screen reads it, and there is nothing to defer - it is a filter over a
     * list already in memory. See [scope]'s comment for why a coroutine that
     * never completes is acceptable on this object.
     */
    override val scoreboardLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onScoreboard } }
            .stateIn(scope, SharingStarted.Eagerly, state.value.filter { it.scope.onScoreboard })

    override val clipLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onClips } }
            .stateIn(scope, SharingStarted.Eagerly, state.value.filter { it.scope.onClips })
```

Move the `private val scope = CoroutineScope(...)` declaration **above** `state`
if the compiler complains about initialization order; keep its doc comment
attached.

Add the patch payload beside the existing `NamePatch` / `ColorPatch`:

```kotlin
    @Serializable private data class UsagePatch(val usage: String)
```

Add `setUsage`, immediately after `recolor` so the two read together:

```kotlin
    override suspend fun setUsage(id: String, usage: LabelUsage): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).update(UsagePatch(usage.key)) { filter { eq("id", id) } }
        publish(state.value.map { if (it.id == id) it.copy(usage = usage.key) else it })
    }
```

Change `create`'s signature and its insert row:

```kotlin
    @Serializable private data class NewLabelRow(
        val name: String,
        @SerialName("color_key") val colorKey: String,
        val usage: String,
    )

    override suspend fun create(
        name: String,
        color: LabelColor?,
        usage: LabelUsage,
    ): Result<AnnotationLabel> {
        val trimmed = name.trim()
        validate(trimmed)?.let { return Result.failure(it) }
        val swatch = color ?: nextUnusedColor(state.value.map { it.colorKey })
        return runCatching {
            val row = client.postgrest.from(TABLE)
                .insert(NewLabelRow(trimmed, swatch.key, usage.key)) { select() }
                .decodeSingle<AnnotationLabel>()
            publish(state.value + row)
            row
        }.recoverCatching { throw it.asDuplicateName(trimmed) }
    }
```

Add imports: `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`,
`kotlinx.coroutines.flow.SharingStarted`,
`com.badmintontracker.shared.model.LabelUsage`.

- [ ] **Step 5: Update the interop surface**

In `SwiftInterop.kt`, replace the remaining `createLabelForSwift` and add the
new setter:

```kotlin
/**
 * [color] null picks a swatch automatically. Both parameters are explicit
 * rather than defaulted because Kotlin default arguments do not cross the ObjC
 * boundary - Swift has to name them either way.
 */
suspend fun AnnotationLabelsRepository.createLabelForSwift(
    name: String,
    color: LabelColor?,
    usage: LabelUsage,
): CreateLabelOutcome =
    create(name, color, usage).fold(
        onSuccess = { CreateLabelOutcome(it, null) },
        onFailure = { CreateLabelOutcome(null, it.userFacingMessage("Couldn't add label")) },
    )

suspend fun AnnotationLabelsRepository.setLabelUsageOrMessage(id: String, usage: LabelUsage): String? =
    setUsage(id, usage).exceptionOrNull()?.let { it.userFacingMessage("Couldn't change where this label is used") }
```

Add the import `com.badmintontracker.shared.model.LabelUsage`.

- [ ] **Step 6: Update `FakeAnnotationLabelsRepository`**

In `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationLabelsRepository.kt`,
add below the existing `labels` declaration:

```kotlin
    // Derived from the same backing state as the real one, so the fake cannot
    // answer a filtering question differently from production.
    override val scoreboardLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onScoreboard } }
            .stateIn(scope, SharingStarted.Eagerly, state.value.filter { it.scope.onScoreboard })

    override val clipLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onClips } }
            .stateIn(scope, SharingStarted.Eagerly, state.value.filter { it.scope.onClips })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
```

Declare `scope` above the two flows if initialization order requires it.
`Dispatchers.Unconfined` rather than a test dispatcher: these derivations must be
readable synchronously right after construction, before any test advances a
scheduler.

Change `create` and add `setUsage`:

```kotlin
    override suspend fun create(
        name: String,
        color: LabelColor?,
        usage: LabelUsage,
    ): Result<AnnotationLabel> {
        val trimmed = name.trim()
        validate(trimmed)?.let { return Result.failure(it) }
        val swatch = color ?: AnnotationLabelsRepositoryImpl.nextUnusedColor(state.value.map { it.colorKey })
        val row = AnnotationLabel(
            id = "label-${++nextId}",
            name = trimmed,
            colorKey = swatch.key,
            createdAt = Instant.parse("2026-08-24T12:00:00Z"),
            usage = usage.key,
        )
        state.value = state.value + row
        return Result.success(row)
    }

    override suspend fun setUsage(id: String, usage: LabelUsage): Result<Unit> {
        state.value = state.value.map { if (it.id == id) it.copy(usage = usage.key) else it }
        return Result.success(Unit)
    }
```

- [ ] **Step 7: Fix the two remaining `create` callers**

`androidApp/.../labels/LabelsViewModel.kt` - change the signature and forward:

```kotlin
    suspend fun create(name: String, color: LabelColor, usage: LabelUsage): Boolean =
        viewModelScope.async {
            labels.create(name, color, usage)
```

Leave the rest of that method, including its long doc comment, untouched.

`iosApp/Sources/Labels/LabelsModel.swift`:

```swift
    @discardableResult
    func create(_ name: String, color: LabelColor, usage: LabelUsage) async -> Bool {
        guard let outcome = try? await SwiftInteropKt.createLabelForSwift(
            rally.labels, name: name, color: color, usage: usage
        ) else {
            errorMessage = "Couldn't add label"
            return false
        }
        errorMessage = outcome.errorMessage
        if let created = outcome.label {
            expanded = .existing(created.id)
            return true
        }
        return false
    }

    func setUsage(_ id: String, to usage: LabelUsage) async {
        errorMessage = try? await SwiftInteropKt.setLabelUsageOrMessage(rally.labels, id: id, usage: usage)
    }
```

`iosApp/Sources/Labels/LabelsView.swift:41` - the draft's callback becomes:

```swift
                            onCreate: { name, color in
                                await model.create(name, color: color, usage: .both)
                            }
```

Task 7 replaces `.both` with the owner's choice.

`androidApp/.../labels/LabelsScreen.kt` - `DraftLabelRow`'s `onCreate` parameter
type becomes `suspend (String, LabelColor, LabelUsage) -> Boolean`, and its
`commit()` calls `onCreate(trimmed, selectedColor, LabelUsage.BOTH)`. Task 6
replaces `BOTH` with the owner's choice.

- [ ] **Step 8: Run every suite**

Run:

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "id=25A81330-906B-42F9-A880-716162614A46" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: all PASS. Behaviour is unchanged at this point - every label is still
`both`, so both derived flows return the full list.

- [ ] **Step 9: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepository.kt \
        shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepositoryTest.kt \
        androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationLabelsRepository.kt \
        androidApp/src/main/java/com/badmintontracker/android/labels/LabelsViewModel.kt \
        androidApp/src/main/java/com/badmintontracker/android/labels/LabelsScreen.kt \
        iosApp/Sources/Labels/LabelsModel.swift \
        iosApp/Sources/Labels/LabelsView.swift
git commit -m "feat: repository exposes scoreboard and clip label subsets"
```

---

### Task 4: The Android board

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringScreen.kt:468-560`
- Test: `androidApp/src/test/java/com/badmintontracker/android/scoring/ScoringViewModelTest.kt`

**Interfaces:**
- Consumes: `scoreboardLabels` from Task 3, `LabelUsage` from Task 1.
- Produces: nothing later tasks depend on. `ScoringUiState` keeps its
  `labels: List<AnnotationLabel>` field name; only its source changes.

- [ ] **Step 1: Write the failing test**

Add to `ScoringViewModelTest`. Put the new fixture label beside the existing
`goodShot` / `forcedError` declarations:

```kotlin
    private val footwork = AnnotationLabel(
        id = "l3", name = "Footwork", colorKey = "teal", createdAt = t0,
        usage = LabelUsage.CLIPS.key,
    )
```

and the test itself:

```kotlin
    @Test
    fun the_board_offers_only_labels_scoped_to_it() = runTest {
        // The whole point of the scope: a label made for reviewing clips does
        // not take up room on a board being tapped every rally.
        val (_, _, vm) = fixture(labels = listOf(goodShot, forcedError, footwork))
        advanceUntilIdle()

        vm.state.value.labels.map { it.id } shouldBe listOf("l1", "l2")
    }
```

`fixture(...)` builds a `FakeAnnotationLabelsRepository` from its `labels`
argument - confirm that by reading it, and if it takes the list some other way,
follow what is there.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*ScoringViewModelTest*'`
Expected: FAIL - the assertion sees three ids, not two.

- [ ] **Step 3: Point the view model at the scoped flow**

In `ScoringViewModel.kt`, change the two references to `labels.labels`:

```kotlin
    val state = combine(scoreLogs.logs, labels.scoreboardLabels, pending, ::build)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            build(scoreLogs.logs.value, labels.scoreboardLabels.value, pending.value),
        )
```

and rename `build`'s second parameter from `palette` to `boardLabels` so the
name says which set it is.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*ScoringViewModelTest*'`
Expected: PASS.

- [ ] **Step 5: Rebuild `ControlBar`**

Replace `ControlBar` in `ScoringScreen.kt` with:

```kotlin
/**
 * The tag row, the note field, and the fixed action row. The tag row is on
 * screen before the rally it will tag, which is the whole reason tagging costs
 * the coach one tap rather than a dialog.
 *
 * The chips wrap rather than scroll. Only labels scoped to the board reach here
 * (see LabelUsage), so the set is small by construction - and when the coach
 * scopes one more than fits, the cost is a line of board height he can see,
 * not a chip hidden off the right edge. Note sits on the action row below,
 * outside the wrapping area: it used to be the last chip in a scrolling row,
 * which is exactly why it became unreachable.
 */
@Composable
private fun ControlBar(
    match: MatchState,
    labels: List<AnnotationLabel>,
    hasAnyLabels: Boolean,
    pendingTagOrdinal: Int?,
    canUndo: Boolean,
    noteOpen: Boolean,
    onToggleTag: (Int, AnnotationLabel) -> Unit,
    onSetComment: (Int, String) -> Unit,
    onToggleNote: () -> Unit,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    val point = pendingTagOrdinal?.let { match.points.getOrNull(it) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (labels.isEmpty()) {
                Text(
                    // Two different problems, and one sentence used to cover
                    // both: an account with no labels at all, and an account
                    // whose labels are all scoped to clips. The second would
                    // otherwise claim there are no labels while the Labels
                    // screen plainly shows several.
                    text =
                        if (hasAnyLabels) "No board labels - choose them on the labels screen."
                        else "No labels yet - add them on the labels screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    labels.forEach { label ->
                        TagChip(
                            label = label,
                            selected = point?.tags?.any { it.labelName == label.name } == true,
                            enabled = point != null,
                            onClick = { pendingTagOrdinal?.let { onToggleTag(it, label) } },
                        )
                    }
                }
            }

            if (noteOpen && point != null) {
                val ordinal = point.ordinal
                val original = remember(ordinal) { point.comment.orEmpty() }
                var draft by remember(ordinal) { mutableStateOf(original) }
                // Committed once, when the field goes away - the note moves to
                // another rally, the row closes, or the screen leaves. Committing
                // per keystroke would append one log entry per character, and undo
                // is defined as dropping the last entry, so it would then take a
                // note back a letter at a time instead of taking back the rally.
                DisposableEffect(ordinal) {
                    onDispose { if (draft != original) onSetComment(ordinal, draft) }
                }
                // Focused as it opens. Opening the note is already a deliberate
                // detour from scoring; making the coach tap twice to start typing
                // is the kind of thing that gets the feature abandoned.
                val focus = remember(ordinal) { FocusRequester() }
                LaunchedEffect(ordinal) { focus.requestFocus() }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text("Note on this rally") },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
                Text(
                    text = tagRowCaption(match, pendingTagOrdinal),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                TextButton(onClick = onToggleNote, enabled = point != null) { Text("Note") }
                // Only once the match is actually over. While it is live, finishing
                // lives in the overflow behind a confirm: a call to action sitting
                // beside a board being tapped every rally is a match ended by accident.
                if (match.isOver) {
                    Button(onClick = onDone) { Text("Done") }
                }
            }
        }
    }
}
```

Update the call site in `ScoringScreen` to pass the new parameter:

```kotlin
        ControlBar(
            match = match,
            labels = state.labels,
            hasAnyLabels = state.hasAnyLabels,
            pendingTagOrdinal = state.pendingTagOrdinal,
```

- [ ] **Step 6: Add `hasAnyLabels` to the state**

In `ScoringViewModel.kt`, add the field to `ScoringUiState`:

```kotlin
    /**
     * Whether the account has any labels at all, board-scoped or not. Lets the
     * board tell "you have not made any labels" apart from "none of yours are
     * on the board", which are different problems with different fixes.
     */
    val hasAnyLabels: Boolean = false,
```

and combine four flows instead of three:

```kotlin
    val state = combine(
        scoreLogs.logs, labels.scoreboardLabels, labels.labels, pending, ::build,
    ).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        build(scoreLogs.logs.value, labels.scoreboardLabels.value, labels.labels.value, pending.value),
    )
```

with `build` gaining an `allLabels: List<AnnotationLabel>` parameter that sets
`hasAnyLabels = allLabels.isNotEmpty()`.

- [ ] **Step 7: Fix imports**

Remove from `ScoringScreen.kt`: `androidx.compose.foundation.horizontalScroll`,
`androidx.compose.foundation.rememberScrollState`,
`androidx.compose.foundation.layout.Spacer`,
`androidx.compose.foundation.layout.width` - **only if** nothing else in the
file still uses them. Grep before deleting each one.

Add: `androidx.compose.foundation.layout.FlowRow`.

- [ ] **Step 8: Run tests and build**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/scoring/ScoringViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/scoring/ScoringViewModelTest.kt
git commit -m "feat: the Android board wraps its board labels and pins Note"
```

---

### Task 5: The iOS board

**Files:**
- Create: `iosApp/Sources/Components/ChipFlow.swift`
- Modify: `iosApp/Sources/Components/PlaybackControlBar.swift:177-230` (remove `ChipFlow`)
- Modify: `iosApp/Sources/Scoring/ScoringModel.swift`
- Modify: `iosApp/Sources/Scoring/ScoringView.swift:349-414`
- Test: `iosApp/Tests/ScoringModelTests.swift`

**Interfaces:**
- Consumes: `scoreboardLabels` from Task 3.
- Produces: `ChipFlow` as an internal type usable from any file in the target.
  `ScoringModel.init(scoreLogs:labelsRepository:scoreboardLabels:scoreLogId:)` -
  the `labels:` parameter is renamed.

- [ ] **Step 1: Move `ChipFlow` to its own file**

Cut the whole `private struct ChipFlow: Layout { ... }` declaration, including
its doc comment, out of `iosApp/Sources/Components/PlaybackControlBar.swift` and
paste it into a new `iosApp/Sources/Components/ChipFlow.swift`:

```swift
import SwiftUI

/// Chips wrap onto further lines instead of running off the container, the way
/// Android's `FlowRow` does. A plain `HStack` fits six skip intervals at the
/// default text size and nothing like it at accessibility sizes.
///
/// Lives here rather than inside `PlaybackControlBar` because the courtside
/// board needs the same behaviour, and a second copy of a wrapping layout in
/// the same target is worse than one shared one.
struct ChipFlow: Layout {
    ...body copied verbatim, with `private` dropped from the type...
}
```

Keep the nested `private struct Row` and `private func rows(...)` private -
only the type itself becomes internal. Do not change a line of its logic.

- [ ] **Step 2: Regenerate the Xcode project**

Run: `cd iosApp && xcodegen generate && cd ..`
Expected: "Created project at .../iosApp.xcodeproj". `project.yml` lists source
*directories*, so a new file is invisible until this runs.

- [ ] **Step 3: Write the failing test**

In `iosApp/Tests/ScoringModelTests.swift`, extend the label helper and add a
case:

```swift
    private func label(_ id: String, _ name: String, _ color: String,
                       _ usage: LabelUsage = .both) -> AnnotationLabel {
        AnnotationLabel(id: id, name: name, colorKey: color, createdAt: t0, usage: usage.key)
    }

    private var footwork: AnnotationLabel { label("l3", "Footwork", "teal", .clips) }
```

```swift
    func testTheBoardOffersOnlyLabelsScopedToIt() {
        // Mirrors ScoringViewModelTest.the_board_offers_only_labels_scoped_to_it.
        // The model is handed the already-scoped set: filtering is the
        // repository's job and is asserted in commonTest against it.
        let repo = SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")
        let log = repo.create(
            title: "Thu League",
            homePlayers: ["Coen"],
            awayPlayers: ["Marco"],
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(doubles: false, firstServer: .home, homeStartsRight: .first, awayStartsRight: .first)
        )
        let model = ScoringModel(
            scoreLogs: repo,
            scoreboardLabels: [goodShot, forcedError],
            scoreLogId: log.id
        )

        XCTAssertEqual(model.labels.map(\.id), ["l1", "l2"])
        XCTAssertFalse(model.labels.contains { $0.id == footwork.id })
    }
```

- [ ] **Step 4: Run to verify it fails**

Run:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "id=25A81330-906B-42F9-A880-716162614A46" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: compile failure - no `scoreboardLabels:` parameter, and
`AnnotationLabel` has no `usage:` argument in that position until Task 1's
field is picked up (it is; Task 1 shipped it).

- [ ] **Step 5: Rename the seam and read the scoped flow**

In `iosApp/Sources/Scoring/ScoringModel.swift`:

```swift
    init(
        scoreLogs: ScoreLogsRepository,
        labelsRepository: AnnotationLabelsRepository? = nil,
        scoreboardLabels: [AnnotationLabel] = [],
        scoreLogId: String
    ) {
        self.scoreLogs = scoreLogs
        self.labelsRepository = labelsRepository
        self.scoreLogId = scoreLogId
        self.scorer = MatchScorer(repo: scoreLogs, scoreLogId: scoreLogId)
        self.labels = labelsRepository?.scoreboardLabels.value ?? scoreboardLabels
        self.hasAnyLabels = !(labelsRepository?.labels.value.isEmpty ?? scoreboardLabels.isEmpty)
        readStore()
    }
```

Add the property beside `labels`:

```swift
    /// Whether the account has any labels at all, board-scoped or not. Lets the
    /// board tell "you have not made any labels" apart from "none of yours are
    /// on the board", which are different problems with different fixes.
    private(set) var hasAnyLabels: Bool = false
```

And in `start()`, follow both flows:

```swift
        if let labelsRepository {
            Task {
                for await palette in labelsRepository.scoreboardLabels {
                    labels = palette
                }
            }
            Task {
                for await all in labelsRepository.labels {
                    hasAnyLabels = !all.isEmpty
                }
            }
        }
```

Update the doc comment on `labelsRepository` to say the surface reads the
board-scoped subset.

- [ ] **Step 6: Rebuild `controlBar`**

In `iosApp/Sources/Scoring/ScoringView.swift`, replace the `ScrollView` block
and the action row:

```swift
            if model.labels.isEmpty {
                Text(
                    model.hasAnyLabels
                        ? "No board labels - choose them on the labels screen."
                        : "No labels yet - add them on the labels screen."
                )
                .font(.footnote)
                .foregroundStyle(Shuttl.textSecondary)
                .padding(.horizontal, 8)
            } else {
                // Wraps rather than scrolls. Only board-scoped labels reach
                // here, so the set is small by construction - and when one more
                // than fits is scoped, the cost is a line of board height that
                // is visible, not a chip hidden off the right edge.
                ChipFlow(spacing: 6) {
                    ForEach(model.labels, id: \.id) { label in
                        tagChip(
                            label: label,
                            selected: point?.tags.contains { $0.labelName == label.name } ?? false,
                            enabled: point != nil
                        ) {
                            if let ordinal = model.pendingTagOrdinal {
                                model.toggleTag(ordinal: ordinal, label: label)
                            }
                        }
                    }
                }
                .padding(.horizontal, 8)
            }
```

and, in the `HStack` below the note field, put `Note` before `Done`:

```swift
            HStack(spacing: 8) {
                Button("Undo") { model.undo() }
                    .disabled(!model.canUndo)
                Text(Self.caption(match, model.pendingTagOrdinal))
                    .font(.footnote)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                Spacer()
                // Outside the wrapping area on purpose: it used to be the last
                // chip in a horizontally scrolling row, which is exactly why it
                // became unreachable once a few labels existed.
                Button("Note") { noteOpen.toggle() }
                    .disabled(point == nil)
                if match.isOver {
                    Button("Done") { deliverFinish(nil) }.buttonStyle(.borderedProminent)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
```

Keep the existing comment above the `if match.isOver` branch.

- [ ] **Step 7: Run the tests**

Run:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "id=25A81330-906B-42F9-A880-716162614A46" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: `** TEST SUCCEEDED **`. `PlaybackControlBar`'s own chip row must still
render - `ChipFlow` is now internal but referenced identically.

- [ ] **Step 8: Commit**

```bash
git add iosApp/Sources/Components/ChipFlow.swift \
        iosApp/Sources/Components/PlaybackControlBar.swift \
        iosApp/Sources/Scoring/ScoringModel.swift \
        iosApp/Sources/Scoring/ScoringView.swift \
        iosApp/Tests/ScoringModelTests.swift \
        iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "feat: the iOS board wraps its board labels and pins Note"
```

---

### Task 6: The Android Labels screen

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/labels/LabelsScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/labels/LabelsViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/labels/LabelsViewModelTest.kt`

**Interfaces:**
- Consumes: `setUsage` and `create(name, color, usage)` from Task 3.
- Produces: `LabelsViewModel.setUsage(id: String, usage: LabelUsage)`.

- [ ] **Step 1: Write the failing test**

Add to `LabelsViewModelTest`:

```kotlin
    @Test
    fun setting_a_scope_writes_it_through() = runTest {
        val repo = FakeAnnotationLabelsRepository(listOf(goodShot))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.setUsage(goodShot.id, LabelUsage.SCOREBOARD)
        advanceUntilIdle()

        vm.state.value.labels.single().scope shouldBe LabelUsage.SCOREBOARD
        repo.scoreboardLabels.value.map { it.id } shouldBe listOf(goodShot.id)
        repo.clipLabels.value.shouldBeEmpty()
    }

    @Test
    fun creating_a_label_carries_its_scope() = runTest {
        val repo = FakeAnnotationLabelsRepository()
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.create("Serve", LabelColor.BLUE, LabelUsage.SCOREBOARD) shouldBe true
        advanceUntilIdle()

        repo.labels.value.single().scope shouldBe LabelUsage.SCOREBOARD
    }
```

Use whatever fixture label the file already declares in place of `goodShot` if
it is named differently, and add imports for `LabelUsage` and
`io.kotest.matchers.collections.shouldBeEmpty` as needed.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*LabelsViewModelTest*'`
Expected: FAIL - `setUsage` unresolved on the view model.

- [ ] **Step 3: Add `setUsage` to the view model**

In `LabelsViewModel.kt`, beside `recolor`:

```kotlin
    fun setUsage(id: String, usage: LabelUsage) = run { labels.setUsage(id, usage) }
```

It goes through the same private `run` helper as `rename`/`recolor`/`delete`, so
a failure reaches the snackbar through `userFacingMessage` like every other one.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*LabelsViewModelTest*'`
Expected: PASS.

- [ ] **Step 5: Add the scope control to the shared editor**

In `LabelsScreen.kt`, give `LabelEditorFields` two more parameters and render
the control between the name field and the swatch grid:

```kotlin
@Composable
private fun LabelEditorFields(
    name: String,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit,
    onFocusChanged: (FocusState) -> Unit,
    palette: List<LabelColor>,
    selectedColorKey: String?,
    onSelectColor: (LabelColor) -> Unit,
    selectedUsage: LabelUsage,
    onSelectUsage: (LabelUsage) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShuttlOutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = "Name",
            onDone = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onFocusChanged(onFocusChanged),
        )
        UsagePicker(selected = selectedUsage, onSelect = onSelectUsage)
        SwatchGrid(palette = palette, selectedKey = selectedColorKey, onSelect = onSelectColor)
    }
}

/**
 * Where this label may be offered. Same control the new-match screen uses for
 * singles/doubles, so it reads as one app rather than as a settings row.
 */
@Composable
private fun UsagePicker(selected: LabelUsage, onSelect: (LabelUsage) -> Unit) {
    val options = listOf(
        LabelUsage.BOTH to "Both",
        LabelUsage.SCOREBOARD to "Scoreboard",
        LabelUsage.CLIPS to "Clips",
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (usage, text) ->
            SegmentedButton(
                selected = selected == usage,
                onClick = { onSelect(usage) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(text) }
        }
    }
}
```

Add imports: `androidx.compose.material3.SegmentedButton`,
`androidx.compose.material3.SegmentedButtonDefaults`,
`androidx.compose.material3.SingleChoiceSegmentedButtonRow`,
`com.badmintontracker.shared.model.LabelUsage`.

- [ ] **Step 6: Wire both callers**

`LabelRow`'s expanded branch writes through immediately, exactly as recolour
does:

```kotlin
            LabelEditorFields(
                name = name,
                onNameChange = { name = it },
                onDone = ::commit,
                onFocusChanged = { state -> if (state.isFocused) hadFocus = true else if (hadFocus) commit() },
                palette = palette,
                selectedColorKey = label.colorKey,
                onSelectColor = onRecolor,
                selectedUsage = label.scope,
                onSelectUsage = onSetUsage,
            )
```

with `LabelRow` gaining an `onSetUsage: (LabelUsage) -> Unit` parameter, passed
from the `LazyColumn` as `onSetUsage = { vm.setUsage(label.id, it) }`.

`DraftLabelRow` holds it locally, like colour:

```kotlin
    var selectedUsage by remember { mutableStateOf(LabelUsage.BOTH) }
```

passed as `selectedUsage = selectedUsage, onSelectUsage = { selectedUsage = it }`,
and its `commit()` becomes `onCreate(trimmed, selectedColor, selectedUsage)`.
Change `DraftLabelRow`'s `onCreate` parameter type to
`suspend (String, LabelColor, LabelUsage) -> Boolean` (Task 3 already made it a
three-argument call passing `LabelUsage.BOTH`; this replaces the constant with
the local state).

- [ ] **Step 7: Add the collapsed-row caption**

In `LabelRow`'s collapsed `Row`, after the name `Text`:

```kotlin
            // Only on rows that are not `both`, so the common case stays quiet
            // and the caption reads as an exception rather than as a column.
            // Without it the split is invisible from the list, and "which labels
            // are on my board?" means opening every row in turn.
            val scopeCaption = when (label.scope) {
                LabelUsage.BOTH -> null
                LabelUsage.SCOREBOARD -> "BOARD"
                LabelUsage.CLIPS -> "CLIPS"
            }
            if (scopeCaption != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = scopeCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

- [ ] **Step 8: Run tests and build**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/labels/LabelsScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/labels/LabelsViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/labels/LabelsViewModelTest.kt
git commit -m "feat: choose a label's scope on the Android labels screen"
```

---

### Task 7: The iOS Labels screen

**Files:**
- Modify: `iosApp/Sources/Labels/LabelsView.swift`
- Test: `iosApp/Tests/LabelsLogicTests.swift` (no change expected; run it)

**Interfaces:**
- Consumes: `LabelsModel.setUsage(_:to:)` and
  `LabelsModel.create(_:color:usage:)` from Task 3.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the picker to `EditorFields`**

In `iosApp/Sources/Labels/LabelsView.swift`:

```swift
private struct EditorFields: View {
    @Binding var name: String
    let selectedKey: String?
    let onSelectColor: (LabelColor) -> Void
    let selectedUsage: LabelUsage
    let onSelectUsage: (LabelUsage) -> Void
    let onCommit: () -> Void

    @FocusState private var focused: Bool
    @State private var wasFocused = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Name", text: $name)
                .submitLabel(.done)
                .padding(12)
                .background(Shuttl.bgInput)
                .focused($focused)
                .onSubmit(onCommit)
                .onChange(of: focused) { _, isFocused in
                    if isFocused {
                        wasFocused = true
                    } else if wasFocused {
                        onCommit()
                    }
                }
            // Where this label may be offered. Same control the new-match
            // screen uses for singles/doubles, so it reads as one app.
            Picker("Use", selection: Binding(get: { selectedUsage }, set: onSelectUsage)) {
                Text("Both").tag(LabelUsage.both)
                Text("Scoreboard").tag(LabelUsage.scoreboard)
                Text("Clips").tag(LabelUsage.clips)
            }
            .pickerStyle(.segmented)
            SwatchGrid(selectedKey: selectedKey, onSelect: onSelectColor)
        }
        .padding(.bottom, 12)
    }
}
```

`LabelUsage` bridges to Swift as `.both` / `.scoreboard` / `.clips`. If the
generated case names differ, use whatever the `Shared` module exports - build
once and read the error.

- [ ] **Step 2: Wire `LabelEditor` (the existing-row case)**

```swift
private struct LabelEditor: View {
    let label: AnnotationLabel
    let onRename: (String) async -> Bool
    let onRecolor: (LabelColor) -> Void
    let onSetUsage: (LabelUsage) -> Void

    @State private var name: String
    @State private var commitGuard: CommitGuard

    init(
        label: AnnotationLabel,
        onRename: @escaping (String) async -> Bool,
        onRecolor: @escaping (LabelColor) -> Void,
        onSetUsage: @escaping (LabelUsage) -> Void
    ) {
        self.label = label
        self.onRename = onRename
        self.onRecolor = onRecolor
        self.onSetUsage = onSetUsage
        _name = State(initialValue: label.name)
        _commitGuard = State(initialValue: CommitGuard(lastCommitted: label.name))
    }

    var body: some View {
        EditorFields(
            name: $name,
            selectedKey: label.colorKey,
            onSelectColor: onRecolor,
            selectedUsage: label.scope,
            onSelectUsage: onSetUsage,
            onCommit: commit
        )
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let previous = commitGuard.lastCommitted
        guard commitGuard.begin(trimmed) else { return }
        Task {
            let succeeded = await onRename(trimmed)
            if !succeeded { commitGuard.failed(previous: previous) }
        }
    }
}
```

`LabelRow` gains `let onSetUsage: (LabelUsage) -> Void` and forwards it; the
`ForEach` in `LabelsView.body` passes
`onSetUsage: { usage in Task { await model.setUsage(label.id, to: usage) } }`.

- [ ] **Step 3: Wire `DraftLabelEditor` (the create case)**

```swift
private struct DraftLabelEditor: View {
    let existingColorKeys: [String]
    let onCreate: (String, LabelColor, LabelUsage) async -> Bool

    @State private var name = ""
    @State private var selected: LabelColor = LabelColor.green
    @State private var usage: LabelUsage = .both
    @State private var commitGuard = CommitGuard()

    var body: some View {
        EditorFields(
            name: $name,
            selectedKey: selected.key,
            onSelectColor: { selected = $0 },
            selectedUsage: usage,
            onSelectUsage: { usage = $0 },
            onCommit: commit
        )
        .task(id: existingColorKeys) {
            selected = AnnotationLabelsRepositoryImpl.companion.nextUnusedColor(takenKeys: existingColorKeys)
        }
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let previous = commitGuard.lastCommitted
        guard commitGuard.begin(trimmed) else { return }
        Task {
            let succeeded = await onCreate(trimmed, selected, usage)
            if !succeeded { commitGuard.failed(previous: previous) }
        }
    }
}
```

and its call site in `LabelsView.body`:

```swift
                        DraftLabelEditor(
                            existingColorKeys: model.labels.map(\.colorKey),
                            onCreate: { name, color, usage in
                                await model.create(name, color: color, usage: usage)
                            }
                        )
```

- [ ] **Step 4: Add the collapsed-row caption**

In `LabelRow`'s `HStack`, replace the bare `Spacer()` with:

```swift
                Spacer()
                // Only on rows that are not `both`, so the common case stays
                // quiet and the caption reads as an exception rather than as a
                // column. Without it the split is invisible from the list.
                if let caption = Self.scopeCaption(label.scope) {
                    Text(caption)
                        .font(.caption2)
                        .foregroundStyle(Shuttl.textSecondary)
                }
```

and add to `LabelRow`:

```swift
    private static func scopeCaption(_ scope: LabelUsage) -> String? {
        switch scope {
        case .both: return nil
        case .scoreboard: return "BOARD"
        case .clips: return "CLIPS"
        default: return nil
        }
    }
```

The `default` is there because a Kotlin enum bridges to Swift without
exhaustiveness; it is not dead code the compiler will warn about.

- [ ] **Step 5: Build and test**

Run:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "id=25A81330-906B-42F9-A880-716162614A46" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git add iosApp/Sources/Labels/LabelsView.swift
git commit -m "feat: choose a label's scope on the iOS labels screen"
```

---

### Task 8: The clip and local-video pickers read the clip subset

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt:55`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerViewModel.kt:30-31`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailModel.swift:72`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerModel.swift:42`
- Test: `androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalPlayerViewModelTest.kt`

**Interfaces:**
- Consumes: `clipLabels` from Task 3.
- Produces: nothing.

- [ ] **Step 1: Write the failing tests**

In `ClipDetailViewModelTest`, add a board-only label to whatever fixture list the
file already uses and assert it is not offered:

```kotlin
    @Test
    fun the_note_picker_offers_only_labels_scoped_to_clips() = runTest {
        val boardOnly = AnnotationLabel(
            id = "l9", name = "Serve", colorKey = "blue", createdAt = t0,
            usage = LabelUsage.SCOREBOARD.key,
        )
        val vm = fixture(labels = listOf(goodShot, boardOnly))
        advanceUntilIdle()

        vm.state.value.labels.map { it.id } shouldBe listOf("l1")
    }
```

Add the mirror case to `LocalPlayerViewModelTest`, asserting on `labelOptions`
instead of `state.value.labels`. Read both files first and match their existing
fixture helper names and the `t0` / label declarations they already have.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*ClipDetailViewModelTest*' --tests '*LocalPlayerViewModelTest*'`
Expected: FAIL - both see the board-only label.

- [ ] **Step 3: Point all four consumers at `clipLabels`**

`ClipDetailViewModel.kt:55`:

```kotlin
            labels.clipLabels.collect { list -> state.update { it.copy(labels = list) } }
```

`LocalPlayerViewModel.kt:30-31`:

```kotlin
    val labelOptions: StateFlow<List<AnnotationLabel>> = labels.clipLabels
        .stateIn(viewModelScope, SharingStarted.Eagerly, labels.clipLabels.value)
```

`ClipDetailModel.swift:72`:

```swift
        for await ls in rally.labels.clipLabels {
            labels = ls
        }
```

`LocalPlayerModel.swift:42`: the same change.

Update each one's neighbouring doc comment to say it streams the clip-scoped
subset rather than "the signed-in user's labels".

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: PASS, whole Android suite.

- [ ] **Step 5: Build iOS**

Run:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "id=25A81330-906B-42F9-A880-716162614A46" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt \
        androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt \
        androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalPlayerViewModelTest.kt \
        iosApp/Sources/ClipDetail/ClipDetailModel.swift \
        iosApp/Sources/LocalVideo/LocalPlayerModel.swift
git commit -m "feat: note pickers offer only clip-scoped labels"
```

---

### Task 9: Changelog and on-device verification

**Files:**
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Add the changelog entry**

Under `## [Unreleased]` / `### Added` in `CHANGELOG.md`, above the existing
entries:

```markdown
- Labels now carry a scope: Both, Scoreboard, or Clips, chosen on the Labels
  screen. The courtside board draws only the labels scoped to it and wraps them
  onto as many lines as they need instead of scrolling sideways, and the Note
  button has moved down beside Undo where it no longer scrolls away. Existing
  labels are all Both, so nothing moves until you say so.
```

Under `### Changed`, adding the heading if it is not already there:

```markdown
- On iOS, labels are now created on the Labels screen only. The "+ New label"
  shortcut inside the Add-note sheet is gone, which matches Android and means a
  label's name, colour and scope are always chosen together in one place.
```

- [ ] **Step 2: Run every suite one last time**

Run:

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "id=25A81330-906B-42F9-A880-716162614A46" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: all PASS.

- [ ] **Step 3: Verify on both devices**

The migration has **not** been applied to the hosted project, so before this
step either apply it (owner's call) or accept that `usage` reads as its default
everywhere. State which was done in the report.

Launch both apps per the recipes in the Commands table and walk the flow that
produced the complaint:

1. Scope two of three labels to `Scoreboard`, one to `Clips`.
2. Open the board. Both board labels are visible without scrolling; `Note` is on
   the fixed row; the board still fills what is left.
3. Score a rally, tag it, open `Note`, type, score the next rally. The note
   commits on dismissal, and undo takes back the rally rather than a letter.
4. Open a clip's Add-note sheet. The `Clips` label is offered, neither
   `Scoreboard` label is, and **on iOS there is no "+ New label" button** - check
   both a cloud clip and a local video.
5. Scope every label to `Clips`. The board reads "No board labels", not "No
   labels yet".
6. Open `RalliesFacet` on a match tagged before this change: badges and counts
   unchanged.
7. Make a label on the Labels screen choosing name, colour and scope in one
   pass, and confirm it appears in exactly the pickers its scope names.

Take a screenshot of the board at step 2 and of the Labels screen at step 7.
**Look at them.** A chip clipped at the container edge or a caption colliding
with a name is a defect, not a rounding error.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for label scope and the board's wrapping tag row"
```

- [ ] **Step 5: Report**

State in the final summary:

- `supabase/migrations/20260830000000_label_usage.sql` must be applied to the
  hosted Supabase project (`supabase db push` from a linked checkout, or pasted
  into the SQL editor) before scope persists against production data. The apps
  work without it - every label just reads as `both`.
- iOS lost the in-sheet "+ New label" shortcut; labels are made on the Labels
  screen on both platforms now.
- Which of the seven device checks were actually run, and on which simulators.

---

## Self-Review

**Spec coverage.** Every section of
`docs/plans/2026-08-30-scoreboard-label-scope-design.md` maps to a task: §4.1
column → Task 1; §4.2 `LabelUsage` → Task 1; §4.3 model → Task 1; §5.1 flows and
`setUsage` → Task 3; §5.2 `create` → Task 3; §5.3 rewiring → Tasks 4, 5, 8;
§5.4 interop → Tasks 2 and 3; §7.1 wrapping → Tasks 4, 5; §7.2 `Note` → Tasks 4,
5; §7.3 empty state → Tasks 4, 5; §8.1 control → Tasks 6, 7; §8.2 caption →
Tasks 6, 7; §8.3 removal → Task 2; §10 verification → distributed, with §10.4
in Task 9.

**Two places the executor must read before writing.** The plan cannot invent
`AnnotationLabelsRepositoryTest`'s fake-client helpers (Task 3 Step 1) or the
existing fixture names in `ClipDetailViewModelTest` / `LocalPlayerViewModelTest`
(Task 8 Step 1). Both steps say so explicitly and say what to do instead of
guessing. Everything else is spelled out.

**Type consistency.** `LabelUsage.key` (not `.value`); `AnnotationLabel.usage`
is the `String`, `AnnotationLabel.scope` is the `LabelUsage`;
`scoreboardLabels`/`clipLabels` are the flow names everywhere;
`setUsage(id, usage)` on the repository, `setUsage(id, usage)` on
`LabelsViewModel`, `setUsage(_ id:to:)` on `LabelsModel`;
`create(name, color, usage)` in all three layers; `hasAnyLabels` on both
`ScoringUiState` and `ScoringModel`.
