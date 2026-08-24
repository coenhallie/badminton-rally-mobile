# Custom Annotation Labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded three-case `AnnotationKind` with user-owned annotation labels that can be created, renamed, recoloured and deleted, and applied to annotations on both cloud clips and local videos.

**Architecture:** A new owner-scoped `annotation_labels` table is the source of truth, seeded DB side with the current three. Annotations no longer reference a kind; they carry a snapshot of the label's name and colour key, so a share recipient needs no cross-user read and a deleted label cannot orphan anything. `AnnotationKind` is deleted outright, which makes the compiler enumerate every call site.

**Tech Stack:** Kotlin Multiplatform (kotlinx-serialization, kotlinx-coroutines, multiplatform-settings, Supabase-kt), Jetpack Compose / Material 3 on Android, SwiftUI on iOS, Postgres with RLS on Supabase.

**Spec:** `docs/plans/2026-08-24-custom-annotation-labels-design.md`

## Global Constraints

- No em dash in any prose, comment, commit message or user-facing string. Use a plain dash.
- No agent attribution in commit messages.
- Label name: 1 to 24 characters after trimming, unique per owner case insensitively.
- Palette is exactly ten keys: `green`, `teal`, `blue`, `indigo`, `purple`, `pink`, `red`, `orange`, `amber`, `slate`. The DB CHECK, `LabelColor` and both platform colour tables must list the same ten.
- The three seeded labels are `Good shot`/`green`, `Forced error`/`amber`, `Unforced error`/`red`, and their rendered colours are byte-identical to today's badges.
- User-facing error copy is short and readable, never a raw technical dump. Follow `userFacingMessage`.
- Modals never carry substantial content. The Labels screen is a pushed route; editing expands in place.
- No manual reordering. Labels sort by `created_at` ascending.
- Test commands: `./gradlew :shared:jvmTest`, `./gradlew :androidApp:testDebugUnitTest`, `./gradlew :androidApp:assembleDebug`, `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`.

## File Structure

**Shared (KMP)**
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/LabelColor.kt` - the ten swatches and their rendered colours as raw ints, plus `from(key)`.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationLabel.kt` - the row.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepository.kt` - interface, impl, `StateFlow` cache.
- Delete `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationKind.kt`.
- Modify `model/RallyAnnotation.kt`, `localvideo/LocalAnnotation.kt`, `localvideo/LocalAnnotationsRepository.kt`, `repo/AnnotationsRepository.kt`, `RallyApp.kt`, `iosMain/SwiftInterop.kt`.

**Backend**
- Create `supabase/migrations/20260824000000_annotation_labels.sql`.

**Android**
- Create `androidApp/src/main/java/com/badmintontracker/android/labels/LabelsScreen.kt`, `LabelsViewModel.kt`.
- Modify `nav/Route.kt`, `RallyAndroidApp.kt`, `cliplist/ClipListScreen.kt`, `clipdetail/AnnotationUi.kt`, `clipdetail/ClipDetailViewModel.kt`, `localvideo/LocalPlayerViewModel.kt`.
- Delete `clipdetail/AnnotationKindStyle.kt`, replaced by `clipdetail/LabelBadge.kt`.

**iOS**
- Create `iosApp/Sources/Labels/LabelsView.swift`, `LabelsModel.swift`.
- Rename `Components/KindBadge.swift` to `Components/LabelBadge.swift`.
- Modify `ClipDetail/AddAnnotationSheet.swift`, `ClipDetail/ClipDetailModel.swift`, `ClipDetail/ClipDetailView.swift`, `ClipList/ClipListView.swift`, `LocalVideo/LocalPlayerModel.swift`, `LocalVideo/LocalPlayerView.swift`.

---

### Task 1: The colour palette

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/LabelColor.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/LabelColorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class LabelColor` with `val key: String`, `val background: Long`, `val foreground: Long`; `LabelColor.from(key: String?): LabelColor?`; `LabelColor.PALETTE: List<LabelColor>`.

