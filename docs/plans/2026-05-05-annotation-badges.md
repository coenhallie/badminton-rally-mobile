# Annotation Badges Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let users tag rally annotations with one of three badminton-specific shot-quality labels — Good shot / Forced error / Unforced error — rendered as colored pills, with body text now optional.

**Architecture:** Add an optional `AnnotationKind` enum field to the existing `RallyAnnotation` model (shared module). Repository, ViewModel, and Compose UI gain pass-through changes. Supabase migration adds a `kind` column with CHECK constraints. Body becomes optional at all layers; one of body/kind must be present.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, Supabase (postgrest-kt), Jetpack Compose Material 3, Kotest, Turbine.

**Design doc:** [`docs/plans/2026-05-05-annotation-badges-design.md`](./2026-05-05-annotation-badges-design.md)

---

## Conventions

- All gradle commands run from the repo root.
- Test runners:
  - Shared (commonTest via JVM): `./gradlew :shared:jvmTest`
  - Android unit tests: `./gradlew :androidApp:testDebugUnitTest`
  - Android assemble: `./gradlew :androidApp:assembleDebug`
- Use Kotest matchers (`shouldBe`, `shouldContain`) — same style as existing tests.
- Commit style mirrors recent history: `feat(...)`, `fix(...)`, `test(...)`, `docs(...)`, `chore(...)`. Include the Co-Authored-By trailer used in the design commit.

---

## Task 1: Add AnnotationKind enum

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationKind.kt`

**Step 1: Create the enum**

```kotlin
package com.badmintontracker.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AnnotationKind {
    @SerialName("good_shot")      GOOD_SHOT,
    @SerialName("forced_error")   FORCED_ERROR,
    @SerialName("unforced_error") UNFORCED_ERROR,
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinJvm`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationKind.kt
git commit -m "$(cat <<'EOF'
feat(shared): AnnotationKind enum for shot-quality badges

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Extend RallyAnnotation with kind field (TDD)

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/RallyAnnotationSerializationTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/RallyAnnotation.kt`

**Step 1: Add failing tests**

Replace the contents of `RallyAnnotationSerializationTest.kt` with:

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class RallyAnnotationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_postgrest_payload_without_kind() {
        val payload = """
            {
              "id":               "33333333-3333-3333-3333-333333333333",
              "clip_id":          "11111111-1111-1111-1111-111111111111",
              "timestamp_seconds": 4.2,
              "body":             "great footwork",
              "created_at":       "2026-05-04T12:00:00Z"
            }
        """.trimIndent()

        val a = json.decodeFromString(RallyAnnotation.serializer(), payload)

        a.clipId shouldBe "11111111-1111-1111-1111-111111111111"
        a.timestampSeconds shouldBe 4.2f
        a.body shouldBe "great footwork"
        a.kind shouldBe null
        a.createdAt shouldBe Instant.parse("2026-05-04T12:00:00Z")
    }

    @Test
    fun decodes_each_kind_value() {
        val pairs = listOf(
            "good_shot"      to AnnotationKind.GOOD_SHOT,
            "forced_error"   to AnnotationKind.FORCED_ERROR,
            "unforced_error" to AnnotationKind.UNFORCED_ERROR,
        )
        for ((wire, enum) in pairs) {
            val payload = """
                {
                  "id":"x","clip_id":"c","timestamp_seconds":1.0,"body":"",
                  "kind":"$wire","created_at":"2026-05-04T12:00:00Z"
                }
            """.trimIndent()
            val a = json.decodeFromString(RallyAnnotation.serializer(), payload)
            a.kind shouldBe enum
        }
    }

    @Test
    fun encodes_null_kind_as_omitted_or_null() {
        val a = RallyAnnotation(
            id = "x", clipId = "c", timestampSeconds = 1f, body = "hi",
            kind = null, createdAt = Instant.parse("2026-05-04T12:00:00Z"),
        )
        val out = Json.encodeToString(RallyAnnotation.serializer(), a)
        // Either field is absent, or explicitly null. Either is fine for postgrest.
        out.shouldNotContain("\"kind\":\"")
    }

    @Test
    fun encodes_non_null_kind_with_snake_case_value() {
        val a = RallyAnnotation(
            id = "x", clipId = "c", timestampSeconds = 1f, body = "",
            kind = AnnotationKind.UNFORCED_ERROR,
            createdAt = Instant.parse("2026-05-04T12:00:00Z"),
        )
        val out = Json.encodeToString(RallyAnnotation.serializer(), a)
        out.shouldContain("\"kind\":\"unforced_error\"")
    }
}
```

**Step 2: Run tests, verify they fail**

Run: `./gradlew :shared:jvmTest --tests 'com.badmintontracker.shared.model.RallyAnnotationSerializationTest'`
Expected: FAIL — compilation error (`No value passed for parameter 'kind'`) or unresolved reference `kind`.

**Step 3: Add `kind` field to the model**

Replace `RallyAnnotation.kt` with:

```kotlin
package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RallyAnnotation(
    val id: String,
    @SerialName("clip_id")             val clipId: String,
    @SerialName("timestamp_seconds")   val timestampSeconds: Float,
    val body: String,
    val kind: AnnotationKind? = null,
    @SerialName("created_at")          val createdAt: Instant,
)
```

**Step 4: Run tests, verify they pass**

Run: `./gradlew :shared:jvmTest --tests 'com.badmintontracker.shared.model.RallyAnnotationSerializationTest'`
Expected: PASS, all four tests green.

**Step 5: Run the full shared test suite to catch any compile breaks**

Run: `./gradlew :shared:jvmTest`
Expected: BUILD SUCCESSFUL. If any other tests fail, they'll be call-site issues — fix by passing `kind = null` to existing `RallyAnnotation(...)` constructions if any exist outside `commonTest`. Note: tests in `androidApp` will still compile because data class adds a new param with a default.

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/RallyAnnotation.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/RallyAnnotationSerializationTest.kt
git commit -m "$(cat <<'EOF'
feat(shared): add optional kind to RallyAnnotation

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: AnnotationsRepository accepts kind (TDD)

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt`

