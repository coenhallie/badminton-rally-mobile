# Local Video Annotations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add on-phone timestamped annotations (shot-quality chip + optional note, tap-to-seek, delete) to local videos, mirroring analyzed-clip annotations, stored locally.

**Architecture:** A new `LocalAnnotationsRepository` persists annotations in the existing `multiplatform-settings` store keyed by local video id. `LocalPlayerScreen` gains an annotation surface driven by a new `LocalPlayerViewModel`, reusing annotation UI extracted from `ClipDetailScreen`. A new `AnalyzeStage.ANALYZED` keeps annotated videos after analysis instead of removing them.

**Tech Stack:** Kotlin 2.3, Jetpack Compose M3, Media3 ExoPlayer, multiplatform-settings, kotlinx.serialization; kotest + Turbine + kotlinx-coroutines-test for tests.

**Design doc:** `docs/plans/2026-07-08-local-video-annotations-design.md`

## Global Constraints

- On-phone storage only (multiplatform-settings); no Supabase, no backend, no RLS. Key: `local_annotations`, shaped `Map<localVideoId, List<LocalAnnotation>>`.
- Reuse the shared `AnnotationKind` (good_shot / forced_error / unforced_error) so chips/colors match analyzed clips.
- Annotations attach to the whole local video at a timestamp (seconds, ≥ 0).
- On analysis success: if the video has annotations → keep entry, mark `AnalyzeStage.ANALYZED`; else remove as today. Zero-clip and failure branches unchanged.
- Removing a local video also removes its annotations (`removeAllFor`).
- Disclosure caption in the local annotation area, verbatim: `Annotations are saved on this phone and are removed if you remove the video from the app.`
- Add is ignored when the note is blank AND no kind is selected (same guard as `ClipDetailViewModel`).
- Follow existing style: concrete repos injected (no interfaces), fakes-in-`testing/`, `MapSettings` for local-repo tests, manual `viewModelFactory` DI, Shuttl components, zero-radius shapes.
- Test commands: `./gradlew :androidApp:testDebugUnitTest`; build check `./gradlew :androidApp:assembleDebug`.

---