Colours are plain ARGB longs so Android maps them with `Color(value)` and iOS with the existing `Color(rgb:)` helper. The palette is theme independent: a solid saturated pill with a fixed foreground reads correctly on both `#FFFFFF` and `#0D0D0D`, which is already true of the three badges shipping today. `green`, `amber` and `red` carry today's exact values so the seeded labels are visually unchanged.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LabelColorTest {

    @Test
    fun palette_has_ten_swatches_with_unique_keys() {
        LabelColor.PALETTE shouldHaveSize 10
        LabelColor.PALETTE.map { it.key }.toSet() shouldHaveSize 10
    }

    @Test
    fun from_resolves_a_known_key() {
        LabelColor.from("green") shouldBe LabelColor.GREEN
    }

    @Test
    fun from_returns_null_rather_than_throwing_on_an_unknown_key() {
        LabelColor.from("chartreuse").shouldBeNull()
        LabelColor.from(null).shouldBeNull()
    }

    @Test
    fun seeded_swatches_keep_the_colours_the_three_badges_ship_with() {
        LabelColor.GREEN.background shouldBe 0xFF2E7D32
        LabelColor.GREEN.foreground shouldBe 0xFFFFFFFF
        LabelColor.AMBER.background shouldBe 0xFFB26A00
        LabelColor.AMBER.foreground shouldBe 0xFF000000
        LabelColor.RED.background shouldBe 0xFFC62828
        LabelColor.RED.foreground shouldBe 0xFFFFFFFF
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "*LabelColorTest*"`
Expected: FAIL, unresolved reference `LabelColor`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.badmintontracker.shared.model

/**
 * The colours a label may take, shared so Android, iOS and the DB CHECK agree on
 * one set. Values are ARGB: Android maps them with Color(value), iOS with the
 * Color(rgb:) helper.
 *
 * Theme independent by design. Each entry is a solid saturated fill with a
 * foreground picked to clear 4.5:1 against it, which reads correctly on both the
 * light (#FFFFFF) and dark (#0D0D0D) app backgrounds. GREEN, AMBER and RED carry
 * the exact values the three original badges shipped with.
 */
enum class LabelColor(
    val key: String,
    val background: Long,
    val foreground: Long,
) {
    GREEN( "green",  0xFF2E7D32, WHITE),
    TEAL(  "teal",   0xFF00695C, WHITE),
    BLUE(  "blue",   0xFF1565C0, WHITE),
    INDIGO("indigo", 0xFF283593, WHITE),
    PURPLE("purple", 0xFF6A1B9A, WHITE),
    PINK(  "pink",   0xFFAD1457, WHITE),
    RED(   "red",    0xFFC62828, WHITE),
    ORANGE("orange", 0xFFEF6C00, BLACK),
    AMBER( "amber",  0xFFB26A00, BLACK),
    SLATE( "slate",  0xFF37474F, WHITE),
    ;

    companion object {
        /** Declaration order, which is also the order the swatch grid renders. */
        val PALETTE: List<LabelColor> = entries.toList()

        /**
         * Resolves a stored key. Returns null rather than throwing, so a snapshot
         * naming a swatch this build does not know renders as a neutral chip
         * instead of taking down the whole annotation list.
         */
        fun from(key: String?): LabelColor? = entries.firstOrNull { it.key == key }
    }
}

private const val WHITE = 0xFFFFFFFF
private const val BLACK = 0xFF000000
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "*LabelColorTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/LabelColor.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/LabelColorTest.kt
git commit -m "feat(shared): add the ten-swatch label colour palette"
```

---

### Task 2: The AnnotationLabel model

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationLabel.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/AnnotationLabelSerializationTest.kt`

**Interfaces:**
- Consumes: `LabelColor` from Task 1.
- Produces: `data class AnnotationLabel(id: String, name: String, colorKey: String, createdAt: Instant)` with `val color: LabelColor?`.

`colorKey` crosses serialization as a `String`, not as `LabelColor`. kotlinx throws `SerializationException` on an unknown enum value, so an enum here would make a row naming a swatch this build does not know undecodable, and the neutral-chip fallback unreachable.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

class AnnotationLabelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_a_row_and_resolves_its_colour() {
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l1","name":"Net kill","color_key":"teal",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        label.name shouldBe "Net kill"
        label.colorKey shouldBe "teal"
        label.color shouldBe LabelColor.TEAL
    }

    @Test
    fun decodes_a_row_naming_a_swatch_this_build_does_not_know() {
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l2","name":"Drive","color_key":"chartreuse",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        label.name shouldBe "Drive"
        label.color.shouldBeNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "*AnnotationLabelSerializationTest*"`
Expected: FAIL, unresolved reference `AnnotationLabel`.

- [ ] **Step 3: Write the implementation**

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
) {
    /** Null when the stored key is not in this build's palette. */
    val color: LabelColor? get() = LabelColor.from(colorKey)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "*AnnotationLabelSerializationTest*"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationLabel.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/model/AnnotationLabelSerializationTest.kt
git commit -m "feat(shared): add the AnnotationLabel model"
```

---

### Task 3: The database migration

**Files:**
- Create: `supabase/migrations/20260824000000_annotation_labels.sql`

**Interfaces:**
- Consumes: the ten palette keys from Task 1.
- Produces: table `public.annotation_labels`; columns `rally_annotations.label_name` and `rally_annotations.label_color`; `rally_annotations.kind` is gone.

There is no unit test harness for SQL in this repo, and `supabase db reset` cannot be used: `supabase/` tracks migrations only, with no `config.toml`, no `seed.sql` and no base schema, so a reset replays into an empty database and dies on the first `alter table`. The project is linked (`supabase/.temp/project-ref`), so this task is verified by pushing to the linked project and probing it.

- [ ] **Step 1: Write the migration**

```sql
-- Replaces the fixed three-case rally_annotations.kind with user-owned labels.
-- See docs/plans/2026-08-24-custom-annotation-labels-design.md

create table public.annotation_labels (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null default auth.uid()
               references auth.users(id) on delete cascade,
    name       text not null check (length(trim(name)) between 1 and 24),
    color_key  text not null check (color_key in (
                   'green','teal','blue','indigo','purple',
                   'pink','red','orange','amber','slate')),
    created_at timestamptz not null default now()
);

create unique index annotation_labels_owner_name_key
    on public.annotation_labels (owner_id, lower(name));

create index annotation_labels_owner_created_idx
    on public.annotation_labels (owner_id, created_at);

alter table public.annotation_labels enable row level security;

create policy annotation_labels_select_own on public.annotation_labels
    for select using (owner_id = auth.uid());
create policy annotation_labels_insert_own on public.annotation_labels
    for insert with check (owner_id = auth.uid());
create policy annotation_labels_update_own on public.annotation_labels
    for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy annotation_labels_delete_own on public.annotation_labels
    for delete using (owner_id = auth.uid());

-- Seeding. owner_id is set from new.id explicitly: inside an auth.users insert
-- there is no authenticated session, so the column default auth.uid() would
-- yield NULL, the not-null would fire, and signup itself would roll back.
-- security definer so the insert is not blocked by the policies above.
create or replace function public.seed_annotation_labels()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.annotation_labels (owner_id, name, color_key) values
        (new.id, 'Good shot',      'green'),
        (new.id, 'Forced error',   'amber'),
        (new.id, 'Unforced error', 'red')
    on conflict do nothing;
    return new;
end;
$$;

create trigger seed_annotation_labels_on_signup
    after insert on auth.users
    for each row execute function public.seed_annotation_labels();

-- Backfill accounts that already exist.
insert into public.annotation_labels (owner_id, name, color_key)
select u.id, d.name, d.color_key
from auth.users u
cross join (values
    ('Good shot',      'green'),
    ('Forced error',   'amber'),
    ('Unforced error', 'red')
) as d(name, color_key)
on conflict do nothing;

-- rally_annotations: snapshot the label instead of referencing a kind.
alter table public.rally_annotations
    add column label_name  text,
    add column label_color text;

-- Backfill before the column goes. add constraint validates existing rows on
-- the spot, and 20260505000000 dropped body's not-null precisely so a
-- badge-only annotation could exist; such a row would have a null body and a
-- null label_name, violate the new CHECK, and abort this migration.
update public.rally_annotations set
    label_name  = case kind when 'good_shot'      then 'Good shot'
                            when 'forced_error'   then 'Forced error'
                            when 'unforced_error' then 'Unforced error' end,
    label_color = case kind when 'good_shot'      then 'green'
                            when 'forced_error'   then 'amber'
                            when 'unforced_error' then 'red' end
where kind is not null;

alter table public.rally_annotations
    drop constraint if exists rally_annotations_body_or_kind_check,
    drop constraint if exists rally_annotations_kind_check,
    drop column if exists kind;

alter table public.rally_annotations
    add constraint rally_annotations_body_or_label_check
    check (
        (label_name is not null and length(trim(label_name)) > 0)
        or (body is not null and length(trim(body)) > 0)
    );
```

- [ ] **Step 2: Push the migration**

Run: `supabase db push`
Expected: the CLI lists `20260824000000_annotation_labels.sql` as pending and applies it with no error. Do not run `supabase db reset`; see the note above.

- [ ] **Step 3: Verify the resulting schema**

In the Supabase dashboard SQL editor for the linked project, run:

```sql
select column_name from information_schema.columns
where table_name = 'rally_annotations' and column_name in ('kind','label_name','label_color');

select conname from pg_constraint where conrelid = 'public.rally_annotations'::regclass;

select relrowsecurity from pg_class where oid = 'public.annotation_labels'::regclass;

select name, color_key from public.annotation_labels order by created_at;
```

Expected: `label_name` and `label_color` present and `kind` absent; `rally_annotations_body_or_label_check` present and neither `rally_annotations_kind_check` nor `rally_annotations_body_or_kind_check`; RLS true; the three seeded labels listed as `Good shot`/`green`, `Forced error`/`amber`, `Unforced error`/`red` for your own account.

- [ ] **Step 4: Verify a label-only annotation is accepted and an empty one is not**

In the same SQL editor, substituting a real clip id you own:

```sql
-- must succeed
insert into public.rally_annotations (clip_id, timestamp_seconds, label_name, label_color)
values ('<clip-id>', 1.5, 'Net kill', 'teal');

-- must fail with rally_annotations_body_or_label_check
insert into public.rally_annotations (clip_id, timestamp_seconds)
values ('<clip-id>', 2.0);
```

Expected: the first inserts one row, the second raises `new row for relation "rally_annotations" violates check constraint "rally_annotations_body_or_label_check"`. Delete the probe row afterwards.

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations/20260824000000_annotation_labels.sql
git commit -m "feat(db): add annotation_labels and snapshot labels onto annotations"
```

---

### Task 4: The labels repository

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AnnotationLabel`, `LabelColor` from Tasks 1 and 2; `TestSupabase.client`, `jsonResponse` from `shared/src/commonTest/.../testing/`.
- Produces: `interface AnnotationLabelsRepository` with `labels: StateFlow<List<AnnotationLabel>>`, `refresh()`, `create(name, color)`, `rename(id, name)`, `recolor(id, color)`, `delete(id)`; and `AnnotationLabelsRepositoryImpl(client, settings)`.

`create` is called from the picker with a null colour, so it must always resolve to some swatch. It takes the first swatch not already in use, and once all ten are taken falls back to `PALETTE[size % 10]` rather than returning null.

The last known list is cached in `Settings` under `annotation_labels_cache`, so the local video player has a picker while offline.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AnnotationLabelsRepositoryTest {

    private val seeded = """
      [
        {"id":"l1","name":"Good shot","color_key":"green","created_at":"2026-08-24T12:00:00Z"},
        {"id":"l2","name":"Forced error","color_key":"amber","created_at":"2026-08-24T12:00:01Z"},
        {"id":"l3","name":"Unforced error","color_key":"red","created_at":"2026-08-24T12:00:02Z"}
      ]
    """.trimIndent()

    @Test
    fun refresh_loads_labels_in_creation_order_and_publishes_them() = runTest {
        var capturedUrl: String? = null
        val client = TestSupabase.client { request ->
            capturedUrl = request.url.toString()
            jsonResponse(seeded)
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        repo.refresh().isSuccess shouldBe true

        repo.labels.value shouldHaveSize 3
        repo.labels.value[0].name shouldBe "Good shot"
        capturedUrl!!.shouldContain("annotation_labels")
        capturedUrl!!.shouldContain("order=created_at.asc")
    }

    @Test
    fun refresh_caches_the_list_so_a_cold_start_offline_still_has_a_picker() = runTest {
        val settings = MapSettings()
        val client = TestSupabase.client { jsonResponse(seeded) }
        AnnotationLabelsRepositoryImpl(client, settings).refresh()

        val offline = TestSupabase.client { error("offline") }
        AnnotationLabelsRepositoryImpl(offline, settings).labels.value shouldHaveSize 3
    }

    @Test
    fun create_with_no_colour_takes_the_first_unused_swatch() = runTest {
        var posted: String? = null
        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text
            if (body == null) jsonResponse(seeded)
            else {
                posted = body
                jsonResponse(
                    """[{"id":"l4","name":"Net kill","color_key":"teal","created_at":"2026-08-24T12:00:03Z"}]""",
                    HttpStatusCode.Created,
                )
            }
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        val created = repo.create("Net kill", null)

        created.isSuccess shouldBe true
        // green, amber and red are taken by the seeded three, so teal is next.
        posted!!.shouldContain(""""color_key":"teal"""")
        repo.labels.value shouldHaveSize 4
    }

    @Test
    fun create_trims_the_name_and_rejects_a_blank_one() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        repo.create("   ", null).isFailure shouldBe true
    }

    @Test
    fun create_rejects_a_name_already_in_use_regardless_of_case() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.create("good SHOT", null).isFailure shouldBe true
    }

    @Test
    fun delete_drops_the_label_from_the_published_list() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.delete("l2").isSuccess shouldBe true

        repo.labels.value shouldHaveSize 2
        repo.labels.value.none { it.id == "l2" } shouldBe true
    }

    @Test
    fun recolor_updates_the_published_list() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.recolor("l1", LabelColor.SLATE).isSuccess shouldBe true

        repo.labels.value.first { it.id == "l1" }.colorKey shouldBe "slate"
    }

    @Test
    fun nextUnusedColor_wraps_once_every_swatch_is_taken() {
        val taken = LabelColor.PALETTE.map { it.key }
        AnnotationLabelsRepositoryImpl.nextUnusedColor(taken) shouldBe LabelColor.PALETTE[0]
        AnnotationLabelsRepositoryImpl.nextUnusedColor(taken + "green") shouldBe LabelColor.PALETTE[1]
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "*AnnotationLabelsRepositoryTest*"`
Expected: FAIL, unresolved reference `AnnotationLabelsRepositoryImpl`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface AnnotationLabelsRepository {
    /** Last known list, creation order. Survives a cold start offline. */
    val labels: StateFlow<List<AnnotationLabel>>
    suspend fun refresh(): Result<Unit>
    /** [color] null picks a swatch automatically; the inline picker path passes null. */
    suspend fun create(name: String, color: LabelColor?): Result<AnnotationLabel>
    suspend fun rename(id: String, name: String): Result<Unit>
    suspend fun recolor(id: String, color: LabelColor): Result<Unit>
    suspend fun delete(id: String): Result<Unit>
}

class AnnotationLabelsRepositoryImpl(
    private val client: SupabaseClient,
    private val settings: Settings,
) : AnnotationLabelsRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AnnotationLabel.serializer())

    private val state = MutableStateFlow(loadCache())
    override val labels: StateFlow<List<AnnotationLabel>> = state.asStateFlow()

    @Serializable private data class NewLabelRow(
        val name: String,
        @SerialName("color_key") val colorKey: String,
    )
    @Serializable private data class NamePatch(val name: String)
    @Serializable private data class ColorPatch(@SerialName("color_key") val colorKey: String)

    override suspend fun refresh(): Result<Unit> = runCatching {
        val rows = client.postgrest.from(TABLE)
            .select { order("created_at", Order.ASCENDING) }
            .decodeList<AnnotationLabel>()
        publish(rows)
    }

    override suspend fun create(name: String, color: LabelColor?): Result<AnnotationLabel> {
        val trimmed = name.trim()
        validate(trimmed)?.let { return Result.failure(it) }
        val swatch = color ?: nextUnusedColor(state.value.map { it.colorKey })
        return runCatching {
            val row = client.postgrest.from(TABLE)
                .insert(NewLabelRow(trimmed, swatch.key)) { select() }
                .decodeSingle<AnnotationLabel>()
            publish(state.value + row)
            row
        }.recoverCatching { throw it.asDuplicateName(trimmed) }
    }

    override suspend fun rename(id: String, name: String): Result<Unit> {
        val trimmed = name.trim()
        validate(trimmed, ignoringId = id)?.let { return Result.failure(it) }
        return runCatching {
            client.postgrest.from(TABLE).update(NamePatch(trimmed)) { filter { eq("id", id) } }
            publish(state.value.map { if (it.id == id) it.copy(name = trimmed) else it })
        }.recoverCatching { throw it.asDuplicateName(trimmed) }
    }

    override suspend fun recolor(id: String, color: LabelColor): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).update(ColorPatch(color.key)) { filter { eq("id", id) } }
        publish(state.value.map { if (it.id == id) it.copy(colorKey = color.key) else it })
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).delete { filter { eq("id", id) } }
        publish(state.value.filterNot { it.id == id })
    }

    /**
     * The unique index is the real authority, and [validate] can only see the
     * in-memory list: on a cold start offline, or before the first refresh lands,
     * that list is empty and the duplicate check passes. So the server's rejection
     * is a normal path, not an edge case, and it gets the same sentence rather
     * than surfacing as "HTTP 409".
     */
    private fun Throwable.asDuplicateName(name: String): Throwable {
        val text = message.orEmpty()
        val duplicate = "23505" in text ||
            "duplicate key" in text ||
            "annotation_labels_owner_name_key" in text
        return if (duplicate) IllegalArgumentException("You already have a label called \"$name\".") else this
    }

    /**
     * Mirrors the DB's own rules so the user sees the problem before a round trip,
     * and so the message is ours rather than a Postgres constraint name.
     */
    private fun validate(trimmed: String, ignoringId: String? = null): Throwable? = when {
        trimmed.isEmpty() -> IllegalArgumentException("Give the label a name.")
        trimmed.length > MAX_NAME -> IllegalArgumentException("Keep the name under $MAX_NAME characters.")
        state.value.any { it.id != ignoringId && it.name.equals(trimmed, ignoreCase = true) } ->
            IllegalArgumentException("You already have a label called \"$trimmed\".")
        else -> null
    }

    private fun publish(next: List<AnnotationLabel>) {
        state.value = next
        settings.putString(KEY_CACHE, json.encodeToString(serializer, next))
    }

    private fun loadCache(): List<AnnotationLabel> =
        settings.getStringOrNull(KEY_CACHE)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?: emptyList()

    companion object {
        private const val TABLE = "annotation_labels"
        private const val KEY_CACHE = "annotation_labels_cache"
        private const val MAX_NAME = 24

        /**
         * First swatch not already in use. Past ten labels every swatch is taken,
         * so it wraps by count rather than returning null: the inline picker path
         * always passes a null colour and has no way to handle running out.
         */
        fun nextUnusedColor(takenKeys: List<String>): LabelColor =
            LabelColor.PALETTE.firstOrNull { it.key !in takenKeys }
                ?: LabelColor.PALETTE[takenKeys.size % LabelColor.PALETTE.size]
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "*AnnotationLabelsRepositoryTest*"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationLabelsRepositoryTest.kt
git commit -m "feat(shared): add AnnotationLabelsRepository with an offline cache"
```

---
### Task 5: Cut cloud annotations over to the snapshot

**Files:**
- Delete: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationKind.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/RallyAnnotation.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/model/RallyAnnotationSerializationTest.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AnnotationLabel`, `LabelColor` from Tasks 1 and 2.
- Produces: `RallyAnnotation(id, clipId, timestampSeconds, body, labelName, labelColor, createdAt)` with `val color: LabelColor?`; `AnnotationsRepository.add(clipId, timestampSeconds, body, label: AnnotationLabel?)`.

Deleting `AnnotationKind` is deliberate and is what makes the rest of this plan tractable: the compiler now lists every site that needs attention. Expect `:shared:jvmTest` to fail to compile until Task 6, and both apps until Tasks 8 to 12. Work through the errors in order.

- [ ] **Step 1: Update the serialization test**

Replace the whole of `RallyAnnotationSerializationTest.kt` with:

```kotlin
package com.badmintontracker.shared.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

class RallyAnnotationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_a_row_carrying_a_label_snapshot() {
        val row = json.decodeFromString<RallyAnnotation>(
            """{"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"",
                "label_name":"Net kill","label_color":"teal",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        row.labelName shouldBe "Net kill"
        row.color shouldBe LabelColor.TEAL
    }

    @Test
    fun decodes_a_plain_text_row_with_no_label() {
        val row = json.decodeFromString<RallyAnnotation>(
            """{"id":"a2","clip_id":"c1","timestamp_seconds":3.0,"body":"good length",
                "created_at":"2026-08-24T12:00:01Z"}"""
        )

        row.body shouldBe "good length"
        row.labelName.shouldBeNull()
        row.color.shouldBeNull()
    }

    @Test
    fun a_snapshot_naming_an_unknown_swatch_still_decodes() {
        val row = json.decodeFromString<RallyAnnotation>(
            """{"id":"a3","clip_id":"c1","timestamp_seconds":4.0,"body":"",
                "label_name":"Drive","label_color":"chartreuse",
                "created_at":"2026-08-24T12:00:02Z"}"""
        )

        row.labelName shouldBe "Drive"
        row.color.shouldBeNull()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "*RallyAnnotationSerializationTest*"`
Expected: FAIL, unresolved reference `labelName`.

- [ ] **Step 3: Update the model and delete the enum**

`RallyAnnotation.kt` becomes:

```kotlin
package com.badmintontracker.shared.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RallyAnnotation(
    val id: String,
    @SerialName("clip_id")           val clipId: String,
    @SerialName("timestamp_seconds") val timestampSeconds: Float,
    val body: String,
    /**
     * The label's name and colour as they were when the annotation was made.
     * Snapshotted rather than referenced so a share recipient needs no read on
     * the sharer's labels, and deleting a label cannot orphan an annotation.
     */
    @SerialName("label_name")        val labelName: String? = null,
    @SerialName("label_color")       val labelColor: String? = null,
    @SerialName("created_at")        val createdAt: Instant,
) {
    val color: LabelColor? get() = LabelColor.from(labelColor)
}
```

Then delete the enum:

```bash
git rm shared/src/commonMain/kotlin/com/badmintontracker/shared/model/AnnotationKind.kt
```

- [ ] **Step 4: Update the repository**

In `AnnotationsRepository.kt`, replace the `AnnotationKind` import with `AnnotationLabel`, and change the interface method and impl:

```kotlin
    suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): Result<RallyAnnotation>
```

```kotlin
    @Serializable
    private data class NewAnnotationRow(
        @SerialName("clip_id")           val clipId: String,
        @SerialName("timestamp_seconds") val timestampSeconds: Float,
        val body: String,
        @SerialName("label_name")  val labelName: String? = null,
        @SerialName("label_color") val labelColor: String? = null,
    )

    override suspend fun add(
        clipId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): Result<RallyAnnotation> = runCatching {
        // owner_id is filled server-side via the column's `default auth.uid()`.
        client.postgrest.from("rally_annotations")
            .insert(
                NewAnnotationRow(clipId, timestampSeconds, body, label?.name, label?.colorKey)
            ) { select() }
            .decodeSingle<RallyAnnotation>()
    }
```

- [ ] **Step 5: Update the repository test**

In `AnnotationsRepositoryTest.kt`, replace the `AnnotationKind` import with `AnnotationLabel` and `kotlinx.datetime.Instant`, change `repo.add("c1", 1.5f, "hi", null)` to keep its trailing null, and replace the `add_includes_kind_in_post_body_when_provided` test with:

```kotlin
    @Test
    fun add_snapshots_the_label_name_and_colour_into_the_post_body() = runTest {
        var captured: String? = null
        val client = TestSupabase.client { request ->
            captured = (request.body as? TextContent)?.text ?: ""
            jsonResponse(
                """[{"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"",
                     "label_name":"Net kill","label_color":"teal",
                     "created_at":"2026-08-24T12:00:00Z"}]""",
                HttpStatusCode.Created,
            )
        }
        val repo = AnnotationsRepositoryImpl(client)
        val label = AnnotationLabel(
            id = "l4",
            name = "Net kill",
            colorKey = "teal",
            createdAt = Instant.parse("2026-08-24T12:00:00Z"),
        )

        val result = repo.add("c1", 1.5f, "", label)

        result.isSuccess shouldBe true
        result.getOrThrow().labelName shouldBe "Net kill"
        captured!!.shouldContain(""""label_name":"Net kill"""")
        captured!!.shouldContain(""""label_color":"teal"""")
    }
```

- [ ] **Step 6: Run the shared tests**

Run: `./gradlew :shared:jvmTest --tests "*RallyAnnotation*" --tests "*AnnotationsRepositoryTest*"`
Expected: PASS. If `LocalAnnotation.kt` still fails to compile, that is Task 6; leave it and come back to this step after Task 6 if the module will not build.

- [ ] **Step 7: Commit**

```bash
git add -A shared/src/commonMain/kotlin/com/badmintontracker/shared/model \
           shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AnnotationsRepository.kt \
           shared/src/commonTest/kotlin/com/badmintontracker/shared/model/RallyAnnotationSerializationTest.kt \
           shared/src/commonTest/kotlin/com/badmintontracker/shared/repo/AnnotationsRepositoryTest.kt
git commit -m "feat(shared): snapshot labels onto RallyAnnotation, delete AnnotationKind"
```

---

### Task 6: Cut local annotations over to the snapshot

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotation.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AnnotationLabel`, `LabelColor`.
- Produces: `LocalAnnotation(id, timestampSeconds, body, labelName, labelColor, createdAtEpochMs)` with `val color: LabelColor?`; `LocalAnnotationsRepository.add(videoId, timestampSeconds, body, label: AnnotationLabel?)`.

Files already on disk still carry `"kind"`. `LocalAnnotationsRepository.kt:20` constructs its decoder as `Json { ignoreUnknownKeys = true }`, so those files decode cleanly with a null label: the note and timestamp survive, the badge does not. Do not add a compatibility branch, and do not remove that flag.

- [ ] **Step 1: Write the failing test**

Append to `LocalAnnotationsRepositoryTest.kt`:

```kotlin
    @Test
    fun add_stores_the_label_snapshot() {
        val repo = LocalAnnotationsRepository(MapSettings())
        val label = AnnotationLabel(
            id = "l4",
            name = "Net kill",
            colorKey = "teal",
            createdAt = Instant.parse("2026-08-24T12:00:00Z"),
        )

        repo.add("v1", 1.5f, "", label)

        val stored = repo.annotationsFor("v1").single()
        stored.labelName shouldBe "Net kill"
        stored.color shouldBe LabelColor.TEAL
    }

    @Test
    fun a_file_written_before_labels_existed_still_decodes() {
        val settings = MapSettings()
        settings.putString(
            "local_annotations",
            """{"v1":[{"id":"a1","timestampSeconds":1.5,"body":"good length",
                 "kind":"good_shot","createdAtEpochMs":1000}]}""",
        )

        val stored = LocalAnnotationsRepository(settings).annotationsFor("v1").single()

        stored.body shouldBe "good length"
        stored.labelName.shouldBeNull()
    }
```

Add the imports the new tests need: `com.badmintontracker.shared.model.AnnotationLabel`, `com.badmintontracker.shared.model.LabelColor`, `io.kotest.matchers.nulls.shouldBeNull`, `kotlinx.datetime.Instant`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "*LocalAnnotationsRepositoryTest*"`
Expected: FAIL, unresolved reference `labelName`.

- [ ] **Step 3: Update the model**

`LocalAnnotation.kt` becomes:

```kotlin
package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.LabelColor
import kotlinx.serialization.Serializable

/** A timestamped note on a local (on-phone) video. Stored on-device only. */
@Serializable
data class LocalAnnotation(
    val id: String,
    val timestampSeconds: Float,
    val body: String,                 // may be blank when only a label is set
    val labelName: String? = null,
    val labelColor: String? = null,
    val createdAtEpochMs: Long,
) {
    val color: LabelColor? get() = LabelColor.from(labelColor)
}
```

- [ ] **Step 4: Update the repository**

In `LocalAnnotationsRepository.kt`, replace the `AnnotationKind` import with `com.badmintontracker.shared.model.AnnotationLabel` and change `add`:

```kotlin
    fun add(
        videoId: String,
        timestampSeconds: Float,
        body: String,
        label: AnnotationLabel?,
    ): LocalAnnotation {
        val annotation = LocalAnnotation(
            id = randomUuid(),
            timestampSeconds = timestampSeconds,
            body = body,
            labelName = label?.name,
            labelColor = label?.colorKey,
            createdAtEpochMs = nowEpochMs(),
        )
        mutate(videoId) { it + annotation }
        return annotation
    }
```

- [ ] **Step 5: Run the whole shared suite**

Run: `./gradlew :shared:jvmTest`
Expected: PASS. `AnnotationKind` should now appear in no shared source; confirm with `grep -rn "AnnotationKind" shared/src` returning nothing.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo
git commit -m "feat(shared): snapshot labels onto LocalAnnotation"
```

---

### Task 7: Wire the repository into the app graph

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`

**Interfaces:**
- Consumes: `AnnotationLabelsRepository` from Task 4.
- Produces: `RallyApp.labels: AnnotationLabelsRepository`; `AnnotationsRepository.addAnnotationForSwift(clipId, timestampSeconds, body, label)`; `AnnotationLabelsRepository.createLabelForSwift(name)`, `renameLabelOrMessage(id, name)`, `recolorLabelOrMessage(id, color)`, `deleteLabelOrMessage(id)`, `refreshLabelsOrMessage()`.

Swift cannot see Kotlin's `Result`, which is why every repository already has an interop wrapper. The new label operations need the same treatment.

- [ ] **Step 1: Add the repository to RallyApp**

In `RallyApp.kt`, add the import `com.badmintontracker.shared.repo.AnnotationLabelsRepository` and `...AnnotationLabelsRepositoryImpl`, then add to the repository block:

```kotlin
    val labels: AnnotationLabelsRepository = AnnotationLabelsRepositoryImpl(client, settings)
```

- [ ] **Step 2: Update the Swift interop**

In `SwiftInterop.kt`, replace the `AnnotationKind` import with `AnnotationLabel`, change the add wrapper's parameter, and append the label wrappers:

```kotlin
suspend fun AnnotationsRepository.addAnnotationForSwift(
    clipId: String,
    timestampSeconds: Float,
    body: String,
    label: AnnotationLabel?,
): AddAnnotationOutcome = add(clipId, timestampSeconds, body, label).fold(
    onSuccess = { AddAnnotationOutcome(it, null) },
    onFailure = { AddAnnotationOutcome(null, it.userFacingMessage("Couldn't add note")) },
)

class CreateLabelOutcome(val label: AnnotationLabel?, val errorMessage: String?)

suspend fun AnnotationLabelsRepository.createLabelForSwift(name: String): CreateLabelOutcome =
    create(name, null).fold(
        onSuccess = { CreateLabelOutcome(it, null) },
        onFailure = { CreateLabelOutcome(null, it.userFacingMessage("Couldn't add label")) },
    )

suspend fun AnnotationLabelsRepository.renameLabelOrMessage(id: String, name: String): String? =
    rename(id, name).exceptionOrNull()?.let { it.userFacingMessage("Couldn't rename label") }

suspend fun AnnotationLabelsRepository.recolorLabelOrMessage(id: String, color: LabelColor): String? =
    recolor(id, color).exceptionOrNull()?.let { it.userFacingMessage("Couldn't change colour") }

suspend fun AnnotationLabelsRepository.deleteLabelOrMessage(id: String): String? =
    delete(id).exceptionOrNull()?.let { it.userFacingMessage("Couldn't delete label") }

suspend fun AnnotationLabelsRepository.refreshLabelsOrMessage(): String? =
    refresh().exceptionOrNull()?.let { it.userFacingMessage("Couldn't load labels") }
```

Add the imports these need: `com.badmintontracker.shared.model.AnnotationLabel`, `com.badmintontracker.shared.model.LabelColor`, `com.badmintontracker.shared.repo.AnnotationLabelsRepository`.

- [ ] **Step 3: Verify both targets still build**

Run: `./gradlew :shared:jvmTest && ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`
Expected: both succeed.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt \
        shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt
git commit -m "feat(shared): expose the labels repository to both platforms"
```

---
### Task 8: Android badge and picker driven by the label list

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/LabelBadge.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationLabelsRepository.kt`
- Delete: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationKindStyle.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationUi.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/testing/FakeAnnotationsRepository.kt`

**Interfaces:**
- Consumes: `RallyApp.labels`, `AnnotationLabel`, `LabelColor`.
- Produces: `@Composable fun LabelBadge(name: String, colorKey: String?)`; `AddAnnotationSheet(labels: List<AnnotationLabel>, onDismiss, onConfirm: (String, AnnotationLabel?) -> Unit)`; `ClipDetailViewModel.addAnnotation(timestampSeconds, body, label: AnnotationLabel?)`; `ClipDetailState.labels: List<AnnotationLabel>` and `ClipDetailState.errorMessage: String?`.

- [ ] **Step 1: Update the ViewModel test**

In `ClipDetailViewModelTest.kt`, replace the `AnnotationKind` import with `AnnotationLabel` and add:

```kotlin
    @Test
    fun addAnnotation_passes_the_label_through_to_the_repository() = runTest {
        val repo = FakeAnnotationsRepository()
        val labels = FakeAnnotationLabelsRepository(listOf(netKill))
        val vm = ClipDetailViewModel(
            clipId = "c1", clips = FakeClipsRepository(), annotations = repo,
            media = FakeMediaRepository(), auth = FakeAuthRepository(), labels = labels,
        )

        vm.addAnnotation(1.5f, "", netKill)
        advanceUntilIdle()

        repo.added.single().label shouldBe netKill
    }

    @Test
    fun addAnnotation_is_ignored_when_both_body_and_label_are_empty() = runTest {
        val repo = FakeAnnotationsRepository()
        val vm = ClipDetailViewModel(
            clipId = "c1", clips = FakeClipsRepository(), annotations = repo,
            media = FakeMediaRepository(), auth = FakeAuthRepository(),
            labels = FakeAnnotationLabelsRepository(emptyList()),
        )

        vm.addAnnotation(1.5f, "   ", null)
        advanceUntilIdle()

        repo.added.shouldBeEmpty()
    }
```

with, at the top of the class:

```kotlin
    private val netKill = AnnotationLabel(
        id = "l4", name = "Net kill", colorKey = "teal",
        createdAt = Instant.parse("2026-08-24T12:00:00Z"),
    )
```

Update `FakeAnnotationsRepository` so `add` takes `label: AnnotationLabel?` and records it, and add a `FakeAnnotationLabelsRepository(initial: List<AnnotationLabel>)` next to it that implements `AnnotationLabelsRepository` over a `MutableStateFlow`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*ClipDetailViewModelTest*"`
Expected: FAIL to compile, `AnnotationKind` unresolved and `ClipDetailViewModel` has no `labels` parameter.

- [ ] **Step 3: Write LabelBadge.kt and delete AnnotationKindStyle**

```kotlin
package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.model.LabelColor

/**
 * A label's pill. [colorKey] null, or naming a swatch this build does not know,
 * renders the neutral chip rather than nothing: the name is the information, the
 * colour is decoration.
 */
@Composable
fun LabelBadge(name: String, colorKey: String?, modifier: Modifier = Modifier) {
    val swatch = LabelColor.from(colorKey)
    val container = swatch?.let { Color(it.background.toInt()) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    val onContainer = swatch?.let { Color(it.foreground.toInt()) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant

    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = container) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
```

```bash
git rm androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationKindStyle.kt
```

- [ ] **Step 4: Drive AnnotationUi from the list**

In `AnnotationUi.kt`: replace the `AnnotationKind` import with `AnnotationLabel`, change `AnnotationRow`'s `kind: AnnotationKind?` parameter to `labelName: String?` and `labelColor: String?`, and replace the `kind?.let { ... }` block with:

```kotlin
        labelName?.let { name ->
            LabelBadge(name = name, colorKey = labelColor)
            Spacer(Modifier.width(8.dp))
        }
```

Change `AddAnnotationSheet`'s signature and chip row:

```kotlin
internal fun AddAnnotationSheet(
    labels: List<AnnotationLabel>,
    onDismiss: () -> Unit,
    onConfirm: (body: String, label: AnnotationLabel?) -> Unit,
)
```

```kotlin
    var label by remember { mutableStateOf<AnnotationLabel?>(null) }
    val canAdd = label != null || body.isNotBlank()
```

```kotlin
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labels.forEach { candidate ->
                    LabelChip(
                        label = candidate,
                        selected = label?.id == candidate.id,
                        onClick = { label = if (label?.id == candidate.id) null else candidate },
                    )
                }
            }
```

and replace `KindChip` with:

```kotlin
@Composable
private fun LabelChip(label: AnnotationLabel, selected: Boolean, onClick: () -> Unit) {
    val swatch = LabelColor.from(label.colorKey)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label.name) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = swatch?.let { Color(it.background.toInt()) }
                ?: MaterialTheme.colorScheme.surfaceVariant,
            selectedLabelColor = swatch?.let { Color(it.foreground.toInt()) }
                ?: MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
```

- [ ] **Step 5: Thread the labels through both ViewModels and screens**

`ClipDetailViewModel`: add a `private val labels: AnnotationLabelsRepository` as the sixth constructor parameter, after the existing `clipId, clips, annotations, media, auth`, add `val labels: List<AnnotationLabel> = emptyList()` to `ClipDetailState` (the existing state class, exposed as `val state = MutableStateFlow(ClipDetailState())`), collect `labels.labels` into that field in `init`, call `labels.refresh()` alongside the existing load, and change `addAnnotation` to take `label: AnnotationLabel?`, guarding with `if (trimmed.isEmpty() && label == null) return` and calling `annotations.add(clipId, ts, trimmed, label)`.

`LocalPlayerViewModel`: add the same `labels: AnnotationLabelsRepository` parameter and make the same `addAnnotation(timestampSeconds, body, label)` change, calling `annotations.add(videoId, timestampSeconds.coerceAtLeast(0f), trimmed, label)`. Note this ViewModel has no state object: `state` is a bare `StateFlow<List<LocalAnnotation>>`. Do not introduce one. Add two separate flows beside it:

```kotlin
    val labelOptions: StateFlow<List<AnnotationLabel>> = labels.labels
        .stateIn(viewModelScope, SharingStarted.Eagerly, labels.labels.value)

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun errorShown() { _errorMessage.value = null }
```

`ClipDetailScreen` and `LocalPlayerScreen`: pass `state.labels` into `AddAnnotationSheet` and pass `annotation.labelName`/`annotation.labelColor` into `AnnotationRow`.

`AuthGate.kt`: add `rally.labels` to both `initializer { ClipDetailViewModel(...) }` and the `LocalPlayerViewModel` initializer.

- [ ] **Step 6: Run the Android tests**

Run: `./gradlew :androidApp:testDebugUnitTest`
Expected: PASS. Then `grep -rn "AnnotationKind" androidApp/src` must return nothing.

- [ ] **Step 7: Build the app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A androidApp/src
git commit -m "feat(android): render annotation badges from the user's label list"
```

---

### Task 9: The Android Labels screen

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/labels/LabelsViewModel.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/labels/LabelsScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/labels/LabelsViewModelTest.kt`

**Interfaces:**
- Consumes: `AnnotationLabelsRepository`, `LabelColor.PALETTE`.
- Produces: `Route.Labels`; `LabelsViewModel(labels)` with `state: StateFlow<LabelsUiState>`, `create(name)`, `rename(id, name)`, `recolor(id, color)`, `delete(id)`, `expand(id?)`.

A pushed route, not a sheet. The list is plain rows on the app background: a colour dot, the name, nothing else. Tapping a row expands it in place to reveal a single-line name field and one row of swatches. Delete is a trailing swipe through the existing `SwipeToRemoveRow`, confirmed through `ConfirmDialog`, whose body states that annotations already tagged keep their badge.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.badmintontracker.android.labels

import com.badmintontracker.android.testing.FakeAnnotationLabelsRepository
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class LabelsViewModelTest {

    private fun label(id: String, name: String, key: String) = AnnotationLabel(
        id = id, name = name, colorKey = key,
        createdAt = Instant.parse("2026-08-24T12:00:00Z"),
    )

    @Test
    fun publishes_the_repository_list() = runTest {
        val repo = FakeAnnotationLabelsRepository(listOf(label("l1", "Good shot", "green")))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.state.value.labels shouldHaveSize 1
    }

    @Test
    fun expanding_a_row_collapses_the_previous_one() {
        val vm = LabelsViewModel(FakeAnnotationLabelsRepository(emptyList()))

        vm.expand("l1")
        vm.state.value.expandedId shouldBe "l1"

        vm.expand("l2")
        vm.state.value.expandedId shouldBe "l2"

        vm.expand("l2")
        vm.state.value.expandedId.shouldBeNull()
    }

    @Test
    fun a_rejected_rename_surfaces_a_short_message_and_leaves_the_list_alone() = runTest {
        val repo = FakeAnnotationLabelsRepository(
            listOf(label("l1", "Good shot", "green"), label("l2", "Forced error", "amber"))
        )
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.rename("l2", "good shot")
        advanceUntilIdle()

        vm.state.value.errorMessage shouldBe "You already have a label called \"good shot\"."
        vm.state.value.labels.first { it.id == "l2" }.name shouldBe "Forced error"
    }

    @Test
    fun delete_removes_the_label() = runTest {
        val repo = FakeAnnotationLabelsRepository(listOf(label("l1", "Good shot", "green")))
        val vm = LabelsViewModel(repo)
        advanceUntilIdle()

        vm.delete("l1")
        advanceUntilIdle()

        vm.state.value.labels.shouldHaveSize(0)
    }
}
```

`FakeAnnotationLabelsRepository` already lives in `androidApp/src/test/java/com/badmintontracker/android/testing/` from Task 8. Extend it there to enforce the same duplicate-name rule as the real repository, failing with the same `IllegalArgumentException` message, so this test is meaningful.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*LabelsViewModelTest*"`
Expected: FAIL, unresolved reference `LabelsViewModel`.

- [ ] **Step 3: Write the ViewModel**

```kotlin
package com.badmintontracker.android.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.repo.userFacingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LabelsUiState(
    val labels: List<AnnotationLabel> = emptyList(),
    val expandedId: String? = null,
    val errorMessage: String? = null,
)

class LabelsViewModel(private val labels: AnnotationLabelsRepository) : ViewModel() {

    private val _state = MutableStateFlow(LabelsUiState())
    val state: StateFlow<LabelsUiState> = _state.asStateFlow()

    val palette: List<LabelColor> = LabelColor.PALETTE

    init {
        viewModelScope.launch {
            labels.labels.collect { rows -> _state.value = _state.value.copy(labels = rows) }
        }
        viewModelScope.launch { labels.refresh() }
    }

    /** Passing the id already expanded collapses it, so a row is its own toggle. */
    fun expand(id: String?) {
        _state.value = _state.value.copy(expandedId = if (_state.value.expandedId == id) null else id)
    }

    fun create(name: String) = run { labels.create(name, null) }
    fun rename(id: String, name: String) = run { labels.rename(id, name) }
    fun recolor(id: String, color: LabelColor) = run { labels.recolor(id, color) }
    fun delete(id: String) = run { labels.delete(id) }

    fun errorShown() { _state.value = _state.value.copy(errorMessage = null) }

    /**
     * Every failure goes through userFacingMessage, the same filter the iOS interop
     * wrappers use, so a network stack trace or a multi-line Postgres error never
     * reaches the snackbar. Our own validation messages are single-line and pass
     * through unchanged.
     */
    private fun run(op: suspend () -> Result<*>) {
        viewModelScope.launch {
            op().onFailure { e ->
                _state.value = _state.value.copy(
                    errorMessage = e.userFacingMessage("Couldn't save the label"),
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*LabelsViewModelTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the screen**

`LabelsScreen.kt`: a `Scaffold` with a `TopAppBar` titled `"LABELS"` in `MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp)` to match `ClipListScreen`, a back `IconButton`, and a `LazyColumn` of rows. Each row is a `SwipeToRemoveRow` wrapping a `Column`:

- Collapsed: a `Row` with a 12.dp circle in the label's background colour, 12.dp spacing, the name in `bodyLarge`, and `Modifier.clickable { vm.expand(label.id) }.padding(horizontal = 24.dp, vertical = 14.dp)`.
- Expanded: adds a `ShuttlOutlinedTextField` bound to a local `remember(label.id) { mutableStateOf(label.name) }`, committing through `vm.rename` on focus loss or the Done action, and a `FlowRow` over `vm.palette` of ten 28.dp swatch circles calling `vm.recolor`, the selected one drawn with a 2.dp border in `MaterialTheme.colorScheme.onSurface`.

Below the list, a single `TextButton` reading `"New label"`, which expands into the same inline name field with no swatch row, calling `vm.create`.

`SwipeToRemoveRow`'s `onSwiped` returns false and opens a `ConfirmDialog` with title `"Delete label?"` and body `"\"$name\" leaves the picker. Notes already tagged with it keep their badge."`, confirming into `vm.delete`.

Surface `state.errorMessage` through the existing snackbar pattern and clear it with `vm.errorShown()`.

- [ ] **Step 6: Wire the route and the menu entry**

`Route.kt`: add `@Serializable data object Labels : Route`.

`AuthGate.kt`: add, next to the other destinations,

```kotlin
                composable<Route.Labels> {
                    val vm: LabelsViewModel = viewModel(
                        factory = viewModelFactory { initializer { LabelsViewModel(rally.labels) } }
                    )
                    LabelsScreen(vm = vm, onBack = { nav.popBackStack() })
                }