**Step 1: Add a failing test for `add` with kind**

Append this test to `AnnotationsRepositoryTest`:

```kotlin
@Test
fun add_includes_kind_in_post_body_when_provided() = runTest {
    var captured: String? = null
    val client = TestSupabase.client { request ->
        captured = (request.body as? TextContent)?.text ?: ""
        jsonResponse(
            """[{"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"",
                "kind":"unforced_error","created_at":"2026-05-04T12:00:00Z"}]""",
            HttpStatusCode.Created,
        )
    }
    val repo = AnnotationsRepositoryImpl(client)

    val result = repo.add("c1", 1.5f, "", AnnotationKind.UNFORCED_ERROR)

    result.isSuccess shouldBe true
    result.getOrThrow().kind shouldBe AnnotationKind.UNFORCED_ERROR
    captured!!.shouldContain(""""kind":"unforced_error"""")
}
```

Also update the existing `add_posts_to_rally_annotations` test to pass `kind = null` and assert the body does NOT contain a `"kind":"...` string (or contains `"kind":null` — match whichever your serializer emits; the `shouldNotContain(""""kind":"""")` assertion is precise enough):

```kotlin
@Test
fun add_posts_to_rally_annotations() = runTest {
    var captured: Pair<String, String>? = null
    val client = TestSupabase.client { request ->
        val body = (request.body as? TextContent)?.text ?: ""
        captured = request.method.value to body
        jsonResponse(
            """[{"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"hi","created_at":"2026-05-04T12:00:00Z"}]""",
            HttpStatusCode.Created,
        )
    }
    val repo = AnnotationsRepositoryImpl(client)

    val result = repo.add("c1", 1.5f, "hi", null)

    result.isSuccess shouldBe true
    result.getOrThrow().body shouldBe "hi"
    captured!!.first shouldBe "POST"
    captured!!.second.shouldContain(""""body":"hi"""")
}
```