### Task 1: `LocalAnnotation` model, `LocalAnnotationsRepository`, `ANALYZED` stage

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalAnnotation.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalAnnotationsRepository.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoEntry.kt` (add `ANALYZED`)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoListViewModel.kt` (add `ANALYZED` branch so `toRow`'s `when` stays exhaustive)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt` (construct + expose `localAnnotations`)
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalAnnotationsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AnnotationKind` (`com.badmintontracker.shared.model.AnnotationKind`), `Settings` (multiplatform-settings).
- Produces (used by Tasks 3, 5):

```kotlin
@Serializable
data class LocalAnnotation(
    val id: String,
    val timestampSeconds: Float,
    val body: String,
    val kind: AnnotationKind? = null,
    val createdAtEpochMs: Long,
)

class LocalAnnotationsRepository(settings: Settings) {
    val byVideoId: StateFlow<Map<String, List<LocalAnnotation>>>
    fun annotationsFor(videoId: String): List<LocalAnnotation>   // sorted by timestamp asc
    fun hasAnnotations(videoId: String): Boolean
    fun add(videoId: String, timestampSeconds: Float, body: String, kind: AnnotationKind?): LocalAnnotation
    fun delete(videoId: String, annotationId: String)
    fun removeAllFor(videoId: String)
}
// enum AnalyzeStage now: LOCAL, UPLOADING, PROCESSING, FAILED, ANALYZED
```

- [ ] **Step 1: Add the `ANALYZED` stage (and keep `toRow` exhaustive)**

In `LocalVideoEntry.kt`, change the enum line:

```kotlin
enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED, ANALYZED }
```

Adding an enum value makes the `when (stage)` in `LocalVideoListViewModel.toRow` non-exhaustive, so add its branch now (the row's `canAnalyze` stays false for ANALYZED since it isn't LOCAL/FAILED). In `LocalVideoListViewModel.kt`, inside `toRow`'s `when`, add after the `AnalyzeStage.FAILED -> null` line:

```kotlin
        AnalyzeStage.ANALYZED -> "Analyzed"
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.model.AnnotationKind
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LocalAnnotationsRepositoryTest {

    @Test
    fun add_stores_and_hasAnnotations_reflects_it() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.hasAnnotations("v1").shouldBeFalse()
        repo.add("v1", 12f, "nice", AnnotationKind.GOOD_SHOT)
        repo.hasAnnotations("v1").shouldBeTrue()
        val a = repo.annotationsFor("v1").single()
        a.timestampSeconds shouldBe 12f
        a.body shouldBe "nice"
        a.kind shouldBe AnnotationKind.GOOD_SHOT
    }

    @Test
    fun annotationsFor_is_sorted_by_timestamp() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("v1", 30f, "late", null)
        repo.add("v1", 5f, "early", null)
        repo.add("v1", 15f, "mid", null)
        repo.annotationsFor("v1").map { it.timestampSeconds } shouldBe listOf(5f, 15f, 30f)
    }

    @Test
    fun keyed_per_video() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("v1", 1f, "a", null)
        repo.add("v2", 2f, "b", null)
        repo.annotationsFor("v1").single().body shouldBe "a"
        repo.annotationsFor("v2").single().body shouldBe "b"
    }

    @Test
    fun delete_removes_only_that_annotation() {
        val repo = LocalAnnotationsRepository(MapSettings())
        val a = repo.add("v1", 1f, "a", null)
        repo.add("v1", 2f, "b", null)
        repo.delete("v1", a.id)
        repo.annotationsFor("v1").map { it.body } shouldBe listOf("b")
    }

    @Test
    fun removeAllFor_clears_a_video() {
        val repo = LocalAnnotationsRepository(MapSettings())
        repo.add("v1", 1f, "a", null)
        repo.add("v1", 2f, "b", null)
        repo.removeAllFor("v1")
        repo.hasAnnotations("v1").shouldBeFalse()
    }

    @Test
    fun persists_across_instances() {
        val settings = MapSettings()
        LocalAnnotationsRepository(settings).add("v1", 1f, "a", null)
        LocalAnnotationsRepository(settings).annotationsFor("v1").single().body shouldBe "a"
    }

    @Test
    fun corrupt_json_yields_empty() {
        val settings = MapSettings().apply { putString("local_annotations", "not-json") }
        LocalAnnotationsRepository(settings).annotationsFor("v1") shouldBe emptyList()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalAnnotationsRepositoryTest"`
Expected: compilation FAILS — `LocalAnnotation`/`LocalAnnotationsRepository` unresolved.

- [ ] **Step 4: Implement the model + repository**

`LocalAnnotation.kt`:

```kotlin
package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.model.AnnotationKind
import kotlinx.serialization.Serializable

/** A timestamped note on a local (on-phone) video. Stored on-device only. */
@Serializable
data class LocalAnnotation(
    val id: String,
    val timestampSeconds: Float,
    val body: String,                 // may be blank when only a kind is set
    val kind: AnnotationKind? = null,
    val createdAtEpochMs: Long,
)
```

`LocalAnnotationsRepository.kt`:

```kotlin
package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.model.AnnotationKind
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** On-phone annotations keyed by local video id, persisted as JSON in Settings. */
class LocalAnnotationsRepository(private val settings: Settings) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer =
        MapSerializer(String.serializer(), ListSerializer(LocalAnnotation.serializer()))

    private val state = MutableStateFlow(load())
    val byVideoId: StateFlow<Map<String, List<LocalAnnotation>>> = state.asStateFlow()

    fun annotationsFor(videoId: String): List<LocalAnnotation> =
        state.value[videoId].orEmpty()

    fun hasAnnotations(videoId: String): Boolean =
        state.value[videoId]?.isNotEmpty() == true

    fun add(videoId: String, timestampSeconds: Float, body: String, kind: AnnotationKind?): LocalAnnotation {
        val annotation = LocalAnnotation(
            id = UUID.randomUUID().toString(),
            timestampSeconds = timestampSeconds,
            body = body,
            kind = kind,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        mutate(videoId) { it + annotation }
        return annotation
    }

    fun delete(videoId: String, annotationId: String) =
        mutate(videoId) { list -> list.filterNot { it.id == annotationId } }

    fun removeAllFor(videoId: String) {
        val next = state.value - videoId
        persist(next)
    }

    private fun mutate(videoId: String, transform: (List<LocalAnnotation>) -> List<LocalAnnotation>) {
        val current = state.value[videoId].orEmpty()
        val updated = transform(current).sortedBy { it.timestampSeconds }
        persist(state.value + (videoId to updated))
    }

    private fun persist(next: Map<String, List<LocalAnnotation>>) {
        settings.putString(KEY, json.encodeToString(serializer, next))
        state.value = next
    }

    private fun load(): Map<String, List<LocalAnnotation>> =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?.mapValues { (_, list) -> list.sortedBy { it.timestampSeconds } }
            ?: emptyMap()

    private companion object { const val KEY = "local_annotations" }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalAnnotationsRepositoryTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Expose `localAnnotations` from `RallyAndroidApp`**

In `RallyAndroidApp.kt`, add the import and field, and construct it right after `localVideos`:

```kotlin
import com.badmintontracker.android.localvideo.LocalAnnotationsRepository
```

```kotlin
    lateinit var localAnnotations:   LocalAnnotationsRepository  private set
```

```kotlin
        localVideos = LocalVideoRepository(settings)
        localAnnotations = LocalAnnotationsRepository(settings)
```

- [ ] **Step 7: Build + commit**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add -A androidApp
git commit -m "feat(localvideo): on-phone annotations repository and ANALYZED stage"
```

---

### Task 2: Extract shared annotation UI from `ClipDetailScreen`

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationUi.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Interfaces:**
- Produces (used by Task 4). All are `internal` (same `:androidApp` module → callable from the `localvideo` package):

```kotlin
internal fun formatTimestamp(seconds: Float): String
@Composable internal fun AnnotationRow(
    timestampSeconds: Float, body: String, kind: AnnotationKind?,
    onClick: () -> Unit, onDelete: (() -> Unit)?,
)
@Composable internal fun AddAnnotationSheet(
    onDismiss: () -> Unit, onConfirm: (body: String, kind: AnnotationKind?) -> Unit,
)
```

This is a refactor: behavior for analyzed clips is unchanged, so verification is a clean build plus the untouched `ClipDetailViewModelTest`.

- [ ] **Step 1: Create `AnnotationUi.kt`**

```kotlin
package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.model.AnnotationKind
import kotlinx.coroutines.launch

internal fun formatTimestamp(seconds: Float): String {
    val total = kotlin.math.round(seconds).toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
internal fun AnnotationRow(
    timestampSeconds: Float,
    body: String,
    kind: AnnotationKind?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTimestamp(timestampSeconds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        kind?.let { k ->
            val s = k.style()
            Surface(shape = RoundedCornerShape(50), color = s.container) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = s.onContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (body.isNotBlank()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete annotation")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAnnotationSheet(
    onDismiss: () -> Unit,
    onConfirm: (body: String, kind: AnnotationKind?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<AnnotationKind?>(null) }
    val canAdd = kind != null || body.isNotBlank()

    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add annotation", style = MaterialTheme.typography.titleLarge)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KindChip("Good shot",      AnnotationKind.GOOD_SHOT,      kind) { kind = if (kind == it) null else it }
                KindChip("Forced error",   AnnotationKind.FORCED_ERROR,   kind) { kind = if (kind == it) null else it }
                KindChip("Unforced error", AnnotationKind.UNFORCED_ERROR, kind) { kind = if (kind == it) null else it }
            }

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Note (optional)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { hideThen { onDismiss() } }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = canAdd, onClick = { hideThen { onConfirm(body, kind) } }) { Text("Add") }
            }
        }
    }
}

@Composable
private fun KindChip(
    label: String,
    target: AnnotationKind,
    selected: AnnotationKind?,
    onClick: (AnnotationKind) -> Unit,
) {
    val s = target.style()
    FilterChip(
        selected = selected == target,
        onClick = { onClick(target) },
        label = { Text(label) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = s.container,
            selectedLabelColor = s.onContainer,
        ),
    )
}
```

- [ ] **Step 2: Delete the moved code from `ClipDetailScreen.kt`**

Delete these now-duplicated private declarations from `ClipDetailScreen.kt` (they live in `AnnotationUi.kt` now): `formatTimestamp`, `AnnotationRow`, `AddAnnotationDialog`, `KindChip`.

- [ ] **Step 3: Update the two call sites in `ClipDetailScreen.kt`**

The annotation list item — replace:

```kotlin
                        AnnotationRow(
                            a = a,
                            onClick = { vm.onAnnotationTap(a) },
                            onDelete = if (state.isOwner) ({ pendingDelete = a }) else null,
                        )
```

with:

```kotlin
                        AnnotationRow(
                            timestampSeconds = a.timestampSeconds,
                            body = a.body,
                            kind = a.kind,
                            onClick = { vm.onAnnotationTap(a) },
                            onDelete = if (state.isOwner) ({ pendingDelete = a }) else null,
                        )
```

The add dialog — replace:

```kotlin
    addDialog?.let { ts ->
        AddAnnotationDialog(
            onDismiss = { addDialog = null },
            onConfirm = { body, kind ->
                vm.addAnnotation(ts, body, kind)
                addDialog = null
            },
        )
    }
```

with:

```kotlin
    addDialog?.let { ts ->
        AddAnnotationSheet(
            onDismiss = { addDialog = null },
            onConfirm = { body, kind ->
                vm.addAnnotation(ts, body, kind)
                addDialog = null
            },
        )
    }
```

- [ ] **Step 4: Remove now-unused imports from `ClipDetailScreen.kt`**

These imports are only used by the moved code; delete them:

```
androidx.compose.foundation.layout.FlowRow
androidx.compose.foundation.shape.RoundedCornerShape
androidx.compose.foundation.text.KeyboardOptions
androidx.compose.material3.Button
androidx.compose.material3.FilterChip
androidx.compose.material3.FilterChipDefaults
androidx.compose.material3.ModalBottomSheet
androidx.compose.material3.OutlinedTextField
androidx.compose.material3.rememberModalBottomSheetState
androidx.compose.runtime.rememberCoroutineScope
androidx.compose.ui.text.input.KeyboardCapitalization
androidx.compose.foundation.layout.imePadding
kotlinx.coroutines.launch
```

Keep everything else (`Surface`, `TextButton`, `Arrangement`, `Icons`, `Icon`, etc. remain in use by the player error overlay and the delete confirm dialog).

- [ ] **Step 5: Build to confirm the refactor compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. If the compiler flags any remaining unused import, remove it; if it reports a still-referenced symbol, that import stays.

- [ ] **Step 6: Run the existing clip-detail test + commit**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.clipdetail.ClipDetailViewModelTest"`
Expected: PASS (unchanged).

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail
git commit -m "refactor(clipdetail): extract reusable annotation UI"
```

---

### Task 3: `LocalPlayerViewModel`

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalPlayerViewModelTest.kt`

**Interfaces:**
- Consumes: `LocalAnnotationsRepository`, `LocalAnnotation` (Task 1), `AnnotationKind`.
- Produces (used by Task 4):

```kotlin
class LocalPlayerViewModel(videoId: String, annotations: LocalAnnotationsRepository) : ViewModel() {
    val state: StateFlow<List<LocalAnnotation>>   // sorted by timestamp
    val seekTo: SharedFlow<Long>                  // ms
    fun onAnnotationTap(a: LocalAnnotation)
    fun addAnnotation(timestampSeconds: Float, body: String, kind: AnnotationKind?)
    fun deleteAnnotation(id: String)
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.android.localvideo

import app.cash.turbine.test
import com.badmintontracker.shared.model.AnnotationKind
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlayerViewModelTest {

    private val repo = LocalAnnotationsRepository(MapSettings())

    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    private fun vm() = LocalPlayerViewModel("v1", repo)

    @Test
    fun add_kind_only_and_note_only_and_both() = runTest {
        val vm = vm()
        vm.addAnnotation(1f, "", AnnotationKind.GOOD_SHOT)       // kind only
        vm.addAnnotation(2f, "just a note", null)               // note only
        vm.addAnnotation(3f, "both", AnnotationKind.FORCED_ERROR)
        vm.state.value.map { it.timestampSeconds } shouldBe listOf(1f, 2f, 3f)
    }

    @Test
    fun add_ignored_when_blank_body_and_no_kind() = runTest {
        val vm = vm()
        vm.addAnnotation(1f, "   ", null)
        vm.state.value shouldBe emptyList()
    }

    @Test
    fun add_trims_body_and_coerces_negative_timestamp() = runTest {
        val vm = vm()
        vm.addAnnotation(-5f, "  hi  ", null)
        val a = vm.state.value.single()
        a.body shouldBe "hi"
        a.timestampSeconds shouldBe 0f
    }

    @Test
    fun delete_removes_annotation() = runTest {
        val vm = vm()
        vm.addAnnotation(1f, "a", null)
        val id = vm.state.value.single().id
        vm.deleteAnnotation(id)
        vm.state.value shouldBe emptyList()
    }

    @Test
    fun state_is_sorted_by_timestamp() = runTest {
        val vm = vm()
        vm.addAnnotation(30f, "late", null)
        vm.addAnnotation(5f, "early", null)
        vm.state.value.map { it.body } shouldBe listOf("early", "late")
    }

    @Test
    fun onAnnotationTap_emits_milliseconds() = runTest {
        val vm = vm()
        vm.seekTo.test {
            vm.onAnnotationTap(
                LocalAnnotation(id = "x", timestampSeconds = 2.5f, body = "b", kind = null, createdAtEpochMs = 0),
            )
            awaitItem() shouldBe 2500L
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalPlayerViewModelTest"`
Expected: compilation FAILS — `LocalPlayerViewModel` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LocalPlayerViewModel(
    private val videoId: String,
    private val annotations: LocalAnnotationsRepository,
) : ViewModel() {

    val state: StateFlow<List<LocalAnnotation>> = annotations.byVideoId
        .map { it[videoId].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, annotations.annotationsFor(videoId))

    val seekTo: SharedFlow<Long> = MutableSharedFlow(extraBufferCapacity = 1)

    fun onAnnotationTap(a: LocalAnnotation) {
        (seekTo as MutableSharedFlow).tryEmit((a.timestampSeconds * 1000).toLong())
    }

    fun addAnnotation(timestampSeconds: Float, body: String, kind: AnnotationKind?) {
        val trimmed = body.trim()
        if (trimmed.isEmpty() && kind == null) return
        annotations.add(videoId, timestampSeconds.coerceAtLeast(0f), trimmed, kind)
    }

    fun deleteAnnotation(id: String) = annotations.delete(videoId, id)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalPlayerViewModelTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerViewModel.kt androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalPlayerViewModelTest.kt
git commit -m "feat(localvideo): local player view model for annotations"
```

---

### Task 4: `LocalPlayerScreen` annotation surface + nav wiring

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`

**Interfaces:**
- Consumes: `LocalPlayerViewModel` (Task 3), `AnnotationRow`/`AddAnnotationSheet`/`formatTimestamp` (Task 2), `RallyAndroidApp.localAnnotations` (Task 1).
- Produces: `LocalPlayerScreen(vm, entry, canAnalyze, onAnalyze, onBack)`.

UI task — verified by build + on-device.

- [ ] **Step 1: Rewrite `LocalPlayerScreen.kt` with the annotation surface**

Full file:

```kotlin
package com.badmintontracker.android.localvideo

import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.badmintontracker.android.R
import com.badmintontracker.android.clipdetail.AddAnnotationSheet
import com.badmintontracker.android.clipdetail.AnnotationRow
import com.badmintontracker.android.clipdetail.FrameStepBar
import com.badmintontracker.android.ui.components.FullscreenEffect
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant

private const val ANNOTATION_STORAGE_NOTE =
    "Annotations are saved on this phone and are removed if you remove the video from the app."

/**
 * Plays a local recording from its content:// URI with the same frame-step and
 * fullscreen behavior as analyzed clips, plus on-phone timestamped annotations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlayerScreen(
    vm: LocalPlayerViewModel,
    entry: LocalVideoEntry,
    canAnalyze: Boolean,
    onAnalyze: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val annotations by vm.state.collectAsStateWithLifecycle()
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply { setSeekParameters(SeekParameters.EXACT) }
    }
    var isFullscreen by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf<Float?>(null) }
    var pendingDelete by remember { mutableStateOf<LocalAnnotation?>(null) }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    FullscreenEffect(isFullscreen)

    LaunchedEffect(orientation) {
        isFullscreen = (orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(entry.uri) {
        player.setMediaItem(MediaItem.fromUri(entry.uri))
        player.prepare()
        player.playWhenReady = false
    }

    LaunchedEffect(Unit) {
        vm.seekTo.collect { ms -> player.seekTo(ms) }
    }

    val playerSurface: @Composable (Modifier) -> Unit = { modifier ->
        AndroidView(
            factory = { c ->
                val view = LayoutInflater.from(c)
                    .inflate(R.layout.clip_player_view, null) as PlayerView
                view.apply {
                    this.player = player
                    setFullscreenButtonClickListener { isFullscreen = !isFullscreen }
                    controllerShowTimeoutMs = 1500
                    controllerAutoShow = false
                    hideController()
                }
            },
            update = { it.setFullscreenButtonState(isFullscreen) },
            modifier = modifier,
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text(entry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (canAnalyze) {
                            ShuttlButton(
                                text = "Analyze",
                                onClick = onAnalyze,
                                variant = ShuttlButtonVariant.Primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isFullscreen) {
                FloatingActionButton(onClick = {
                    addDialog = (player.currentPosition.coerceAtLeast(0L)) / 1000f
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add annotation")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isFullscreen) {
                playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                FrameStepBar(player = player, modifier = Modifier.padding(vertical = 8.dp))

                if (annotations.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ANNOTATION_STORAGE_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        ANNOTATION_STORAGE_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(annotations, key = { it.id }) { a ->
                            AnnotationRow(
                                timestampSeconds = a.timestampSeconds,
                                body = a.body,
                                kind = a.kind,
                                onClick = { vm.onAnnotationTap(a) },
                                onDelete = { pendingDelete = a },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (isFullscreen) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            playerSurface(Modifier.fillMaxSize())
            FrameStepBar(
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }

    addDialog?.let { ts ->
        AddAnnotationSheet(
            onDismiss = { addDialog = null },
            onConfirm = { body, kind ->
                vm.addAnnotation(ts, body, kind)
                addDialog = null
            },
        )
    }

    pendingDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete annotation?") },
            text = { Text(if (a.body.isNotBlank()) "\"${a.body}\"" else "This annotation") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAnnotation(a.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
```

- [ ] **Step 2: Update the `Route.LocalPlayer` destination in `AuthGate.kt`**

Add the imports near the other localvideo imports:

```kotlin
import androidx.lifecycle.viewmodel.compose.viewModel
```
(only if not already imported — it is used elsewhere in `AuthGate`; confirm and skip if present)

Replace the whole `composable<Route.LocalPlayer>` block with:

```kotlin
                composable<Route.LocalPlayer> { entry ->
                    val args = entry.toRoute<Route.LocalPlayer>()
                    val entries by localVideos.entries.collectAsStateWithLifecycle()
                    val e = entries.firstOrNull { it.id == args.entryId }
                    if (e == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        val playerVm: LocalPlayerViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { LocalPlayerViewModel(args.entryId, localAnnotations) }
                            }
                        )
                        LocalPlayerScreen(
                            vm = playerVm,
                            entry = e,
                            canAnalyze = e.stage == AnalyzeStage.LOCAL || e.stage == AnalyzeStage.FAILED,
                            onAnalyze = { nav.navigate(Route.CourtMarking(e.id)) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
```

- [ ] **Step 3: Pass `localAnnotations` into `AuthGate` and from `MainActivity`**

In `AuthGate.kt`, add the parameter and import:

```kotlin
import com.badmintontracker.android.localvideo.LocalAnnotationsRepository
import com.badmintontracker.android.localvideo.LocalPlayerViewModel
```

```kotlin
fun AuthGate(
    rally: RallyApp,
    themePrefs: ThemePreferenceRepository,
    localVideos: LocalVideoRepository,
    coordinator: AnalyzeCoordinator,
    localAnnotations: LocalAnnotationsRepository,
) {
```

In `MainActivity.kt`, update the `AuthGate(...)` call to pass it:

```kotlin
                    AuthGate(
                        rally = app.rally,
                        themePrefs = app.themePrefs,
                        localVideos = app.localVideos,
                        coordinator = app.analyzeCoordinator,
                        localAnnotations = app.localAnnotations,
                    )
```

- [ ] **Step 4: Build**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A androidApp
git commit -m "feat(localvideo): annotation surface on the local player"
```

---

### Task 5: Lifecycle — keep annotated videos after analysis

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/AnalyzeCoordinator.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalVideoListViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/RallyAndroidApp.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/AnalyzeCoordinatorTest.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/LocalVideoListViewModelTest.kt`

**Interfaces:**
- Consumes: `LocalAnnotationsRepository.hasAnnotations`/`removeAllFor` (Task 1).
- Produces: `AnalyzeCoordinator(..., localAnnotations)`, `LocalVideoListViewModel(localVideos, coordinator, localAnnotations)`, `ANALYZED` row rendering (`statusText = "Analyzed"`, `canAnalyze = false`).

- [ ] **Step 1: Extend the coordinator test (success with annotations keeps as ANALYZED)**

In `AnalyzeCoordinatorTest.kt`, add a `localAnnotations` field and route it through the `coordinator()` helper, then add the new test. Add near the other fields:

```kotlin
    private val localAnnotations = LocalAnnotationsRepository(MapSettings())
```

Change the `coordinator()` helper to pass it:

```kotlin
    private fun TestScope.coordinator() = AnalyzeCoordinator(
        localVideos = localVideos,
        videos = videos,
        clips = clips,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
        localAnnotations = localAnnotations,
    )
```

Add the new test:

```kotlin
    @Test
    fun success_with_annotations_keeps_entry_as_analyzed() = runTest {
        localVideos.add(entry())
        clips.clips.value = listOf(clipFor("e1"))
        localAnnotations.add("e1", 1f, "note", null)   // has an annotation
        val c = coordinator()
        c.startAnalysis("e1", keypoints())
        runCurrent()
        val kept = localVideos.get("e1").shouldNotBeNull()
        kept.stage shouldBe AnalyzeStage.ANALYZED
    }
```

(The existing `happy_path_…_removes_entry` test has no annotations for "e1", so it still expects removal — leave it unchanged.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.AnalyzeCoordinatorTest"`
Expected: compilation FAILS — `AnalyzeCoordinator` has no `localAnnotations` parameter.

- [ ] **Step 3: Add `localAnnotations` to the coordinator and branch on it**

In `AnalyzeCoordinator.kt`, add the constructor parameter (after `log`):

```kotlin
class AnalyzeCoordinator(
    private val localVideos: LocalVideoRepository,
    private val videos: VideosRepository,
    private val clips: ClipsRepository,
    private val scope: CoroutineScope,
    private val openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel,
    private val log: (String) -> Unit = {},
    private val localAnnotations: LocalAnnotationsRepository,
) {
```

Replace the success tail `localVideos.remove(entryId)` (the last line of `runPipeline`, after the `clipCount == 0` block) with:

```kotlin
        if (localAnnotations.hasAnnotations(entryId)) {
            // Keep annotated videos so the notes survive; mark them Analyzed.
            localVideos.update(entryId) {
                it.copy(stage = AnalyzeStage.ANALYZED, failedStep = null, failureMessage = null)
            }
        } else {
            localVideos.remove(entryId)
        }
```

- [ ] **Step 4: Update `RallyAndroidApp` to pass `localAnnotations` to the coordinator**

In `RallyAndroidApp.kt`, the `analyzeCoordinator = AnalyzeCoordinator(...)` call gains the argument (place it last, and note `log` keeps its default position by naming args):

```kotlin
        analyzeCoordinator = AnalyzeCoordinator(
            localVideos = localVideos,
            videos = rally.videos,
            clips = rally.clips,
            scope = appScope,
            openChannel = { uri, offset ->
                val stream = runCatching { contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()
                    ?: error("Video file is missing or access was revoked")
                stream.skip(offset)
                stream.toByteReadChannel()
            },
            log = { Log.i("AnalyzeCoordinator", it) },
            localAnnotations = localAnnotations,
        )
```

(`localAnnotations` is already constructed in Task 1, before this block.)

- [ ] **Step 5: Run the coordinator tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.AnalyzeCoordinatorTest"`
Expected: PASS (existing + the new `success_with_annotations_keeps_entry_as_analyzed`).

- [ ] **Step 6: Add ANALYZED-row test to `LocalVideoListViewModelTest`**

The VM constructor is about to gain a `localAnnotations` param. Update every `LocalVideoListViewModel(...)` and `AnalyzeCoordinator(...)` construction in this test file to pass a `LocalAnnotationsRepository(MapSettings())`, then add the test.

Add an import:

```kotlin
import com.russhwolf.settings.MapSettings
```
(already imported in this file — confirm and skip if present)

Update the coordinator + VM constructions (both existing tests) to include `localAnnotations`, e.g.:

```kotlin
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        val coordinator = AnalyzeCoordinator(
            localVideos, FakeVideosRepository(), FakeClipsRepository(),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
            localAnnotations = localAnnotations,
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator, localAnnotations)
```

Add the new test:

```kotlin
    @Test
    fun analyzed_row_shows_label_and_hides_analyze() = runTest {
        val localVideos = LocalVideoRepository(MapSettings())
        val localAnnotations = LocalAnnotationsRepository(MapSettings())
        val coordinator = AnalyzeCoordinator(
            localVideos, FakeVideosRepository(), FakeClipsRepository(),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
            localAnnotations = localAnnotations,
        )
        localVideos.add(
            LocalVideoEntry(
                id = "a", uri = "content://a", displayName = "m.mp4",
                durationMs = 1000, sizeBytes = 1, addedAtEpochMs = 0, stage = AnalyzeStage.ANALYZED,
            ),
        )
        val vm = LocalVideoListViewModel(localVideos, coordinator, localAnnotations)
        val row = vm.rows.value.single()
        row.statusText shouldBe "Analyzed"
        row.canAnalyze shouldBe false
    }
```

- [ ] **Step 7: Run to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.localvideo.LocalVideoListViewModelTest"`
Expected: compilation FAILS — `LocalVideoListViewModel` has no third (`localAnnotations`) parameter, and `AnalyzeCoordinator` calls in this file don't pass `localAnnotations`.

- [ ] **Step 8: Implement the VM change + ANALYZED row**

In `LocalVideoListViewModel.kt`, add the constructor param and clear annotations on remove:

```kotlin
class LocalVideoListViewModel(
    private val localVideos: LocalVideoRepository,
    private val coordinator: AnalyzeCoordinator,
    private val localAnnotations: LocalAnnotationsRepository,
) : ViewModel() {

    val rows = combine(localVideos.entries, coordinator.progress) { entries, progress ->
        entries.map { e -> e.toRow(progress[e.id]) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        localVideos.entries.value.map { it.toRow(null) },
    )

    fun remove(id: String) {
        localVideos.remove(id)
        localAnnotations.removeAllFor(id)
    }

    fun retry(id: String) = coordinator.retry(id)

    fun acknowledgeResult(id: String) = localVideos.update(id) { it.copy(resultSeen = true) }
}
```

(`toRow`'s `ANALYZED -> "Analyzed"` branch was already added in Task 1, so no change to `toRow` is needed here.)

- [ ] **Step 9: Wire the third VM arg in `AuthGate`**

In `AuthGate.kt`, the `LocalVideoListViewModel` factory gains `localAnnotations`:

```kotlin
                    val localVm: LocalVideoListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { LocalVideoListViewModel(localVideos, coordinator, localAnnotations) }
                        }
                    )
```

- [ ] **Step 10: Run the full unit suite + build**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 11: Commit**

```bash
git add -A androidApp
git commit -m "feat(localvideo): keep annotated videos after analysis (ANALYZED stage)"
```

---

### Task 6: Changelog + full verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `androidApp/build.gradle.kts` (version bump)

- [ ] **Step 1: Update CHANGELOG under `[Unreleased]` → Added**

```markdown
- Add timestamped annotations (shot-quality chips + notes) to local on-phone videos.
  Annotations are stored on the phone; a video keeps its annotations after analysis
  (marked "Analyzed") and loses them only when removed from the app.
```

- [ ] **Step 2: Bump the app version**

In `androidApp/build.gradle.kts`, bump for sideload:

```kotlin
        versionCode = 7
        versionName = "0.1.6"
```

- [ ] **Step 3: Full build + all tests**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug :shared:jvmTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: On-device verification checklist (manual)**

1. Open a local video → tap the + FAB → the add sheet appears with the three kind chips and a note field; add one → it lands in the list at its timestamp.
2. The disclosure caption is visible (empty-state text before any annotation, and above the list after).
3. Tap an annotation row → the player seeks to that timestamp.
4. Delete an annotation via the trash icon → confirm dialog → it disappears.
5. Analyze a video that has annotations → after it completes, the entry stays under "On this phone" labeled "Analyzed" (no Analyze button) and still plays + shows its annotations; the analyzed match also appears in "My Matches".
6. Analyze a video with no annotations → it auto-removes as before.
7. Remove an annotated video (⋮ → Remove from app) → re-open the app → its annotations are gone.

- [ ] **Step 5: Commit**

```bash
git add CHANGELOG.md androidApp/build.gradle.kts
git commit -m "docs: changelog for local video annotations; bump to 0.1.6"
```