```

`ClipListScreen.kt`: add an `onLabels: () -> Unit` parameter, and a `DropdownMenuItem` reading `"Labels"` above `"Sign out"` in the overflow menu, calling `menuOpen = false; onLabels()`. Pass `onLabels = { nav.navigate(Route.Labels) }` from `AuthGate`.

- [ ] **Step 7: Build and check the screen by hand**

Run: `./gradlew :androidApp:testDebugUnitTest && ./gradlew :androidApp:assembleDebug`
Expected: both succeed.

Then install and walk the screen in both themes: the three seeded labels appear; renaming one updates the row; recolouring redraws the dot and the chip in the Add note sheet; swiping asks before deleting; a deleted label leaves the picker while an annotation already carrying it keeps its badge.

- [ ] **Step 8: Commit**

```bash
git add -A androidApp/src
git commit -m "feat(android): add the Labels screen"
```

---

### Task 10: Inline label creation from the Add note sheet

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationUi.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerViewModel.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/clipdetail/ClipDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `AnnotationLabelsRepository.create`.
- Produces: `ClipDetailViewModel.createLabel(name: String)` and the same on `LocalPlayerViewModel`; `AddAnnotationSheet(..., onCreateLabel: (String) -> Unit, canCreateLabel: Boolean)`.

The sheet gains exactly one text field, never the swatch grid. Colour is auto-assigned; recolouring is the Labels screen's job. The new label is selected the moment it arrives, so the user returns to the note they were writing.

- [ ] **Step 1: Write the failing test**

Append to `ClipDetailViewModelTest.kt`:

```kotlin
    @Test
    fun createLabel_adds_it_to_the_pickable_list() = runTest {
        val labels = FakeAnnotationLabelsRepository(emptyList())
        val vm = ClipDetailViewModel(
            clipId = "c1", clips = FakeClipsRepository(), annotations = FakeAnnotationsRepository(),
            media = FakeMediaRepository(), auth = FakeAuthRepository(), labels = labels,
        )

        vm.createLabel("Net kill")
        advanceUntilIdle()

        vm.state.value.labels.single().name shouldBe "Net kill"
    }

    @Test
    fun createLabel_surfaces_a_short_message_when_the_name_is_taken() = runTest {
        val labels = FakeAnnotationLabelsRepository(listOf(netKill))
        val vm = ClipDetailViewModel(
            clipId = "c1", clips = FakeClipsRepository(), annotations = FakeAnnotationsRepository(),
            media = FakeMediaRepository(), auth = FakeAuthRepository(), labels = labels,
        )
        advanceUntilIdle()

        vm.createLabel("net KILL")
        advanceUntilIdle()

        vm.state.value.errorMessage shouldBe "You already have a label called \"net KILL\"."
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*ClipDetailViewModelTest*"`
Expected: FAIL, `createLabel` unresolved.

- [ ] **Step 3: Add createLabel to both ViewModels**

```kotlin
    fun createLabel(name: String) {
        viewModelScope.launch {
            labels.create(name, null).onFailure { e ->
                _state.value = _state.value.copy(errorMessage = e.userFacingMessage("Couldn't add label"))
            }
        }
    }
```

On `ClipDetailViewModel` that writes into `state.value.copy(errorMessage = ...)` on `ClipDetailState`. On `LocalPlayerViewModel` it writes into the `_errorMessage` flow added in Task 8 Step 5, since that ViewModel has no state object.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "*ClipDetailViewModelTest*"`
Expected: PASS.

- [ ] **Step 5: Add the chip to the sheet**

In `AddAnnotationSheet`, add the parameters `onCreateLabel: (String) -> Unit` and `canCreateLabel: Boolean`, plus local state `var creating by remember { mutableStateOf(false) }` and `var newName by remember { mutableStateOf("") }`.

After the `labels.forEach { ... }` loop inside the `FlowRow`, when `canCreateLabel && !creating`, render an `AssistChip` labelled `"+ New label"` that sets `creating = true`.

When `creating`, render below the `FlowRow` a single `OutlinedTextField` bound to `newName` with placeholder `"Label name"`, `KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done)`, and `KeyboardActions(onDone = { onCreateLabel(newName); newName = ""; creating = false })`. No swatch row.

Select the new label as it arrives, so the user is returned to their note:

```kotlin
    val labelIds = labels.map { it.id }
    LaunchedEffect(labelIds) {
        labels.lastOrNull()?.takeIf { it.id !in seenIds }?.let { label = it }
        seenIds = labelIds.toSet()
    }