Add the missing import at the top of the test file:

```kotlin
import com.badmintontracker.shared.model.AnnotationKind
```

**Step 2: Run tests, verify they fail**

Run: `./gradlew :shared:jvmTest --tests 'com.badmintontracker.shared.repo.AnnotationsRepositoryTest'`
Expected: FAIL — compilation error: `add` does not take 4 arguments.

**Step 3: Update `AnnotationsRepository` to accept kind**

Replace the contents of `AnnotationsRepository.kt`:

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.RallyAnnotation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface AnnotationsRepository {
    suspend fun list(clipId: String): List<RallyAnnotation>
    suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        kind: AnnotationKind?,
    ): Result<RallyAnnotation>
    suspend fun delete(id: String): Result<Unit>
}

class AnnotationsRepositoryImpl(private val client: SupabaseClient) : AnnotationsRepository {

    @Serializable
    private data class NewAnnotationRow(
        @SerialName("clip_id")           val clipId: String,
        @SerialName("timestamp_seconds") val timestampSeconds: Float,
        val body: String,
        val kind: AnnotationKind? = null,
    )

    override suspend fun list(clipId: String): List<RallyAnnotation> =
        client.postgrest.from("rally_annotations")
            .select {
                filter { eq("clip_id", clipId) }
                order("timestamp_seconds", Order.ASCENDING)
            }
            .decodeList()

    override suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        kind: AnnotationKind?,
    ): Result<RallyAnnotation> = runCatching {
        // owner_id is filled server-side via the column's `default auth.uid()`.
        client.postgrest.from("rally_annotations")
            .insert(NewAnnotationRow(clipId, timestampSeconds, body, kind)) { select() }
            .decodeSingle<RallyAnnotation>()
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        client.postgrest.from("rally_annotations").delete { filter { eq("id", id) } }
        Unit
    }
}
```

**Step 4: Run repository tests, verify pass**

Run: `./gradlew :shared:jvmTest --tests 'com.badmintontracker.shared.repo.AnnotationsRepositoryTest'`
Expected: PASS.

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt
git commit -m "$(cat <<'EOF'
feat(shared): AnnotationsRepository.add accepts kind

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Update FakeAnnotationsRepository

**Files:**
- Modify: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt`

**Step 1: Update fake to mirror the new signature**

Replace contents:

```kotlin
package com.badmintontracker.android.testing

import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationsRepository
import kotlinx.datetime.Instant

class FakeAnnotationsRepository : AnnotationsRepository {
    var byClipId: Map<String, List<RallyAnnotation>> = emptyMap()
    var listError: Throwable? = null
    var addError: Throwable? = null
    var deleteError: Throwable? = null

    data class AddCall(
        val clipId: String,
        val timestampSeconds: Float,
        val body: String,
        val kind: AnnotationKind?,
    )

    val addCalls = mutableListOf<AddCall>()
    val deleteCalls = mutableListOf<String>()
    private var nextId = 0

    override suspend fun list(clipId: String): List<RallyAnnotation> {
        listError?.let { throw it }
        return byClipId[clipId] ?: emptyList()
    }

    override suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        kind: AnnotationKind?,
    ): Result<RallyAnnotation> {
        addCalls += AddCall(clipId, timestampSeconds, body, kind)
        addError?.let { return Result.failure(it) }
        val row = RallyAnnotation(
            id = "new-${++nextId}",
            clipId = clipId,
            timestampSeconds = timestampSeconds,
            body = body,
            kind = kind,
            createdAt = Instant.parse("2026-05-04T12:00:00Z"),
        )
        byClipId = byClipId + (clipId to ((byClipId[clipId] ?: emptyList()) + row))
        return Result.success(row)
    }

    override suspend fun delete(id: String): Result<Unit> {
        deleteCalls += id
        deleteError?.let { return Result.failure(it) }
        byClipId = byClipId.mapValues { (_, v) -> v.filterNot { it.id == id } }
        return Result.success(Unit)
    }
}
```