```

with `var seenIds by remember { mutableStateOf(labels.map { it.id }.toSet()) }` declared above it.

Pass `canCreateLabel` as the app's current connectivity, not as a property of which player is open: a local video lives on the device but the device may well be online, and reviewing a match just recorded is where a new label is most wanted. Where no connectivity signal exists yet, pass `true` and let the failure surface as the short message from Step 3.

- [ ] **Step 6: Build and check by hand**

Run: `./gradlew :androidApp:testDebugUnitTest && ./gradlew :androidApp:assembleDebug`
Expected: both succeed.

Then, on device: open Add note on a clip, tap `+ New label`, type a name, press Done. The chip appears selected, the sheet has not grown a swatch grid, and the label is on the Labels screen afterwards. Repeat inside a local video.

- [ ] **Step 7: Commit**

```bash
git add -A androidApp/src
git commit -m "feat(android): create a label inline from the Add note sheet"
```

---
### Task 11: iOS badge and picker driven by the label list

**Files:**
- Create: `iosApp/Sources/Components/LabelBadge.swift`
- Delete: `iosApp/Sources/Components/KindBadge.swift`
- Modify: `iosApp/Sources/ClipDetail/AddAnnotationSheet.swift`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailModel.swift`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailView.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerModel.swift`
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerView.swift`

**Interfaces:**
- Consumes: `AnnotationLabel`, `LabelColor`, `SwiftInteropKt.addAnnotationForSwift`, `SwiftInteropKt.createLabelForSwift` from Task 7.
- Produces: `struct LabelBadge(name: String, colorKey: String?)`; `AddAnnotationSheet(labels:canCreateLabel:onCreateLabel:onAdd:)` where `onAdd` is `(AnnotationLabel?, String) -> Void`.

`KindBadge` has a `default:` branch returning `""` and `bgTertiary`, so today an unrecognised kind renders as an empty pill while Android's `when` is compiler checked. `LabelBadge` takes a name and a colour key, so there is no unmatched case left to render silently: an unknown key falls back to the neutral chip and the name still shows.

- [ ] **Step 1: Write LabelBadge and delete KindBadge**

```swift
import SwiftUI
import Shared

/// A label's pill. An unknown `colorKey` falls back to the neutral chip rather
/// than rendering nothing: the name is the information, the colour is decoration.
struct LabelBadge: View {
    let name: String
    let colorKey: String?

    private var swatch: LabelColor? { LabelColor.companion.from(key: colorKey) }
    private var container: Color {
        swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary
    }
    private var onContainer: Color {
        swatch.map { Color(rgb: UInt32($0.foreground & 0xFFFFFF)) } ?? Shuttl.text
    }

    var body: some View {
        Text(name)
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(onContainer)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(container))
    }
}
```