**Step 2: Verify android tests still compile (ClipDetailViewModelTest will not yet — that's Task 5)**

Run: `./gradlew :androidApp:testDebugUnitTest --tests 'com.badmintontracker.android.testing.*'` (no-op if no tests there)

Expected: BUILD ends with compile errors only in `ClipDetailViewModelTest` because it still references `Triple(...)` for `addCalls`. Don't run the full test target yet.

**Step 3: Commit**

```bash
git add androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt
git commit -m "$(cat <<'EOF'
test(androidApp): FakeAnnotationsRepository carries kind

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ClipDetailViewModel.addAnnotation accepts kind (TDD)

**Files:**
- Modify: `androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt`

**Step 1: Update existing tests + add failing kind test**

In `ClipDetailViewModelTest.kt`, add an import:

```kotlin
import com.badmintontracker.android.testing.FakeAnnotationsRepository.AddCall
import com.badmintontracker.shared.model.AnnotationKind
```

Replace `addAnnotation_appends_to_state_in_timestamp_order`:

```kotlin
@Test
fun addAnnotation_appends_to_state_in_timestamp_order() = runTest {
    val existing = RallyAnnotation(
        "a1", "c1", 5.0f, "later",
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )
    val (vm, _, _, ann) = setup(annotations = listOf(existing))
    advanceUntilIdle()

    vm.addAnnotation(timestampSeconds = 2.0f, body = "earlier", kind = null)
    advanceUntilIdle()

    ann.addCalls shouldBe listOf(AddCall("c1", 2.0f, "earlier", null))
    vm.state.value.annotations.map { it.body } shouldBe listOf("earlier", "later")
    vm.state.value.actionError shouldBe null
}
```

Replace `addAnnotation_blank_body_is_ignored` with two tests covering the new rule:

```kotlin
@Test
fun addAnnotation_blank_body_and_no_kind_is_ignored() = runTest {
    val (vm, _, _, ann) = setup()
    advanceUntilIdle()

    vm.addAnnotation(timestampSeconds = 1f, body = "   ", kind = null)
    advanceUntilIdle()

    ann.addCalls.size shouldBe 0
    vm.state.value.annotations.size shouldBe 0
}

@Test
fun addAnnotation_blank_body_with_kind_is_persisted() = runTest {
    val (vm, _, _, ann) = setup()
    advanceUntilIdle()

    vm.addAnnotation(timestampSeconds = 3f, body = "", kind = AnnotationKind.GOOD_SHOT)
    advanceUntilIdle()

    ann.addCalls shouldBe listOf(AddCall("c1", 3f, "", AnnotationKind.GOOD_SHOT))
    vm.state.value.annotations.map { it.kind } shouldBe listOf(AnnotationKind.GOOD_SHOT)
}
```

Also update `addAnnotation_failure_sets_actionError` to pass `kind = null`:

```kotlin
@Test
fun addAnnotation_failure_sets_actionError() = runTest {
    val (vm, _, _, ann) = setup()
    ann.addError = RuntimeException("boom")
    advanceUntilIdle()

    vm.addAnnotation(1f, "x", null)
    advanceUntilIdle()

    vm.state.value.annotations.size shouldBe 0
    vm.state.value.actionError shouldBe "boom"
}
```

The existing `RallyAnnotation(...)` literal in `init_loads_clip_annotations_and_signs_url` uses 5 positional args ending with the `Instant`. Because `kind` is a new field BEFORE `createdAt`, change the call to use `createdAt =` named argument:

```kotlin
RallyAnnotation("a1", "c1", 1.5f, "great", createdAt = Instant.parse("2026-05-04T12:00:00Z"))
```

Apply the same fix to every `RallyAnnotation(...)` literal in this test file (search for `RallyAnnotation(` — there are six). Easiest: add a leading `createdAt = ` to the trailing `Instant.parse(...)` arg in each.

**Step 2: Run tests, verify failure**

Run: `./gradlew :androidApp:testDebugUnitTest --tests 'com.badmintontracker.android.clipdetail.ClipDetailViewModelTest'`
Expected: FAIL — compilation: `addAnnotation` does not accept 3 args.

**Step 3: Update ViewModel signature**

In `ClipDetailViewModel.kt`, change the `addAnnotation` method:

```kotlin
fun addAnnotation(timestampSeconds: Float, body: String, kind: AnnotationKind?) {
    val trimmed = body.trim()
    if (trimmed.isEmpty() && kind == null) return
    val ts = timestampSeconds.coerceAtLeast(0f)
    viewModelScope.launch {
        annotations.add(clipId, ts, trimmed, kind)
            .onSuccess { row ->
                state.update {
                    it.copy(
                        annotations = (it.annotations + row).sortedBy { a -> a.timestampSeconds },
                        actionError = null,
                    )
                }
            }
            .onFailure { e ->
                state.update { it.copy(actionError = e.message ?: "Couldn't add annotation") }
            }
    }
}
```

Add the import:

```kotlin
import com.badmintontracker.shared.model.AnnotationKind
```

**Step 4: Run tests, verify pass**

Run: `./gradlew :androidApp:testDebugUnitTest --tests 'com.badmintontracker.android.clipdetail.ClipDetailViewModelTest'`
Expected: PASS.

**Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt \
        androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(clipDetail): addAnnotation accepts AnnotationKind

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: AnnotationKindStyle helper

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationKindStyle.kt`

**Step 1: Create the helper**

```kotlin
package com.badmintontracker.android.clipdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.badmintontracker.shared.model.AnnotationKind

internal data class AnnotationKindStyle(
    val label: String,
    val container: Color,
    val onContainer: Color,
)

@Composable
internal fun AnnotationKind.style(): AnnotationKindStyle = when (this) {
    AnnotationKind.GOOD_SHOT      -> AnnotationKindStyle(
        label = "Good shot",
        container = Color(0xFF2E7D32),
        onContainer = Color.White,
    )
    AnnotationKind.FORCED_ERROR   -> AnnotationKindStyle(
        label = "Forced error",
        container = Color(0xFFB26A00),
        onContainer = Color.White,
    )
    AnnotationKind.UNFORCED_ERROR -> AnnotationKindStyle(
        label = "Unforced error",
        container = Color(0xFFC62828),
        onContainer = Color.White,
    )
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationKindStyle.kt
git commit -m "$(cat <<'EOF'
feat(clipDetail): AnnotationKindStyle palette + labels

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: AnnotationRow renders pill

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Update `AnnotationRow` to render the pill**

Replace the existing `AnnotationRow` composable (around lines 261-276) with:

```kotlin
@Composable
private fun AnnotationRow(a: RallyAnnotation, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        a.kind?.let { kind ->
            val s = kind.style()
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = s.container,
            ) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = s.onContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (a.body.isNotBlank()) {
            Text(
                a.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete annotation")
        }
    }
}
```

`Surface`, `Spacer`, and `width` are already imported in this file. `RoundedCornerShape` is referenced via fully-qualified name to avoid touching the import block; if you prefer, add `import androidx.compose.foundation.shape.RoundedCornerShape` and use the bare name.

**Step 2: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "$(cat <<'EOF'
feat(clipDetail): pill badge in AnnotationRow

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: AddAnnotationDialog gets chip selector

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`

**Step 1: Update `AddAnnotationDialog` and its caller**

Add imports near the top of the file:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.badmintontracker.shared.model.AnnotationKind
```

(`Arrangement` is already imported; skip if duplicate.)

Replace the `AddAnnotationDialog` composable with:

```kotlin
@Composable
private fun AddAnnotationDialog(
    onDismiss: () -> Unit,
    onConfirm: (body: String, kind: AnnotationKind?) -> Unit,
) {
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<AnnotationKind?>(null) }

    val canAdd = kind != null || body.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add annotation") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip("Good shot",      AnnotationKind.GOOD_SHOT,      kind) { kind = if (kind == it) null else it }
                    KindChip("Forced error",   AnnotationKind.FORCED_ERROR,   kind) { kind = if (kind == it) null else it }
                    KindChip("Unforced error", AnnotationKind.UNFORCED_ERROR, kind) { kind = if (kind == it) null else it }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("Note (optional)") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canAdd,
                onClick = { onConfirm(body, kind) },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun KindChip(
    label: String,
    target: AnnotationKind,
    selected: AnnotationKind?,
    onClick: (AnnotationKind) -> Unit,
) {
    val s = target.style()
    val isSelected = selected == target
    FilterChip(
        selected = isSelected,
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

Update the dialog's invocation in `ClipDetailScreen` (around line 233-241) to pass through the kind:

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

**Step 2: Build and verify**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

**Step 3: Manual smoke test**

The plan prescribes UI verification per the project's no-Compose-test policy:

1. Install on device/emulator: `./gradlew :androidApp:installDebug`.
2. Open the app, sign in, open any clip.
3. Tap **+**. Confirm three chips render. Confirm **Add** is disabled when no chip is selected and the text field is empty.
4. Select **Good shot** with no text → Add. Row should render the green pill alone.
5. Type a note + select **Forced error** → Add. Row should render amber pill + text.
6. Type a note alone → Add. Row should render text only (no pill), matching pre-existing behavior.
7. Confirm seek-on-tap still works for all rows.

If any step fails, fix and re-run the build before committing.

**Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "$(cat <<'EOF'
feat(clipDetail): badge picker in AddAnnotationDialog

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Supabase migration

**Files:**
- Create: `supabase/migrations/20260505000000_annotation_kind.sql`

**Step 1: Write the migration**

```sql
-- Adds shot-quality badges to rally_annotations.
-- See docs/plans/2026-05-05-annotation-badges-design.md

alter table public.rally_annotations
    add column if not exists kind text;

alter table public.rally_annotations
    drop constraint if exists rally_annotations_kind_check;

alter table public.rally_annotations
    add constraint rally_annotations_kind_check
    check (kind is null or kind in ('good_shot','forced_error','unforced_error'));

-- body becomes optional but at least one of body/kind must be present.
alter table public.rally_annotations
    alter column body drop not null;

alter table public.rally_annotations
    drop constraint if exists rally_annotations_body_or_kind_check;

alter table public.rally_annotations
    add constraint rally_annotations_body_or_kind_check
    check (
        kind is not null
        or (body is not null and length(trim(body)) > 0)
    );
```

**Step 2: Apply manually**

This repo does not use `supabase db push` in CI. Apply via Supabase Studio SQL editor (or `supabase db push` if the CLI is configured locally):

- Copy the migration file's contents into the Studio SQL editor and run it.
- Verify the column appears in the `rally_annotations` table and both check constraints are listed.

**Step 3: Commit the migration file**

```bash
git add supabase/migrations/20260505000000_annotation_kind.sql
git commit -m "$(cat <<'EOF'
feat(supabase): rally_annotations.kind column + checks

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Final verification

**Step 1: Run every check**

Run in sequence:

```bash
./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug
```

Expected: all three BUILD SUCCESSFUL.

**Step 2: Smoke test against the live DB**

After the migration is applied (Task 9 Step 2), repeat the manual smoke test from Task 8 Step 3 with the real Supabase backend selected. Verify:

- Adding a badge-only annotation returns success.
- Reload the screen — the badge persists and renders.
- Deleting a badged annotation works.

**Step 3: Final summary**

Do not commit anything in this task — it's a verification gate. If any step fails, return to the failing task to fix.

---

## Out of scope (do not implement here)

- Editing existing annotations to change a badge.
- Multiple badges per annotation.
- Filtering or stats by badge.
- iOS UI surface for badges.