```bash
git rm iosApp/Sources/Components/KindBadge.swift
```

- [ ] **Step 2: Drive the sheet from the list**

Replace the fixed `kinds` array in `AddAnnotationSheet.swift` with the passed-in list, and add the inline create field. Exactly one text field is added; the swatch grid never appears in a sheet.

```swift
struct AddAnnotationSheet: View {
    let labels: [AnnotationLabel]
    let canCreateLabel: Bool
    let onCreateLabel: (String) -> Void
    let onAdd: (AnnotationLabel?, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selected: AnnotationLabel? = nil
    @State private var body_ = ""
    @State private var creating = false
    @State private var newName = ""
```

```swift
            HStack(spacing: 8) {
                ForEach(labels, id: \.id) { label in
                    chip(label)
                }
                if canCreateLabel && !creating {
                    Button("+ New label") { creating = true }
                        .font(.footnote)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Shuttl.bgTertiary))
                        .foregroundStyle(Shuttl.text)
                }
            }
            if creating {
                TextField("Label name", text: $newName)
                    .submitLabel(.done)
                    .onSubmit {
                        onCreateLabel(newName)
                        newName = ""
                        creating = false
                    }
                    .padding(12)
                    .background(Shuttl.bgInput)
            }
```

`chip(_:)` replaces `chip(_ k: AnnotationKind)`, comparing `selected?.id == label.id` and toggling to nil on a second tap, keeping the existing selected/unselected styling.

Select a newly created label as it arrives so the user returns to their note:

```swift
        .onChange(of: labels.map(\.id)) { _, ids in
            if let last = labels.last, !seenIds.contains(last.id) { selected = last }
            seenIds = Set(ids)
        }
```

with `@State private var seenIds: Set<String> = []`, initialised in `.task { seenIds = Set(labels.map(\.id)) }`.

- [ ] **Step 3: Update both models and views**

`ClipDetailModel.swift`: change `add(kind:body:)` to `add(label: AnnotationLabel?, body: String)`, guarding `if trimmed.isEmpty && label == nil { return }` and passing `label: label` to `SwiftInteropKt.addAnnotationForSwift`. Add `@Published var labels: [AnnotationLabel] = []`, populated from `rally.labels.labels` and refreshed on appear, plus `func createLabel(_ name: String) async` calling `SwiftInteropKt.createLabelForSwift` and surfacing `errorMessage` on failure.

`LocalPlayerModel.swift`: the same `labels`, `createLabel` and `add(label:body:)` changes against `rally.localAnnotations.add`.

`ClipDetailView.swift`: pass `labels: model.labels`, `canCreateLabel:`, `onCreateLabel:` into `AddAnnotationSheet`, and replace `KindBadge(kind: kind)` with:

```swift
            if let name = annotation.labelName {
                LabelBadge(name: name, colorKey: annotation.labelColor)
            }
```

`LocalPlayerView.swift`: the same two changes.

- [ ] **Step 4: Regenerate the project and build**

Run:

```bash
cd iosApp && xcodegen generate && cd ..
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

Expected: all three succeed. Do not hand-edit `project.pbxproj`; it is xcodegen output.

Then `grep -rn "AnnotationKind" iosApp/Sources` must return nothing.

- [ ] **Step 5: Commit**

```bash
git add -A iosApp
git commit -m "feat(ios): render annotation badges from the user's label list"
```

---

### Task 12: The iOS Labels screen

**Files:**
- Create: `iosApp/Sources/Labels/LabelsLogic.swift`
- Create: `iosApp/Sources/Labels/LabelsModel.swift`
- Create: `iosApp/Sources/Labels/LabelsView.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Test: `iosApp/Tests/LabelsLogicTests.swift`

**Interfaces:**
- Consumes: `SwiftInteropKt.renameLabelOrMessage`, `recolorLabelOrMessage`, `deleteLabelOrMessage`, `createLabelForSwift`, `refreshLabelsOrMessage` from Task 7.
- Produces: `enum LabelsLogic { static func nextExpanded(current: String?, tapped: String) -> String? }`; `@MainActor final class LabelsModel: ObservableObject` with `labels`, `expandedId`, `errorMessage`, `expand(_:)`, `create(_:)`, `rename(_:to:)`, `recolor(_:to:)`, `delete(_:)`.

Every suite under `iosApp/Tests` tests pure functions against types like `MatchGrouping` and `LocalVideoLogic`; none instantiates something holding a `RallyApp`. So the row-toggle rule is extracted into `LabelsLogic` and tested there, matching that convention rather than inventing a test seam into an `ObservableObject`. The rest of `LabelsModel` is covered by the by-hand walkthrough in Step 6 and by the Android `LabelsViewModelTest`, which exercises the same logic.

A pushed `NavigationLink` destination, not a sheet. Rows expand in place; delete is `.swipeActions(edge: .trailing)` with a confirmation, matching `ClipListView`.

- [ ] **Step 1: Write the failing test**

```swift
import XCTest
@testable import iosApp

final class LabelsLogicTests: XCTestCase {

    func testTappingANewRowExpandsIt() {
        XCTAssertEqual(LabelsLogic.nextExpanded(current: nil, tapped: "l1"), "l1")
        XCTAssertEqual(LabelsLogic.nextExpanded(current: "l1", tapped: "l2"), "l2")
    }

    func testTappingTheExpandedRowCollapsesIt() {
        XCTAssertNil(LabelsLogic.nextExpanded(current: "l1", tapped: "l1"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run:

```bash
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:iosAppTests/LabelsLogicTests
```

Expected: FAIL to compile, `LabelsLogic` not found.

- [ ] **Step 3: Write the logic, the model and the view**

`LabelsLogic.swift`:

```swift
/// Row expansion is its own toggle: tapping the open row closes it.
enum LabelsLogic {
    static func nextExpanded(current: String?, tapped: String) -> String? {
        current == tapped ? nil : tapped
    }
}
```

`LabelsModel.swift`: `@Published var labels: [AnnotationLabel]`, `@Published var expandedId: String?`, `@Published var errorMessage: String?`. `expand(_ id: String)` sets `expandedId = LabelsLogic.nextExpanded(current: expandedId, tapped: id)`. Each mutation calls its interop wrapper and assigns the returned message to `errorMessage` when non-nil, then reloads `labels` from `rally.labels.labels`.

`LabelsView.swift`: a `List` of rows in a `NavigationStack` destination, titled `"Labels"`.

- Collapsed row: an `HStack` with a 12pt `Circle` filled from `LabelColor.companion.from(key:)`, 12pt spacing, the name, and `.contentShape(Rectangle()).onTapGesture { model.expand(label.id) }`.
- Expanded row: adds a `TextField` seeded from the name, committing through `model.rename(_:to:)` on submit, and a `LazyVGrid` over `LabelColor.companion.PALETTE` of ten 28pt swatch circles calling `model.recolor(_:to:)`, the selected one carrying a 2pt `Shuttl.text` stroke.
- `.swipeActions(edge: .trailing, allowsFullSwipe: true)` with a destructive `Button("Delete")` that sets a `@State private var pendingDelete: AnnotationLabel?`, driving a `.confirmationDialog` titled `"Delete label?"` with the message `"\"\(name)\" leaves the picker. Notes already tagged with it keep their badge."`.
- A trailing `Section` with a `Button("New label")` that reveals the same inline name field, with no swatch row, calling `model.create(_:)`.

- [ ] **Step 4: Run the test to verify it passes**

Run the same `xcodebuild test` command from Step 2, after `cd iosApp && xcodegen generate && cd ..` so the new files are in the project.
Expected: PASS, 2 tests.

- [ ] **Step 5: Wire it into the menu**

In `ClipListView.swift`, inside the existing overflow `Menu` at line 58, add above `Button("Sign out")`:

```swift
                        NavigationLink("Labels") { LabelsView(rally: rally) }
```

If the enclosing `Menu` will not host a `NavigationLink`, use a `Button` that sets a `@State private var showLabels = false` and add `.navigationDestination(isPresented: $showLabels) { LabelsView(rally: rally) }` beside the existing `navigationDestination` modifiers.

- [ ] **Step 6: Regenerate, build and check by hand**

Run:

```bash
cd iosApp && xcodegen generate && cd ..
xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

Expected: both succeed.

Then, in the simulator, in both light and dark: the three seeded labels appear with the same colours they have on Android; rename, recolour and delete behave as on Android; the swipe confirms before deleting; a label created inline from Add note appears here.

- [ ] **Step 7: Commit**

```bash
git add -A iosApp
git commit -m "feat(ios): add the Labels screen"
```

---

### Task 13: Changelog

**Files:**
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

Add to the existing `## [Unreleased]` / `### Added` block. Do not bump `Config/Version.xcconfig`: this branch is not a release commit, and release commits are tagged `v<MARKETING_VERSION>` with CI verifying the tag matches, so a bump here would be wrong. Read the current `## [Unreleased]` block before editing rather than assuming its contents. Do not use an em dash in the new entry, even though older entries contain them.

- [ ] **Step 1: Add the entry**

Under `## [Unreleased]` / `### Added`, prepend:

```markdown
- Custom annotation labels. The three shot-quality badges are now ordinary
  labels you own: rename them, change their colour, delete them, or add your
  own. A new Labels screen in the overflow menu manages the set, and a
  `+ New label` chip in the Add note sheet creates one without leaving the
  clip you are watching. Notes keep the label name and colour they were given,
  so deleting a label never blanks a note you already tagged and a shared
  match still shows its badges to the person you shared it with.
```

- [ ] **Step 2: Verify the whole suite**

Run:

```bash
./gradlew :shared:jvmTest && \
./gradlew :androidApp:testDebugUnitTest && \
./gradlew :androidApp:assembleDebug && \
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 && \
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

Expected: all pass. Then confirm the enum is gone everywhere:

```bash
grep -rn "AnnotationKind" shared/src androidApp/src iosApp/Sources
```

Expected: no matches.

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for custom annotation labels"
```
