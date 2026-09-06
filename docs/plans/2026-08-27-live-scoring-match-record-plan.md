# Live Scoring: The Match Record (L1a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a match a thing that exists before any video does. Create one on the phone, name it, name its players, pick its rules, and see it in the match list beside the video-backed matches - stored locally first so a sports hall with no signal is a normal case, and synced to a `score_logs` table this app owns outright.

**Architecture:** One new table with a nullable `video_id`, so nothing about how `videos` or `rally_clips` are written changes and there is nothing to coordinate with the Modal pipeline. A local-first repository in the same Settings-backed registry pattern as `LocalVideoRepository`, with the owner-scoped cache envelope `AnnotationLabelsRepository` already uses. The match list keeps its existing grouping code untouched: a score-only match is a second row kind merged into the owned section by one small pure function per platform, not a change to `toMatches`.

**Tech Stack:** Kotlin Multiplatform (kotlinx-serialization, kotlinx-datetime, multiplatform-settings, supabase-kt 3.5.0 postgrest), Jetpack Compose / Material 3 on Android, SwiftUI on iOS. Tests: kotlin.test plus kotest-assertions on the Kotlin side, XCTest on iOS.

**Spec:** `docs/plans/2026-08-26-live-scoring-review-design.md`, sections 7 (L1) and 7.1.

**Depends on:** `docs/plans/2026-08-26-live-scoring-engine-plan.md` (L0). Every task here reads `MatchState`, `ScoringRules`, `MatchSetup` or `ScoreEvent`, so L0 must be complete and green first.

## Scope

**In:** the `score_logs` migration; the `ScoreLog` model; the local-first repository and its sync; creating a match; a score-only match appearing in the match list, sorted in with the video-backed ones; opening it; deleting it; the disabled share affordance.

**Out, and deliberately:**

- **The scoring surface.** Tapping a side to score, the courtside tag palette, landscape, keep-awake: that is L1b, the next plan. It is separated because its Swift reads `MatchState` through SKIE-generated names that L0's Task 6 explicitly reserves the right to change, and because the list work here is the piece the design doc flags as the largest and the easiest to under-count. A match created here therefore has no points yet, and the match page's "No points scored yet" is a real state that survives into L1b - a match created and not yet started.
- **Binding a video onto a match** and the reconcile screen (L2).
- **Live sync to a second device** (L3) and the public spectator link (L4).

## Deviations from the spec

1. **No match date field.** §7's L1 lists "Name, singles or doubles, player or pair names, scoring rules, date" for match creation. The date is dropped: under §2's flow the coach creates the match at the moment he is about to score it, so `created_at` *is* the date, and a backdating picker buys a nullable date column plus a Kotlin `LocalDate` that reaches Swift as `Kotlinx_datetimeLocalDate`. If backdating turns out to matter it is one column and one picker, added without touching anything here.
2. **`MatchSetup` becomes `@Serializable`.** L0 left it a plain data class because the fold does not need it serialized. Persisting a match does. Task 2 makes that change in the L0 file and pins the stored shape, rather than introducing a parallel storage type that could disagree with the one the fold reads.

## Global Constraints

- No em dash in any prose, comment, commit message or user-facing string. Use a plain dash.
- No agent attribution in commit messages, and no "Generated with" footer.
- **Do not modify `List<RallyClip>.toMatches` (Android) or `MatchGrouping.matches` (iOS).** Their existing tests must pass untouched at the end of this plan. A score-only match is a second row kind merged in beside their output, never a nullable field threaded through it.
- **`score_logs.video_id` is `on delete set null`, never cascade.** §7.1: the score log must survive its video's deletion as `unbound`, keeping its tags, precisely because `delete_match` cascades `rally_annotations` away. A cascade here is the bug the design doc names.
- **The cache is owner-scoped.** Settings has no per-user namespacing and nothing guarantees a sign-out hook runs before the next launch. The owner id travels inside the stored payload and a payload stamped with someone else's id is discarded, exactly as `AnnotationLabelsRepository.CachedLabels` does.
- User-facing copy calls a score-only match a **match**, never a "score log". `score_logs` is a table name and stays out of the UI.
- Do not hand-edit `iosApp/iosApp.xcodeproj/project.pbxproj`; it is xcodegen output. Run `cd iosApp && xcodegen generate && cd ..` after adding a Swift file.
- Test commands:

```bash
./gradlew :shared:jvmTest
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug
```

and for iOS:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

## File Structure

**Database**
- Create `supabase/migrations/20260827000000_score_logs.sql`.

**Shared (KMP)**
- Modify `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt` - `@Serializable` on `MatchSetup` and `PairPlayer`.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLog.kt` - `ScoreLog`, `ScoreLogStatus`, `createMatchProblem`.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCard.kt` - `ScoreMatchCard`, `buildScoreMatchCard`, `scoreLine`, `sideLabel`. Every string both platforms show for a score-only match is built here, so they cannot render the same match two ways.
- Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt` - the local-first store and its sync.
- Modify `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt` - expose `scoreLogs`.
- Modify `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt` - `syncScoreLogsOrMessage`, `deleteScoreMatchOrMessage`.
- Create `shared/src/commonTest/.../scoring/ScoreLogTest.kt`, `ScoreMatchCardTest.kt`, `ScoreLogsRepositoryTest.kt`.

**Android**
- Create `androidApp/src/main/java/com/badmintontracker/android/scoring/NewMatchViewModel.kt` and `NewMatchScreen.kt`.
- Create `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchViewModel.kt` and `ScoreMatchScreen.kt`.
- Create `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchRow.kt` - the row union and `mergeMatchRows`. Its own file so `ClipListViewModel.kt` keeps one responsibility.
- Modify `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`, `ClipListScreen.kt`, `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`, `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt`.
- Create `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt`, `androidApp/src/test/java/com/badmintontracker/android/scoring/NewMatchViewModelTest.kt`.

**iOS**
- Create `iosApp/Sources/Scoring/NewMatchView.swift`, `iosApp/Sources/Scoring/ScoreMatchView.swift`, `iosApp/Sources/Scoring/ScoreMatchModel.swift`.
- Modify `iosApp/Sources/ClipList/MatchGrouping.swift` - `MatchRow` and `mergeMatchRows`, ported from Android.
- Modify `iosApp/Sources/ClipList/ClipListModel.swift`, `iosApp/Sources/ClipList/ClipListView.swift`.
- Modify `iosApp/Tests/MatchGroupingTests.swift`.

**Docs**
- Modify `CHANGELOG.md`.

---

### Task 1: The score_logs table

**Files:**
- Create: `supabase/migrations/20260827000000_score_logs.sql`

**Interfaces:**
- Consumes: `public.videos`, `auth.users`.
- Produces: `public.score_logs` with owner-only RLS.

§7.1 rejected a `matches` table that `videos` points at, because `videos` rows are written by the Modal pipeline and by the web app's upload path, both in the `badminton-tracker` repo, and `videos.title` is insert-only by deliberate design. This table is the chosen alternative: one table this app owns outright, one nullable column, nothing to coordinate.

- [ ] **Step 1: Write the migration**

Create `supabase/migrations/20260827000000_score_logs.sql`:

```sql
-- Live scoring: a match that exists before any video does.
-- See docs/plans/2026-08-26-live-scoring-review-design.md

create table if not exists public.score_logs (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null default auth.uid()
               references auth.users(id) on delete cascade,

    -- Nullable, and ON DELETE SET NULL rather than CASCADE. Both are load bearing.
    -- A match is created before any video exists, and it has to survive that video
    -- being deleted: delete_match cascades rally_annotations away, so once L2
    -- writes the coach's courtside tags onto clips, this row is the only place
    -- they still exist. Cascading here would silently destroy work he did on a
    -- bench before the video was ever recorded.
    video_id   uuid references public.videos(id) on delete set null,

    title      text not null check (length(trim(title)) between 1 and 80),

    -- The competitors. One name for singles, two for doubles; index 1 is the
    -- player who starts the match in the right service court.
    home_players text[] not null check (
                   cardinality(home_players) between 1 and 2
                   and array_position(home_players, null) is null
                 ),
    away_players text[] not null check (
                   cardinality(away_players) between 1 and 2
                   and array_position(away_players, null) is null
                 ),

    -- ScoringRules, MatchSetup and List<ScoreEvent> as the shared module
    -- serializes them. jsonb rather than text so a later spectator page can read a
    -- score in SQL without shipping the fold to the database.
    rules      jsonb not null,
    setup      jsonb not null,
    events     jsonb not null default '[]'::jsonb,

    -- All four states of §7.1 are enumerated now so the vocabulary is fixed in one
    -- place. Only 'live' and 'unbound' are reachable until L2 attaches a video:
    -- nothing in this release can set 'bound' or 'reconciled', and that is
    -- deliberate rather than an oversight.
    status     text not null default 'live'
               check (status in ('live', 'unbound', 'bound', 'reconciled')),

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- The match list sorts on created_at descending and reads only the owner's rows.
create index if not exists score_logs_owner_created_idx
    on public.score_logs (owner_id, created_at desc);

-- L2 looks a match up from its video. Partial, because most rows have no video.
create index if not exists score_logs_video_idx
    on public.score_logs (video_id) where video_id is not null;

alter table public.score_logs enable row level security;

-- Owner only, all four verbs. Sharing a score-only match is not possible:
-- match_shares is keyed (video_id, shared_with_user_id), so it becomes available
-- when a video is attached and not before. See §7.1.
drop policy if exists score_logs_select_own on public.score_logs;
create policy score_logs_select_own on public.score_logs
    for select to authenticated using (owner_id = auth.uid());

drop policy if exists score_logs_insert_own on public.score_logs;
create policy score_logs_insert_own on public.score_logs
    for insert to authenticated with check (owner_id = auth.uid());

drop policy if exists score_logs_update_own on public.score_logs;
create policy score_logs_update_own on public.score_logs
    for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists score_logs_delete_own on public.score_logs;
create policy score_logs_delete_own on public.score_logs
    for delete to authenticated using (owner_id = auth.uid());

-- Detaching a video must leave the match 'unbound', not stranded as 'bound' with
-- no video. The foreign key above nulls the column on delete, which is what keeps
-- the coach's courtside tags alive (see the comment on video_id), but a status
-- left saying 'bound' would then describe a binding that no longer exists - and
-- L2 reads that status to decide whether a match can be re-bound to a new upload.
-- Normalising here rather than in the client because the client is not the one
-- doing the update: the cascade is.
create or replace function public.unbind_score_log_on_video_delete()
returns trigger
language plpgsql
as $$
begin
    if new.video_id is null and old.video_id is not null
       and new.status in ('bound', 'reconciled') then
        new.status = 'unbound';
    end if;
    return new;
end;
$$;

drop trigger if exists score_logs_unbind_on_video_delete on public.score_logs;
create trigger score_logs_unbind_on_video_delete
    before update on public.score_logs
    for each row execute function public.unbind_score_log_on_video_delete();

-- updated_at is a trigger rather than a client write. The client upserts whole
-- rows from a local-first cache, and a client that forgets the field would leave
-- a stale timestamp that a later sync rule might trust.
create or replace function public.touch_score_logs_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists score_logs_touch_updated_at on public.score_logs;
create trigger score_logs_touch_updated_at
    before update on public.score_logs
    for each row execute function public.touch_score_logs_updated_at();
```

**Executed 2026-08-27 against a local stack** (`supabase start`, this repo's 7 migrations plus the web app's 9 from `../badminton-tracker`). Check 2 found a real gap in the first draft: the foreign key nulls `video_id`, but nothing moved `status`, so a match whose video was deleted sat as `'bound'` with no video - and L2 reads that status to decide whether a match can be re-bound. The `unbind_score_log_on_video_delete` trigger above is the fix, and it is in the migration text now. All five checks below pass.

- [ ] **Step 2: Apply it and verify the constraints actually bite**

Apply the migration to the Supabase project you are developing against, then run these as an authenticated user. Each is checking a rule this plan depends on, not a rule Postgres would enforce anyway:

```sql
-- 1. A match with no video is legal.
insert into public.score_logs (title, home_players, away_players, rules, setup)
values ('Probe', array['Marco'], array['Ana'], '{}'::jsonb, '{}'::jsonb)
returning id, video_id, status;          -- expect: video_id null, status 'live'

-- 2. Deleting the video must NOT delete the match. Bind the probe to any video you
--    own, delete that video, and confirm the row survives with a null video_id.
--    This is the single most important assertion in the migration.

-- 3. An unknown status is rejected.
update public.score_logs set status = 'archived' where title = 'Probe';
-- expect: new row for relation "score_logs" violates check constraint

-- 4. updated_at moves on its own.
update public.score_logs set title = 'Probe 2' where title = 'Probe'
returning created_at, updated_at;        -- expect: updated_at > created_at

delete from public.score_logs where title = 'Probe 2';
```

Record the result of check 2 in the commit message. If it fails, the foreign key is wrong and nothing later in this plan is safe.

- [ ] **Step 3: Commit**

```bash
git add supabase/migrations/20260827000000_score_logs.sql
git commit -m "feat(db): add score_logs, a match that can exist without a video"
```

---

### Task 2: The stored match

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLog.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogTest.kt`

**Interfaces:**
- Consumes: `ScoringRules`, `MatchSetup`, `ScoreEvent`, `Side`, `PairPlayer` (L0).
- Produces:
  - `@Serializable` on `MatchSetup` and on `PairPlayer` (with `@SerialName("first")` / `@SerialName("second")`)
  - `enum class ScoreLogStatus { LIVE, UNBOUND, BOUND, RECONCILED }`, `@Serializable`, serial names `live` / `unbound` / `bound` / `reconciled`
  - `data class ScoreLog(id, videoId, title, homePlayers, awayPlayers, rules, setup, events, status, createdAt, updatedAt)`, `@Serializable`, with `fun state(): MatchState`
  - `fun createMatchProblem(title: String, homePlayers: List<String>, awayPlayers: List<String>, doubles: Boolean): String?`
  - `const val MAX_MATCH_TITLE = 80`, `const val MAX_PLAYER_NAME = 40`

`ScoreLog.state()` folds on demand rather than caching a `MatchState`, so a stored match and a live one cannot disagree: there is still exactly one score, derived from the log.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ScoreLogTest {

    // encodeDefaults matches the Json that ScoreLogsRepository writes its cache
    // with, and it has to: this test pins the stored shape, and a config that
    // differs from the writer's would pin a shape nothing ever produces. It is
    // load bearing below, where awayStartsRight is left at its default and would
    // otherwise simply be absent from the encoded string.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun log(
        events: List<ScoreEvent> = emptyList(),
        status: ScoreLogStatus = ScoreLogStatus.LIVE,
        doubles: Boolean = false,
    ) = ScoreLog(
        id = "log-1",
        videoId = null,
        title = "Thu League vs Marco",
        homePlayers = listOf("Coen"),
        awayPlayers = listOf("Marco"),
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = doubles, firstServer = Side.HOME),
        events = events,
        status = status,
        createdAt = Instant.parse("2026-08-27T18:00:00Z"),
        updatedAt = Instant.parse("2026-08-27T18:00:00Z"),
    )

    @Test
    fun a_log_folds_to_its_own_score() {
        // The state is derived on demand, never stored, so a match read back from
        // the database cannot disagree with the same match still being scored.
        val state = log(listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.PointTo(Side.HOME))).state()
        state.currentGame shouldBe SideScore(home = 2, away = 0)
        state.rules shouldBe ScoringRules.BWF_21
    }

    @Test
    fun a_match_setup_survives_a_round_trip() {
        // Pinned because it is a stored column. The doubles arrangement in
        // particular must not decode as FIRST when SECOND was written, or a coach
        // reopening a match is told the wrong player is serving.
        val setup = MatchSetup(
            doubles = true,
            firstServer = Side.AWAY,
            homeStartsRight = PairPlayer.SECOND,
            awayStartsRight = PairPlayer.FIRST,
        )
        val encoded = json.encodeToString(MatchSetup.serializer(), setup)
        encoded shouldBe
            """{"doubles":true,"first_server":"away","home_starts_right":"second","away_starts_right":"first"}"""
        json.decodeFromString(MatchSetup.serializer(), encoded) shouldBe setup
    }

    @Test
    fun a_status_is_stored_as_the_database_spells_it() {
        json.encodeToString(ScoreLogStatus.serializer(), ScoreLogStatus.UNBOUND) shouldBe "\"unbound\""
        json.decodeFromString(ScoreLogStatus.serializer(), "\"reconciled\"") shouldBe ScoreLogStatus.RECONCILED
    }

    @Test
    fun a_whole_log_survives_a_round_trip() {
        val original = log(
            events = listOf(
                ScoreEvent.PointTo(Side.HOME),
                ScoreEvent.TagPoint(0, listOf(PointTag("Forced error", "amber")), "net"),
            ),
            status = ScoreLogStatus.UNBOUND,
            doubles = true,
        )
        json.decodeFromString(ScoreLog.serializer(), json.encodeToString(ScoreLog.serializer(), original)) shouldBe
            original
    }

    @Test
    fun a_row_from_the_database_decodes_with_no_video() {
        val row = """
            {"id":"log-1","video_id":null,"title":"Thu League",
             "home_players":["Coen"],"away_players":["Marco"],
             "rules":{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11},
             "setup":{"doubles":false,"first_server":"home","home_starts_right":"first","away_starts_right":"first"},
             "events":[{"type":"point","side":"home"}],
             "status":"live",
             "created_at":"2026-08-27T18:00:00Z","updated_at":"2026-08-27T18:00:00Z"}
        """.trimIndent()
        val decoded = json.decodeFromString(ScoreLog.serializer(), row)
        decoded.videoId shouldBe null
        decoded.state().currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_playable_new_match_reports_no_problem() {
        createMatchProblem("Thu League", listOf("Coen"), listOf("Marco"), doubles = false) shouldBe null
        createMatchProblem("Club night", listOf("A", "B"), listOf("C", "D"), doubles = true) shouldBe null
    }

    @Test
    fun a_new_match_missing_something_reports_a_sentence_a_user_can_read() {
        createMatchProblem("  ", listOf("Coen"), listOf("Marco"), doubles = false) shouldBe
            "Give the match a name."
        createMatchProblem("x".repeat(81), listOf("Coen"), listOf("Marco"), doubles = false) shouldBe
            "The match name can be up to 80 characters."
        createMatchProblem("Thu", listOf(" "), listOf("Marco"), doubles = false) shouldBe
            "Name both players."
        createMatchProblem("Thu", listOf("A"), listOf("C", "D"), doubles = true) shouldBe
            "Doubles needs two players on each side."
        createMatchProblem("Thu", listOf("A", "B"), listOf("C"), doubles = true) shouldBe
            "Doubles needs two players on each side."
        createMatchProblem("Thu", listOf("A", "B"), listOf("C"), doubles = false) shouldBe
            "Singles has one player on each side."
        createMatchProblem("Thu", listOf("x".repeat(41)), listOf("Marco"), doubles = false) shouldBe
            "A player name can be up to 40 characters."
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogTest"`
Expected: FAIL to compile, "Unresolved reference: ScoreLog".

- [ ] **Step 3: Make the L0 setup types storable**

In `MatchState.kt`, add the serialization annotations. `PairPlayer` first:

```kotlin
@Serializable
enum class PairPlayer {
    @SerialName("first")  FIRST,
    @SerialName("second") SECOND;

    val other: PairPlayer get() = if (this == FIRST) SECOND else FIRST
}
```

then `MatchSetup`, keeping its existing documentation:

```kotlin
@Serializable
data class MatchSetup(
    val doubles: Boolean,
    @SerialName("first_server")       val firstServer: Side,
    @SerialName("home_starts_right")  val homeStartsRight: PairPlayer = PairPlayer.FIRST,
    @SerialName("away_starts_right")  val awayStartsRight: PairPlayer = PairPlayer.FIRST,
)
```

and add the two imports at the top of the file:

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
```

Note that `homeStartsRight` and `awayStartsRight` keep their defaults, so a setup persisted with the default arrangement omits them - and the test above pins a non-default arrangement precisely because that is the case where the stored bytes matter.

- [ ] **Step 4: Write the stored match**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLog.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a match sits between being scored and being mapped onto a video. */
@Serializable
enum class ScoreLogStatus {
    /** Being scored right now. */
    @SerialName("live") LIVE,

    /** Finished, no video attached. A perfectly good place for a match to end. */
    @SerialName("unbound") UNBOUND,

    /** Mapped onto a video's rally clips. Not reachable until L2. */
    @SerialName("bound") BOUND,

    /** A human has confirmed or corrected that mapping. Not reachable until L2. */
    @SerialName("reconciled") RECONCILED,
}

/**
 * One match: who played, under what rules, and every point as it was scored.
 *
 * The score is not stored. [state] folds it on demand, so a match read back out of
 * the database and the same match still being scored cannot disagree about what
 * the score is - there is still exactly one, and it is derived.
 */
@Serializable
data class ScoreLog(
    val id: String,
    /** Null until L2 attaches a video, and null again if that video is deleted. */
    @SerialName("video_id")     val videoId: String?,
    val title: String,
    /** One name for singles, two for doubles. Index 0 starts in the right service court. */
    @SerialName("home_players") val homePlayers: List<String>,
    @SerialName("away_players") val awayPlayers: List<String>,
    val rules: ScoringRules,
    val setup: MatchSetup,
    val events: List<ScoreEvent>,
    val status: ScoreLogStatus,
    @SerialName("created_at")   val createdAt: Instant,
    @SerialName("updated_at")   val updatedAt: Instant,
) {
    fun state(): MatchState = foldMatchState(rules, setup, events)
}

const val MAX_MATCH_TITLE = 80
const val MAX_PLAYER_NAME = 40

/**
 * The one place a new match's own rules are spelled out, so the Android form, the
 * iOS form and the database CHECK cannot drift into three different opinions about
 * what is complete. Returns a ready to display sentence, or null when the match can
 * be created.
 */
fun createMatchProblem(
    title: String,
    homePlayers: List<String>,
    awayPlayers: List<String>,
    doubles: Boolean,
): String? {
    val named = { side: List<String> -> side.map { it.trim() }.filter { it.isNotEmpty() } }
    val home = named(homePlayers)
    val away = named(awayPlayers)
    val expected = if (doubles) 2 else 1
    return when {
        title.trim().isEmpty() -> "Give the match a name."
        title.trim().length > MAX_MATCH_TITLE -> "The match name can be up to $MAX_MATCH_TITLE characters."
        (home + away).any { it.length > MAX_PLAYER_NAME } ->
            "A player name can be up to $MAX_PLAYER_NAME characters."
        home.isEmpty() || away.isEmpty() -> "Name both players."
        doubles && (home.size != expected || away.size != expected) ->
            "Doubles needs two players on each side."
        !doubles && (home.size != expected || away.size != expected) ->
            "Singles has one player on each side."
        else -> null
    }
}
```

- [ ] **Step 5: Run the shared suite to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.*"`
Expected: PASS, 7 new tests plus every L0 test still green. The L0 tests construct `MatchSetup` positionally and by `copy`, neither of which the annotations change.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLog.kt \
        shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/MatchState.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogTest.kt
git commit -m "feat(scoring): add the stored match and its creation rules"
```

---

### Task 3: Every string a score-only match shows

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCard.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCardTest.kt`

**Interfaces:**
- Consumes: `ScoreLog`, `MatchState`, `ScoreLogStatus`.
- Produces:
  - `data class ScoreMatchCard(scoreLogId: String, title: String, createdAtEpochMs: Long, playersLine: String, scoreLine: String, statusLine: String, isLive: Boolean, hasVideo: Boolean)`
  - `fun buildScoreMatchCard(log: ScoreLog): ScoreMatchCard`
  - `fun sideLabel(players: List<String>): String`
  - `fun scoreLine(state: MatchState): String`

Same reason `buildMatchLabelSummary` exists: both platforms render this row, and a rounding or a separator decided per platform is a rounding decided twice. `createdAtEpochMs` is a `Long` rather than an `Instant` because iOS sorts on `latestCreatedAtMillis` already and a `Kotlinx_datetimeInstant` in a sort comparator is the kind of friction that produces a per-platform helper.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCardTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class ScoreMatchCardTest {

    private fun log(
        events: List<ScoreEvent> = emptyList(),
        status: ScoreLogStatus = ScoreLogStatus.LIVE,
        home: List<String> = listOf("Coen"),
        away: List<String> = listOf("Marco"),
        videoId: String? = null,
    ) = ScoreLog(
        id = "log-1",
        videoId = videoId,
        title = "Thu League",
        homePlayers = home,
        awayPlayers = away,
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = home.size == 2, firstServer = Side.HOME),
        events = events,
        status = status,
        createdAt = Instant.parse("2026-08-27T18:00:00Z"),
        updatedAt = Instant.parse("2026-08-27T18:00:00Z"),
    )

    private fun toScore(home: Int, away: Int): List<ScoreEvent> {
        val events = ArrayList<ScoreEvent>(home + away)
        var h = 0
        var a = 0
        while (h < home || a < away) {
            if (h < home && (h <= a || a >= away)) {
                events += ScoreEvent.PointTo(Side.HOME); h += 1
            } else {
                events += ScoreEvent.PointTo(Side.AWAY); a += 1
            }
        }
        return events
    }

    @Test
    fun a_singles_side_reads_as_one_name() {
        sideLabel(listOf("Coen")) shouldBe "Coen"
    }

    @Test
    fun a_doubles_pair_reads_as_two() {
        sideLabel(listOf("Coen", "Ana")) shouldBe "Coen / Ana"
    }

    @Test
    fun an_unscored_match_says_so_rather_than_showing_nil_nil() {
        // "0-0" on a match nobody has started reads as a bug. This is the state a
        // match sits in between being created and the first rally.
        scoreLine(log().state()) shouldBe "Not started"
    }

    @Test
    fun a_match_in_progress_shows_the_game_being_played() {
        scoreLine(log(toScore(11, 9)).state()) shouldBe "11-9"
    }

    @Test
    fun a_match_in_progress_shows_the_games_already_won_first() {
        scoreLine(log(toScore(21, 18) + toScore(5, 3)).state()) shouldBe "21-18, 5-3"
    }

    @Test
    fun a_finished_match_shows_every_game_and_no_trailing_repeat() {
        // The fold leaves currentGame holding the final game's score, so appending
        // it as a game in progress would print the last game twice.
        scoreLine(log(toScore(21, 18) + toScore(21, 15)).state()) shouldBe "21-18, 21-15"
    }

    @Test
    fun a_card_carries_the_row_a_list_draws() {
        val card = buildScoreMatchCard(log(toScore(11, 9)))
        card.scoreLogId shouldBe "log-1"
        card.title shouldBe "Thu League"
        card.createdAtEpochMs shouldBe Instant.parse("2026-08-27T18:00:00Z").toEpochMilliseconds()
        card.playersLine shouldBe "Coen vs Marco"
        card.scoreLine shouldBe "11-9"
        card.statusLine shouldBe "Scoring"
        card.isLive shouldBe true
        card.hasVideo shouldBe false
    }

    @Test
    fun a_doubles_card_names_all_four_players() {
        val card = buildScoreMatchCard(log(home = listOf("Coen", "Ana"), away = listOf("Marco", "Li")))
        card.playersLine shouldBe "Coen / Ana vs Marco / Li"
    }

    @Test
    fun a_finished_match_says_who_won() {
        val card = buildScoreMatchCard(log(toScore(21, 18) + toScore(21, 15), status = ScoreLogStatus.UNBOUND))
        card.statusLine shouldBe "Coen won"
        card.isLive shouldBe false
    }

    @Test
    fun a_finished_doubles_match_names_the_winning_pair() {
        val card = buildScoreMatchCard(
            log(
                toScore(21, 18) + toScore(21, 15),
                status = ScoreLogStatus.UNBOUND,
                home = listOf("Coen", "Ana"),
                away = listOf("Marco", "Li"),
            )
        )
        card.statusLine shouldBe "Coen / Ana won"
    }

    @Test
    fun a_match_that_ended_with_no_video_is_not_apologised_for() {
        // §7.1: unbound is a terminal state, not a failure. Nothing in the row may
        // read as "missing a video".
        val card = buildScoreMatchCard(log(toScore(21, 0) + toScore(21, 0), status = ScoreLogStatus.UNBOUND))
        card.statusLine shouldBe "Coen won"
        card.hasVideo shouldBe false
    }

    @Test
    fun a_bound_match_knows_it_has_a_video() {
        val card = buildScoreMatchCard(log(status = ScoreLogStatus.BOUND, videoId = "vid-1"))
        card.hasVideo shouldBe true
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreMatchCardTest"`
Expected: FAIL to compile, "Unresolved reference: sideLabel".

- [ ] **Step 3: Write the implementation**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCard.kt`:

```kotlin
package com.badmintontracker.shared.scoring

/**
 * One score-only match as a list row. Every string on it is built here rather than
 * per platform, for the same reason [com.badmintontracker.shared.model.buildMatchLabelSummary]
 * exists: two platforms formatting the same match are two chances to format it
 * differently.
 *
 * [createdAtEpochMs] is milliseconds rather than an Instant because it is a sort
 * key, and iOS already sorts its video matches on a Long.
 */
data class ScoreMatchCard(
    val scoreLogId: String,
    val title: String,
    val createdAtEpochMs: Long,
    /** "Coen vs Marco", or "Coen / Ana vs Marco / Li". */
    val playersLine: String,
    /** "Not started", "11-9", "21-18, 5-3". */
    val scoreLine: String,
    /** "Scoring" while live, "<winner> won" once it has a winner. */
    val statusLine: String,
    val isLive: Boolean,
    /** True once L2 has attached a video. Nothing in this release sets it. */
    val hasVideo: Boolean,
)

/** One name for a singles side, both names for a pair. */
fun sideLabel(players: List<String>): String = players.joinToString(" / ")

/**
 * The match's score as one line: every completed game, then the game in progress.
 *
 * A match nobody has started says so rather than showing 0-0, which reads as a bug
 * on a row. A finished match prints only its completed games: the fold leaves
 * [MatchState.currentGame] holding the final game's score on purpose, so appending
 * it would print the last game twice.
 */
fun scoreLine(state: MatchState): String {
    if (state.points.isEmpty() && state.completedGames.isEmpty()) return "Not started"
    val games = state.completedGames.map { "${it.home}-${it.away}" }
    val parts = if (state.isOver) games else games + "${state.currentGame.home}-${state.currentGame.away}"
    return if (parts.isEmpty()) "Not started" else parts.joinToString(", ")
}

fun buildScoreMatchCard(log: ScoreLog): ScoreMatchCard {
    val state = log.state()
    val winner = state.winner
    return ScoreMatchCard(
        scoreLogId = log.id,
        title = log.title,
        createdAtEpochMs = log.createdAt.toEpochMilliseconds(),
        playersLine = "${sideLabel(log.homePlayers)} vs ${sideLabel(log.awayPlayers)}",
        scoreLine = scoreLine(state),
        statusLine = when (winner) {
            null -> "Scoring"
            Side.HOME -> "${sideLabel(log.homePlayers)} won"
            Side.AWAY -> "${sideLabel(log.awayPlayers)} won"
        },
        isLive = winner == null,
        hasVideo = log.videoId != null,
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreMatchCardTest"`
Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCard.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreMatchCardTest.kt
git commit -m "feat(scoring): build every string a score-only match row shows, once"
```

---

### Task 4: The local-first store

**Files:**
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepositoryTest.kt`

**Interfaces:**
- Consumes: `ScoreLog`, `ScoreLogStatus`, `ScoreEvent`, `ScoringRules`, `MatchSetup`, `Settings`, `SupabaseClient`, `Ids.newId()`, `SyncLock`.
- Produces:
  - `class ScoreLogsRepository(client: SupabaseClient, settings: Settings, now: () -> Instant)`, plus a no-client `(settings, now, ownerId)` constructor for callers with no session - Android view model tests live in a different Gradle module and cannot see the internal test seam
  - `val logs: StateFlow<List<ScoreLog>>`
  - `fun create(title, homePlayers, awayPlayers, rules, setup): ScoreLog`
  - `fun replaceEvents(id: String, events: List<ScoreEvent>)`
  - `fun rename(id: String, title: String)`
  - `fun finish(id: String)`
  - `fun get(id: String): ScoreLog?`
  - `fun removeLocally(id: String)`
  - `suspend fun sync(): Result<Unit>`
  - `suspend fun delete(id: String): Result<Unit>`

The write half only. Every mutation lands in memory and on disk immediately and returns nothing to await, because a sports hall with no signal is the normal case and a coach must never wait on a network call between rallies. `sync()` is the only suspending write, and Task 5 covers it.

`now` is injected so the tests can pin `createdAt` without reaching for a clock; `LocalVideoRepository` gets away without one because its callers stamp their own timestamps.

- [ ] **Step 1: Write the failing test for the local half**

Create `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepositoryTest.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

class ScoreLogsRepositoryTest {

    private val t0 = Instant.parse("2026-08-27T18:00:00Z")

    /**
     * The internal test-seam constructor, the same shape AnnotationLabelsRepositoryImpl
     * uses. Two things need controlling: the clock, and who the cache thinks it
     * belongs to. The owner matters even in a local-only test - the cache refuses
     * to hand back a payload it cannot match to the signed-in user, so a repository
     * that reports no owner reads back nothing.
     */
    private fun repo(
        settings: MapSettings = MapSettings(),
        owner: String? = "owner-1",
        now: () -> Instant = { t0 },
    ) = ScoreLogsRepository(client = null, settings = settings, now = now, ownerId = { owner })

    private fun ScoreLogsRepository.newMatch() = create(
        title = "Thu League",
        homePlayers = listOf("Coen"),
        awayPlayers = listOf("Marco"),
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = false, firstServer = Side.HOME),
    )

    @Test
    fun a_new_match_is_live_with_no_video_and_no_points() {
        val created = repo().newMatch()
        created.status shouldBe ScoreLogStatus.LIVE
        created.videoId.shouldBeNull()
        created.events.shouldBeEmpty()
        created.createdAt shouldBe t0
    }

    @Test
    fun a_new_match_is_readable_immediately_rather_than_after_a_round_trip() {
        // The coach is standing on a court. Nothing here may wait on a network.
        val repo = repo()
        val created = repo.newMatch()
        repo.logs.value.map { it.id } shouldBe listOf(created.id)
        repo.get(created.id) shouldBe created
    }

    @Test
    fun scoring_replaces_the_event_list_wholesale() {
        // Undo and reset both produce a shorter list rather than an inverse
        // operation, so the store takes the list the fold is about to be given.
        val repo = repo()
        val created = repo.newMatch()
        repo.replaceEvents(created.id, listOf(ScoreEvent.PointTo(Side.HOME)))
        repo.get(created.id)?.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
        repo.replaceEvents(created.id, emptyList())
        repo.get(created.id)?.state()?.currentGame shouldBe SideScore.ZERO
    }

    @Test
    fun finishing_a_match_leaves_it_unbound_rather_than_incomplete() {
        // §7.1: a match with no video is a terminal state, not a half-done one.
        val repo = repo()
        val created = repo.newMatch()
        repo.finish(created.id)
        repo.get(created.id)?.status shouldBe ScoreLogStatus.UNBOUND
    }

    @Test
    fun renaming_trims_and_keeps_everything_else() {
        val repo = repo()
        val created = repo.newMatch()
        repo.rename(created.id, "  Club night  ")
        repo.get(created.id)?.title shouldBe "Club night"
        repo.get(created.id)?.homePlayers shouldBe listOf("Coen")
    }

    @Test
    fun mutating_a_match_that_is_gone_is_a_no_op() {
        val repo = repo()
        repo.rename("nope", "x")
        repo.replaceEvents("nope", listOf(ScoreEvent.PointTo(Side.HOME)))
        repo.finish("nope")
        repo.logs.value.shouldBeEmpty()
    }

    @Test
    fun matches_are_newest_first() {
        val settings = MapSettings()
        val first = repo(settings).newMatch()
        val second = repo(settings, now = { t0 + 1.hours }).newMatch()
        repo(settings).logs.value.map { it.id } shouldBe listOf(second.id, first.id)
    }

    @Test
    fun a_match_survives_the_app_being_killed_mid_game() {
        // The whole reason this store is local-first: the phone is in a bag between
        // games and the process does not survive it.
        val settings = MapSettings()
        val created = repo(settings).newMatch()
        repo(settings).replaceEvents(
            created.id, listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.PointTo(Side.AWAY))
        )
        repo(settings).get(created.id)?.state()?.currentGame shouldBe SideScore(home = 1, away = 1)
    }

    @Test
    fun another_accounts_matches_are_never_handed_back() {
        // Settings has no per-user namespacing and nothing guarantees a sign-out
        // hook runs before the next launch - a session can simply expire. So the
        // owner travels inside the payload, and a mismatch shows nothing.
        val settings = MapSettings()
        repo(settings, owner = "owner-1").newMatch()
        repo(settings, owner = "owner-2").logs.value.shouldBeEmpty()
    }

    @Test
    fun a_cache_with_no_owner_is_treated_as_a_mismatch() {
        // Signed out, or a payload written by a build before this scoping existed.
        // Same answer either way: an empty list beats leaking match names.
        val settings = MapSettings()
        repo(settings, owner = "owner-1").newMatch()
        repo(settings, owner = null).logs.value.shouldBeEmpty()
    }

    @Test
    fun an_unreadable_cache_yields_an_empty_list_rather_than_a_crash() {
        // Same contract as LocalVideoRepository.load: a payload this build cannot
        // decode must not take the whole match list down with it.
        val settings = MapSettings().apply { putString("score_logs_cache", "{ not json") }
        repo(settings).logs.value.shouldBeEmpty()
    }

    @Test
    fun removing_a_match_locally_takes_it_out_of_the_list() {
        val repo = repo()
        val created = repo.newMatch()
        repo.removeLocally(created.id)
        repo.logs.value.shouldBeEmpty()
        repo.get(created.id).shouldBeNull()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogsRepositoryTest"`
Expected: FAIL to compile, "Unresolved reference: ScoreLogsRepository".

- [ ] **Step 3: Write the local half**

Create `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt`:

```kotlin
package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.randomUuid
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every match this account has scored, local first.
 *
 * Local first is not an optimisation here. A coach scores in a sports hall with no
 * signal, on a phone that goes in a bag between games, so every mutation lands in
 * memory and on disk synchronously and nothing between rallies awaits a network
 * call. [sync] is the only suspending write.
 *
 * Registry pattern from [com.badmintontracker.shared.localvideo.LocalVideoRepository];
 * owner-scoped cache envelope from
 * [com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl].
 */
class ScoreLogsRepository internal constructor(
    /**
     * Null only in tests of the local half, which never reach the network. Every
     * suspending method on this class returns a failed Result rather than throwing
     * when it is null, so a mis-wired app graph surfaces as a sync error and not as
     * a crash between rallies.
     */
    private val client: SupabaseClient?,
    private val settings: Settings,
    private val now: () -> Instant,
    /**
     * Whose matches these are. A seam for the same reason
     * [com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl] has one:
     * the real answer comes from supabase-kt's asynchronously restored session,
     * which a test cannot control, and the owner decides whether the cache is
     * readable at all.
     */
    private val ownerId: () -> String?,
) {

    /**
     * The only constructor visible outside this module, and the only one the
     * exported Swift surface sees.
     */
    constructor(client: SupabaseClient, settings: Settings, now: () -> Instant) :
        this(client, settings, now, { client.auth.currentUserOrNull()?.id })

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * A stored match plus whether this device has changes the server has not seen.
     * [dirty] rather than a timestamp comparison: the server stamps updated_at from
     * its own clock, and a courtside phone's clock is not something to arbitrate on.
     */
    @Serializable
    private data class StoredLog(val log: ScoreLog, val dirty: Boolean)

    @Serializable
    private data class CachedLogs(
        @SerialName("owner_id") val ownerId: String?,
        val logs: List<StoredLog>,
    )

    private val state = MutableStateFlow(loadCache())

    /** Newest first, which is the order the match list renders. */
    val logs: StateFlow<List<ScoreLog>> = state.asStateFlow()

    // Serializes read-modify-write cycles. The scoring surface writes from the main
    // thread and sync writes from a background dispatcher.
    private val lock = SyncLock()

    private var stored: List<StoredLog> = loadStored()

    fun get(id: String): ScoreLog? = state.value.firstOrNull { it.id == id }

    fun create(
        title: String,
        homePlayers: List<String>,
        awayPlayers: List<String>,
        rules: ScoringRules,
        setup: MatchSetup,
    ): ScoreLog {
        val timestamp = now()
        val log = ScoreLog(
            id = randomUuid(),
            videoId = null,
            title = title.trim(),
            homePlayers = homePlayers.map { it.trim() }.filter { it.isNotEmpty() },
            awayPlayers = awayPlayers.map { it.trim() }.filter { it.isNotEmpty() },
            rules = rules,
            setup = setup,
            events = emptyList(),
            status = ScoreLogStatus.LIVE,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        mutate { it + StoredLog(log, dirty = true) }
        return log
    }

    fun replaceEvents(id: String, events: List<ScoreEvent>) =
        edit(id) { it.copy(events = events) }

    fun rename(id: String, title: String) =
        edit(id) { it.copy(title = title.trim()) }

    fun finish(id: String) =
        edit(id) { it.copy(status = ScoreLogStatus.UNBOUND) }

    /** Drops a match from this device without touching the server. Used by [delete] and by sign-out. */
    fun removeLocally(id: String) = mutate { list -> list.filterNot { it.log.id == id } }

    private fun edit(id: String, transform: (ScoreLog) -> ScoreLog) = mutate { list ->
        list.map {
            if (it.log.id != id) it
            else StoredLog(transform(it.log).copy(updatedAt = now()), dirty = true)
        }
    }

    private fun mutate(transform: (List<StoredLog>) -> List<StoredLog>) = lock.withLock {
        persist(transform(stored))
    }

    /** Caller must hold [lock]. */
    private fun persist(next: List<StoredLog>) {
        val sorted = next.sortedByDescending { it.log.createdAt }
        stored = sorted
        settings.putString(KEY_CACHE, json.encodeToString(
            CachedLogs.serializer(), CachedLogs(currentOwnerId(), sorted),
        ))
        state.value = sorted.map { it.log }
    }

    private fun loadStored(): List<StoredLog> {
        val cached = settings.getStringOrNull(KEY_CACHE)
            ?.let { runCatching { json.decodeFromString(CachedLogs.serializer(), it) }.getOrNull() }
            ?: return emptyList()
        // A cache written by another account, or by a build before this scoping
        // existed, is discarded rather than shown. Better an empty list than one
        // coach's match names in another coach's app.
        val owner = currentOwnerId()
        val usable = owner != null && owner == cached.ownerId
        return if (usable) cached.logs.sortedByDescending { it.log.createdAt } else emptyList()
    }

    private fun loadCache(): List<ScoreLog> = loadStored().map { it.log }

    private fun currentOwnerId(): String? = ownerId()

    internal companion object {
        const val KEY_CACHE = "score_logs_cache"
        const val TABLE = "score_logs"
    }
}
```

`randomUuid()` is the existing generator in `shared/src/commonMain/kotlin/com/badmintontracker/shared/util/Ids.kt`; it is `internal`, which reaches this package, and it is what the local video pipeline already mints client-side ids with. Do not add a second generator.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogsRepositoryTest"`
Expected: PASS, 12 tests.

Note that `loadStored()` is called twice on construction, once through `loadCache()` for the flow's initial value and once for `stored`. That is two Settings reads on a cold start of a list that is at most a few hundred small rows. Leave it: collapsing them means ordering the two property initialisers against each other, which is a sharper edge than the read costs.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepositoryTest.kt
git commit -m "feat(scoring): store matches on the device before anything reaches the network"
```

---

### Task 5: Sync

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepositoryTest.kt`

**Interfaces:**
- Consumes: everything Task 4 produced.
- Produces: `suspend fun sync(): Result<Unit>`, `suspend fun delete(id: String): Result<Unit>`

**The conflict rule, stated rather than discovered:** this account is a single writer. One phone scores a match; §7's L3 (a second device following along) is explicitly not proposed. So sync is: push every dirty row as an upsert, then pull the server's list and take it as authoritative for everything not dirty locally. A row that is dirty locally wins over its server copy, because the only way both changed is a second device this release does not support. When L3 arrives it replaces this rule, and the event log being append-only is what will make that replacement a replay rather than a reconciliation.

- [ ] **Step 1: Write the failing test**

Add to `ScoreLogsRepositoryTest.kt`, using the `TestSupabase` / `jsonResponse` mock-engine harness the repository tests in `shared/src/commonTest/.../repo/` already use rather than a second one. These assert on the requests the repository actually makes, because the failure worth catching is a match that silently never leaves the phone.

```kotlin
    /**
     * A fake server that keeps whatever was last upserted and hands it back on the
     * next read. Echoing rather than returning a canned list is what makes the
     * push-then-pull round trip in [sync] testable at all: a mock that always
     * replies with an empty array would report every pushed row as unacknowledged.
     */
    private class FakeScoreLogsServer {
        var rows: String = "[]"
        var failWith: HttpStatusCode? = null
        val requests = mutableListOf<Pair<String, String>>()

        fun client() = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            requests += request.method.value to body
            val failure = failWith
            when {
                failure != null -> jsonResponse("""{"message":"nope"}""", status = failure)
                request.method == HttpMethod.Get -> jsonResponse(rows)
                request.method == HttpMethod.Post -> { rows = body; jsonResponse("[]") }
                else -> jsonResponse("[]")
            }
        }

        fun posts() = requests.filter { it.first == "POST" }
    }

    private fun syncingRepo(server: FakeScoreLogsServer, settings: MapSettings = MapSettings()) =
        ScoreLogsRepository(server.client(), settings, now = { t0 }, ownerId = { "owner-1" })

    @Test
    fun syncing_pushes_local_matches_and_then_stops_pushing_them() = runTest {
        val server = FakeScoreLogsServer()
        val repo = syncingRepo(server)
        val created = repo.newMatch()

        repo.sync().isSuccess shouldBe true

        server.posts().shouldHaveSize(1)
        server.posts().first().second.shouldContain(created.id)
        server.posts().first().second.shouldContain(""""title":"Thu League"""")

        // Nothing is dirty any more, so a second sync sends no rows at all. Without
        // this the app re-uploads every match it has ever scored on every refresh.
        repo.sync().isSuccess shouldBe true
        server.posts().shouldHaveSize(1)
    }

    @Test
    fun syncing_pulls_matches_this_device_has_never_seen() = runTest {
        // A match scored before a reinstall, or on the coach's other phone.
        val server = FakeScoreLogsServer()
        server.rows = """
            [{"id":"remote-1","video_id":null,"title":"Club night",
              "home_players":["Coen"],"away_players":["Marco"],
              "rules":{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11},
              "setup":{"doubles":false,"first_server":"home"},
              "events":[{"type":"point","side":"home"}],
              "status":"unbound",
              "created_at":"2026-08-26T18:00:00Z","updated_at":"2026-08-26T18:00:00Z"}]
        """.trimIndent()
        val repo = syncingRepo(server)

        repo.sync().isSuccess shouldBe true

        repo.logs.value.map { it.id } shouldBe listOf("remote-1")
        repo.get("remote-1")?.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_point_scored_while_the_sync_was_in_flight_is_not_lost() = runTest {
        // The sharpest edge in this class. The push goes out, and between it and the
        // pull landing the coach scores. The pulled row is the pre-point version we
        // ourselves just sent, and taking it would silently un-score a rally.
        // One repository, not two: the race is inside a single instance, and a
        // second one over the same Settings would simply be reading a stale copy.
        // lateinit because the handler has to reach the repository that owns it.
        var rows = "[]"
        var scoredMidFlight = false
        lateinit var repo: ScoreLogsRepository
        lateinit var createdId: String

        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            when (request.method) {
                HttpMethod.Get -> {
                    if (!scoredMidFlight) {
                        scoredMidFlight = true
                        repo.replaceEvents(createdId, listOf(ScoreEvent.PointTo(Side.HOME)))
                    }
                    jsonResponse(rows)
                }
                else -> { rows = body; jsonResponse("[]") }
            }
        }
        repo = ScoreLogsRepository(client, MapSettings(), now = { t0 }, ownerId = { "owner-1" })
        createdId = repo.newMatch().id

        repo.sync().isSuccess shouldBe true

        repo.get(createdId)?.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_failed_sync_drops_nothing_and_retries_next_time() = runTest {
        // No signal is the normal case in a sports hall, not the exception.
        val server = FakeScoreLogsServer()
        val repo = syncingRepo(server)
        val created = repo.newMatch()
        server.failWith = HttpStatusCode.InternalServerError

        repo.sync().isFailure shouldBe true
        repo.logs.value.map { it.id } shouldBe listOf(created.id)

        server.failWith = null
        server.requests.clear()
        repo.sync().isSuccess shouldBe true
        // Still dirty after the failure, so the retry actually carries the match.
        server.posts().first().second.shouldContain(created.id)
    }

    @Test
    fun deleting_removes_a_match_locally_even_when_the_server_call_fails() = runTest {
        // Deliberate. The row is the user's own and the gesture is explicit, so a
        // match that reappears after a failed delete is a worse outcome than a
        // server row the next sync will not resurrect: removeLocally drops it from
        // the cache, and the pull only ever adds rows the server still returns.
        val server = FakeScoreLogsServer()
        val repo = syncingRepo(server)
        val created = repo.newMatch()
        server.failWith = HttpStatusCode.InternalServerError

        repo.delete(created.id).isFailure shouldBe true
        repo.logs.value.shouldBeEmpty()
        repo.get(created.id).shouldBeNull()
    }
```

and the imports these add:

```kotlin
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogsRepositoryTest"`
Expected: FAIL, "Unresolved reference: sync".

- [ ] **Step 3: Write the sync half**

Add to `ScoreLogsRepository`:

```kotlin
    /**
     * Pushes what this device changed, then takes the server's list for everything
     * it did not.
     *
     * Single writer per account: one phone scores a match, and a second device
     * following along is §7's L3, which is not proposed. So a locally dirty row
     * always wins over its server copy - the only way both could have changed is a
     * second writer this release does not have. L3 replaces this rule outright, and
     * the log being append-only is what will let it replay rather than reconcile.
     */
    suspend fun sync(): Result<Unit> {
        val postgrest = client?.postgrest ?: return Result.failure(
            IllegalStateException("Not signed in.")
        )
        return runCatching {
            val dirty = stored.filter { it.dirty }.map { it.log }
            if (dirty.isNotEmpty()) {
                postgrest.from(TABLE).upsert(dirty)
            }
            val remote = postgrest.from(TABLE)
                .select { order("created_at", Order.DESCENDING) }
                .decodeList<ScoreLog>()

            lock.withLock {
                // Compared by content, not by id. A row that is still byte for byte
                // what we pushed is settled and takes the server's copy; a row that
                // has changed since - the coach scored while the request was on the
                // wire - is still ahead of the server and keeps its local version.
                // Comparing ids instead would silently un-score that rally.
                val pushedById = dirty.associateBy { it.id }
                val localById = stored.associateBy { it.log.id }
                val merged = remote.map { row ->
                    val local = localById[row.id]
                    when {
                        local == null -> StoredLog(row, dirty = false)
                        local.dirty && local.log != pushedById[row.id] -> local
                        else -> StoredLog(row, dirty = false)
                    }
                }
                val remoteIds = remote.map { it.id }.toSet()
                // A dirty row the server has not acknowledged yet must not vanish
                // because the pull did not contain it.
                val unacknowledged = stored.filter { it.dirty && it.log.id !in remoteIds }
                persist(merged + unacknowledged)
            }
        }
    }

    /**
     * Removes a match everywhere. The local removal happens whether or not the
     * server call succeeds: the row is the user's own, the gesture is explicit, and
     * a match that reappears after a failed delete is a worse outcome than a server
     * row the next sync will not resurrect, since [sync] only adds rows the server
     * still returns.
     */
    suspend fun delete(id: String): Result<Unit> {
        removeLocally(id)
        val postgrest = client?.postgrest ?: return Result.failure(
            IllegalStateException("Not signed in.")
        )
        return runCatching {
            postgrest.from(TABLE).delete { filter { eq("id", id) } }
            Unit
        }
    }
```

and the imports it needs:

```kotlin
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:jvmTest --tests "com.badmintontracker.shared.scoring.ScoreLogsRepositoryTest"`
Expected: PASS, 17 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt \
        shared/src/commonTest/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepositoryTest.kt
git commit -m "feat(scoring): sync matches to score_logs, local changes winning"
```

---

### Task 6: Wire it into the app graph

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`

**Interfaces:**
- Consumes: `ScoreLogsRepository`.
- Produces:
  - `RallyApp.scoreLogs: ScoreLogsRepository`
  - `suspend fun ScoreLogsRepository.syncScoreLogsOrMessage(): String?`
  - `suspend fun ScoreLogsRepository.deleteScoreMatchOrMessage(id: String): String?`

- [ ] **Step 1: Expose the repository**

In `RallyApp.kt`, beside the other on-device stores:

```kotlin
    // Matches scored on this phone. Local first, like the video registry above it:
    // a match is created and scored courtside, where there is usually no signal.
    val scoreLogs: ScoreLogsRepository = ScoreLogsRepository(client, settings, Clock.System::now)
```

with the imports:

```kotlin
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import kotlinx.datetime.Clock
```

- [ ] **Step 2: Add the Swift wrappers**

In `SwiftInterop.kt`, following the file's existing contract that `kotlin.Result` does not cross the ObjC bridge usefully:

```kotlin
/** Soft-failing: nil means the sync landed, a string is ready to display. */
suspend fun ScoreLogsRepository.syncScoreLogsOrMessage(): String? =
    sync().exceptionOrNull()?.let { "Couldn't sync your matches. They're saved on this phone." }

suspend fun ScoreLogsRepository.deleteScoreMatchOrMessage(id: String): String? =
    delete(id).exceptionOrNull()?.let { "Couldn't delete the match everywhere. It's gone from this phone." }
```

Both messages say what actually happened rather than "please try again", because in both cases the local state is already correct and the user does not need to retry anything.

- [ ] **Step 3: Verify both platforms still build**

```bash
./gradlew :shared:jvmTest :androidApp:assembleDebug
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

Expected: PASS. Nothing consumes the new property yet; this is confirming the graph and the framework link.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt \
        shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt
git commit -m "feat(scoring): expose the match store to both platforms"
```

---

### Task 7: Android - the row union

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchRow.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt`

**Interfaces:**
- Consumes: `MatchSummary` (unchanged), `ScoreMatchCard` (Task 3).
- Produces:
  - `sealed interface MatchRow` with `val key: String`, `val sortAtEpochMs: Long`, `data class Video(val match: MatchSummary)`, `data class Score(val card: ScoreMatchCard)`
  - `internal fun mergeMatchRows(videoMatches: List<MatchSummary>, scoreMatches: List<ScoreMatchCard>): List<MatchRow>`

**`toMatches` is not touched.** It keeps grouping clips into `MatchSummary` exactly as it does today, with its existing tests unchanged, and this merges its output with the score-only cards. Two row kinds in one sorted section is what "the same list" means; a nullable `videoId` threaded through `MatchSummary` would instead leave an unchecked branch at every one of the five places that dereference it - the list key, the cover clip, the rally count, the navigation value and the delete call.

Score logs are owner-only by RLS, so they merge into the owned section and never into the shared one. The owned/shared partition is unchanged.

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt`:

```kotlin
package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.ScoreMatchCard
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

class MatchRowMergeTest {

    private fun coverClip(videoId: String) = RallyClip(
        id = "c-$videoId", videoId = videoId, ownerId = "user-self", rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/c-$videoId.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun videoMatch(videoId: String, atMillis: Long) = MatchSummary(
        videoId = videoId,
        rallyCount = 3,
        latestCreatedAt = Instant.fromEpochMilliseconds(atMillis),
        coverClip = coverClip(videoId),
        isOwned = true,
    )

    private fun scoreMatch(id: String, atMillis: Long) = ScoreMatchCard(
        scoreLogId = id,
        title = "Thu League",
        createdAtEpochMs = atMillis,
        playersLine = "Coen vs Marco",
        scoreLine = "11-9",
        statusLine = "Scoring",
        isLive = true,
        hasVideo = false,
    )

    @Test
    fun the_two_kinds_interleave_by_date_rather_than_stacking() {
        // The whole point of the decision this implements: a match scored last night
        // sits above a video analysed last week, not in a section beneath it.
        val rows = mergeMatchRows(
            videoMatches = listOf(videoMatch("v1", 300), videoMatch("v2", 100)),
            scoreMatches = listOf(scoreMatch("s1", 200)),
        )
        rows.map { it.key } shouldBe listOf("video-v1", "score-s1", "video-v2")
    }

    @Test
    fun rows_carry_their_own_kind() {
        val rows = mergeMatchRows(listOf(videoMatch("v1", 100)), listOf(scoreMatch("s1", 200)))
        (rows[0] as MatchRow.Score).card.scoreLogId shouldBe "s1"
        (rows[1] as MatchRow.Video).match.videoId shouldBe "v1"
    }

    @Test
    fun two_matches_at_the_same_instant_keep_a_fixed_order() {
        // A list that reshuffles two same-second rows between refreshes reads as a
        // glitch, so the tie-break is on the key and is fully determined.
        val a = mergeMatchRows(listOf(videoMatch("v1", 100)), listOf(scoreMatch("s1", 100)))
        val b = mergeMatchRows(listOf(videoMatch("v1", 100)), listOf(scoreMatch("s1", 100)))
        a.map { it.key } shouldBe b.map { it.key }
        a.map { it.key } shouldBe listOf("score-s1", "video-v1")
    }

    @Test
    fun keys_from_the_two_kinds_cannot_collide() {
        // Both ids are UUIDs from the same generator, so an unprefixed key would let
        // a video match and a score match claim the same LazyColumn slot.
        val rows = mergeMatchRows(listOf(videoMatch("same", 100)), listOf(scoreMatch("same", 200)))
        rows.map { it.key } shouldBe listOf("score-same", "video-same")
    }

    @Test
    fun an_account_with_only_scored_matches_still_gets_a_list() {
        mergeMatchRows(emptyList(), listOf(scoreMatch("s1", 100))).map { it.key } shouldBe listOf("score-s1")
    }

    @Test
    fun an_account_with_neither_gets_nothing() {
        mergeMatchRows(emptyList(), emptyList()).shouldBeEmpty()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.MatchRowMergeTest"`
Expected: FAIL to compile, "Unresolved reference: mergeMatchRows".

- [ ] **Step 3: Write the implementation**

Create `androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchRow.kt`:

```kotlin
package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.scoring.ScoreMatchCard

/**
 * One row of the match list, which since live scoring holds two kinds of thing: a
 * match cut from a video, and a match scored courtside that may never have one.
 *
 * A sealed interface rather than a nullable videoId on MatchSummary. Five places
 * dereference that id - the list key, the cover thumbnail, the rally count, the
 * navigation value and delete_match - and a null there is a different row, not a
 * missing field. This way the compiler asks at each of them.
 */
sealed interface MatchRow {
    /** Prefixed: both ids are UUIDs from the same generator and would otherwise collide. */
    val key: String
    val sortAtEpochMs: Long

    data class Video(val match: MatchSummary) : MatchRow {
        override val key: String get() = "video-${match.videoId}"
        override val sortAtEpochMs: Long get() = match.latestCreatedAt.toEpochMilliseconds()
    }

    data class Score(val card: ScoreMatchCard) : MatchRow {
        override val key: String get() = "score-${card.scoreLogId}"
        override val sortAtEpochMs: Long get() = card.createdAtEpochMs
    }
}

/**
 * Interleaves the two kinds into one newest-first list. Score logs are owner-only
 * by RLS, so this only ever builds the owned section; the shared section is
 * untouched by live scoring.
 *
 * The tie-break on [MatchRow.key] is not decoration: two rows created in the same
 * second must not swap places between refreshes.
 */
internal fun mergeMatchRows(
    videoMatches: List<MatchSummary>,
    scoreMatches: List<ScoreMatchCard>,
): List<MatchRow> =
    (videoMatches.map(MatchRow::Video) + scoreMatches.map(MatchRow::Score))
        .sortedWith(compareByDescending<MatchRow> { it.sortAtEpochMs }.thenBy { it.key })
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.MatchRowMergeTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Run the existing list tests to prove they were not disturbed**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.cliplist.*"`
Expected: PASS, `ClipListViewModelTest` and `ClipSortTest` unchanged and green. If either needed editing, `toMatches` was touched and it should not have been.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/cliplist/MatchRow.kt \
        androidApp/src/test/java/com/badmintontracker/android/cliplist/MatchRowMergeTest.kt
git commit -m "feat(android): merge scored matches into the match list beside video ones"
```

---

### Task 8: Android - create a match

**Files:**
- Create: `androidApp/src/main/java/com/badmintontracker/android/scoring/NewMatchViewModel.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/scoring/NewMatchScreen.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/scoring/NewMatchViewModelTest.kt`

**Interfaces:**
- Consumes: `ScoreLogsRepository`, `createMatchProblem`, `ScoringRules.PRESETS`, `MatchSetup`, `Side`.
- Produces:
  - `data class NewMatchState(title, doubles, homePlayers: List<String>, awayPlayers: List<String>, rules: ScoringRules, firstServer: Side, problem: String?, canCreate: Boolean)`
  - `class NewMatchViewModel(scoreLogs: ScoreLogsRepository)` with `setTitle`, `setDoubles`, `setPlayer(side, index, name)`, `setRules`, `setFirstServer`, `create(): String?` returning the new match's id or null

The view model file holds no composables so it stays unit-testable on the JVM without Compose, matching how `MatchSummaryViewModel` and `MatchSummaryUi` are split.

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/java/com/badmintontracker/android/scoring/NewMatchViewModelTest.kt`:

```kotlin
package com.badmintontracker.android.scoring

import com.badmintontracker.shared.scoring.PairPlayer
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.Test

class NewMatchViewModelTest {

    private fun vm() = NewMatchViewModel(
        ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
    )

    @Test
    fun an_empty_form_cannot_be_submitted_and_says_nothing_yet() {
        // No red text before the user has typed anything: the problem is reported,
        // but the button is what tells them they are not done.
        val state = vm().state.value
        state.canCreate shouldBe false
        state.problem shouldBe null
    }

    @Test
    fun a_complete_singles_form_can_be_submitted() {
        val vm = vm()
        vm.setTitle("Thu League")
        vm.setPlayer(Side.HOME, 0, "Coen")
        vm.setPlayer(Side.AWAY, 0, "Marco")
        vm.state.value.canCreate shouldBe true
        vm.state.value.problem shouldBe null
    }

    @Test
    fun switching_to_doubles_needs_the_second_pair_of_names() {
        val vm = vm()
        vm.setTitle("Club night")
        vm.setPlayer(Side.HOME, 0, "Coen")
        vm.setPlayer(Side.AWAY, 0, "Marco")
        vm.setDoubles(true)
        vm.state.value.canCreate shouldBe false
        vm.state.value.problem shouldBe "Doubles needs two players on each side."
        vm.setPlayer(Side.HOME, 1, "Ana")
        vm.setPlayer(Side.AWAY, 1, "Li")
        vm.state.value.canCreate shouldBe true
    }

    @Test
    fun switching_back_to_singles_does_not_lose_the_typed_second_names() {
        // Toggling a segmented control must not destroy typing. The names are kept
        // and simply not submitted.
        val vm = vm()
        vm.setDoubles(true)
        vm.setPlayer(Side.HOME, 1, "Ana")
        vm.setDoubles(false)
        vm.setDoubles(true)
        vm.state.value.homePlayers[1] shouldBe "Ana"
    }

    @Test
    fun the_default_rules_are_the_bwf_game() {
        vm().state.value.rules shouldBe ScoringRules.BWF_21
    }

    @Test
    fun creating_stores_a_live_match_and_hands_back_its_id() {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
        val vm = NewMatchViewModel(repo)
        vm.setTitle("Thu League")
        vm.setPlayer(Side.HOME, 0, "Coen")
        vm.setPlayer(Side.AWAY, 0, "Marco")
        vm.setRules(ScoringRules.CLUB_15)
        vm.setFirstServer(Side.AWAY)

        val id = vm.create()
        id.shouldNotBeNull()
        val stored = repo.get(id)
        stored.shouldNotBeNull()
        stored.title shouldBe "Thu League"
        stored.homePlayers shouldBe listOf("Coen")
        stored.rules shouldBe ScoringRules.CLUB_15
        stored.setup.firstServer shouldBe Side.AWAY
        stored.setup.doubles shouldBe false
        stored.status shouldBe ScoreLogStatus.LIVE
    }

    @Test
    fun a_doubles_match_stores_the_starting_arrangement() {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
        val vm = NewMatchViewModel(repo)
        vm.setTitle("Club night")
        vm.setDoubles(true)
        vm.setPlayer(Side.HOME, 0, "Coen"); vm.setPlayer(Side.HOME, 1, "Ana")
        vm.setPlayer(Side.AWAY, 0, "Marco"); vm.setPlayer(Side.AWAY, 1, "Li")
        val stored = repo.get(vm.create()!!)!!
        stored.setup.doubles shouldBe true
        // Index 0 of each side starts in the right service court, which is what the
        // form's name order means and what L1b's serve display reads.
        stored.setup.homeStartsRight shouldBe PairPlayer.FIRST
        stored.setup.awayStartsRight shouldBe PairPlayer.FIRST
    }

    @Test
    fun an_incomplete_form_creates_nothing() {
        val repo = ScoreLogsRepository(MapSettings(), now = { Instant.parse("2026-08-27T18:00:00Z") }, ownerId = { "owner-1" })
        val vm = NewMatchViewModel(repo)
        vm.setTitle("Thu League")
        vm.create() shouldBe null
        repo.logs.value shouldBe emptyList()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.scoring.NewMatchViewModelTest"`
Expected: FAIL to compile, "Unresolved reference: NewMatchViewModel".

- [ ] **Step 3: Write the view model**

Create `androidApp/src/main/java/com/badmintontracker/android/scoring/NewMatchViewModel.kt`:

```kotlin
package com.badmintontracker.android.scoring

import androidx.lifecycle.ViewModel
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.createMatchProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NewMatchState(
    val title: String = "",
    val doubles: Boolean = false,
    /**
     * Always two entries per side. The second is ignored in singles rather than
     * cleared, so toggling the format twice does not destroy what was typed.
     */
    val homePlayers: List<String> = listOf("", ""),
    val awayPlayers: List<String> = listOf("", ""),
    val rules: ScoringRules = ScoringRules.BWF_21,
    val firstServer: Side = Side.HOME,
    /** Null while the form is untouched, so nothing is red before anyone has typed. */
    val problem: String? = null,
    val canCreate: Boolean = false,
)

class NewMatchViewModel(private val scoreLogs: ScoreLogsRepository) : ViewModel() {

    private val internal = MutableStateFlow(NewMatchState())
    val state: StateFlow<NewMatchState> = internal.asStateFlow()

    private var touched = false

    fun setTitle(title: String) = edit { it.copy(title = title) }
    fun setDoubles(doubles: Boolean) = edit { it.copy(doubles = doubles) }
    fun setRules(rules: ScoringRules) = edit { it.copy(rules = rules) }
    fun setFirstServer(side: Side) = edit { it.copy(firstServer = side) }

    fun setPlayer(side: Side, index: Int, name: String) = edit { current ->
        val replace = { list: List<String> -> list.mapIndexed { i, v -> if (i == index) name else v } }
        if (side == Side.HOME) current.copy(homePlayers = replace(current.homePlayers))
        else current.copy(awayPlayers = replace(current.awayPlayers))
    }

    /** The new match's id, or null when the form is not complete. */
    fun create(): String? {
        val s = internal.value
        if (!s.canCreate) return null
        return scoreLogs.create(
            title = s.title,
            homePlayers = submitted(s.homePlayers, s.doubles),
            awayPlayers = submitted(s.awayPlayers, s.doubles),
            rules = s.rules,
            setup = MatchSetup(doubles = s.doubles, firstServer = s.firstServer),
        ).id
    }

    private fun edit(transform: (NewMatchState) -> NewMatchState) {
        touched = true
        val next = transform(internal.value)
        val problem = createMatchProblem(
            title = next.title,
            homePlayers = submitted(next.homePlayers, next.doubles),
            awayPlayers = submitted(next.awayPlayers, next.doubles),
            doubles = next.doubles,
        )
        internal.value = next.copy(
            problem = if (touched) problem else null,
            canCreate = problem == null,
        )
    }

    private fun submitted(players: List<String>, doubles: Boolean): List<String> =
        players.take(if (doubles) 2 else 1)
}
```

Note `touched` is set by the first `edit`, so the initial state's `problem` stays null and the very first keystroke is what starts reporting. The test for the empty form asserts exactly that.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.badmintontracker.android.scoring.NewMatchViewModelTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Write the screen**

Create `androidApp/src/main/java/com/badmintontracker/android/scoring/NewMatchScreen.kt`. A single scrolling column in the existing `ShuttlTheme`, matching the spacing and the field styling of the local video details sheet rather than inventing a second form idiom:

- A top app bar titled "New match", with a back arrow and a "Create" text action that is disabled while `canCreate` is false.
- `OutlinedTextField` for the match name, `singleLine`, with a supporting-text line showing `problem` when it is non-null.
- A `SingleChoiceSegmentedButtonRow` for Singles / Doubles.
- A "Players" section: one name field per side in singles, two per side in doubles, labelled with the side's name. The second field animates in rather than appearing instantly, since the row above it is already on screen.
- A rules row rendering `ScoringRules.PRESETS` as a `SingleChoiceSegmentedButtonRow`, labelled from a local `fun ScoringRules.label()` that reads "21 points", "15 points", "15 straight" - the presets are a shared list but their user-facing names are Android copy.
- A "First serve" segmented row over the two side names, defaulting to home. Its section header is "Coin toss".
- The "Create" action calls `vm.create()` and hands the returned id to `onCreated`.

The screen takes `vm: NewMatchViewModel`, `onCreated: (String) -> Unit` and `onBack: () -> Unit`, so it holds no navigation knowledge, matching `LabelsScreen`.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/scoring/ \
        androidApp/src/test/java/com/badmintontracker/android/scoring/
git commit -m "feat(android): create a match before there is any video"
```

---

### Task 9: Android - show it, open it, delete it

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListScreen.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt` - **two** `ClipListViewModel` construction sites, one under `Route.ClipList` and one under `Route.MatchClips`; both take the new argument
- Create: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchViewModel.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/scoring/ScoreMatchScreen.kt`

**Interfaces:**
- Consumes: `MatchRow`, `mergeMatchRows`, `ScoreLogsRepository`, `buildScoreMatchCard`.
- Produces:
  - `ClipListState.ownedRows: List<MatchRow>` replacing the screen's use of `ownedMatches` (the property itself stays, so nothing that reads it breaks)
  - `Route.NewMatch`, `Route.ScoreMatch(scoreLogId: String)`
  - `class ScoreMatchViewModel(scoreLogs, scoreLogId)` exposing the log, its `MatchState` and its card

- [ ] **Step 1: Merge the rows into the list state**

In `ClipListViewModel`, take the score logs as a fifth constructor dependency and fold them into the existing `combine`. `ownedMatches` stays exactly as it is; `ownedRows` is added beside it:

```kotlin
data class ClipListState(
    val clips: List<RallyClip> = emptyList(),
    val ownedMatches: List<MatchSummary> = emptyList(),
    val ownedRows: List<MatchRow> = emptyList(),
    val sharedMatches: List<MatchSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
)
```

`combine` takes at most five flows in the overload this file uses, and it already passes five. Add the score logs by combining the existing result with them rather than reaching for the vararg overload:

```kotlin
    private val scoreCards = scoreLogs.logs.map { logs -> logs.map(::buildScoreMatchCard) }

    val state = combine(
        clips.observeClips(), sharerByVideoId, metadataByVideoId, refreshing, errors,
    ) { list, sharerMap, metadataMap, r, e ->
        // unchanged body, now returning the state without ownedRows
    }.combine(scoreCards) { base, cards ->
        base.copy(ownedRows = mergeMatchRows(base.ownedMatches, cards))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipListState())
```

Extend `refresh()` to sync the score logs alongside the other reads, as a fourth `async` with the same soft-failure contract as the shares and metadata lookups: a failed sync leaves the local matches on screen and says nothing, because they are all still there.

Add the two actions the list needs:

```kotlin
    fun deleteScoreMatch(scoreLogId: String) {
        viewModelScope.launch {
            scoreLogs.delete(scoreLogId)
                .onFailure { errors.value = "Couldn't delete the match everywhere. It's gone from this phone." }
        }
    }
```

There is no `leaveShare` equivalent: a score-only match cannot be shared, so it cannot be left.

- [ ] **Step 2: Render both row kinds**

In `ClipListScreen`, replace the owned section's `items(state.ownedMatches, ...)` with `items(state.ownedRows, key = { it.key })` and a `when` over the row:

- `MatchRow.Video` keeps the existing `SwipeToRemoveRow(label = "Delete")` wrapping `MatchRow(...)` verbatim. Nothing about the video row changes.
- `MatchRow.Score` gets the same `SwipeToRemoveRow(label = "Delete")` around a new `ScoreMatchRow` composable, with the confirmation dialog routed to `vm::deleteScoreMatch`.

`ScoreMatchRow` mirrors `MatchRow`'s three-line layout exactly so the two kinds line up down the list:

- The cover slot is the same 96 x 54 box, filled with `bgTertiary` and a centred scoreboard icon rather than a thumbnail. Same size, same corner treatment, same 12dp gap: a row that is 4dp shorter than its neighbour is the kind of thing this codebase treats as a defect.
- Primary line: `card.title`.
- Secondary line: `"${card.scoreLine} · ${formatDate(...)}"`, uppercased and kerned exactly as `matchRowSecondary` does.
- Third line: `card.playersLine`, in the slot the video row gives to the description.
- The share affordance is present but disabled, with a tooltip or supporting line reading "Add a video to share this match." §7.1 asks for an explicit disabled state with a reason rather than a missing button, so the coach learns the rule instead of wondering where the button went.
- A live match carries a small "Scoring" chip from `card.statusLine`; a finished one shows the winner line instead.

Update the empty state: it currently reads "No matches yet. Record one with the + button above." That is now wrong, because a match can also be scored. Change it to "No matches yet. Score one or record a video with the + button above."

Add "New match" as the first entry of the existing add menu, above "Record video" and "Import video", navigating to `Route.NewMatch`.

- [ ] **Step 3: Add the routes and the match page**

In `Route.kt`:

```kotlin
    @Serializable data object NewMatch : Route
    @Serializable data class  ScoreMatch(val scoreLogId: String) : Route
```

`ScoreMatchViewModel` exposes the log, its folded `MatchState` and its `ScoreMatchCard` from `scoreLogs.logs`, so a rename or a later score change redraws without a refresh. `ScoreMatchScreen` shows:

- A top bar with the match title and a back arrow.
- A header card: the players, the score line, the status line and the rules ("21 points, best of 3").
- The point list from `state.points`, newest first, each row reading `"${p.scoreAfter.home}-${p.scoreAfter.away}"`, the winning side's name, and its tags as label chips reusing the annotation chip component.
- The empty state: "No points scored yet." This is a real state and stays one after L1b - a match created and not started looks exactly like this.

Wire both into `AuthGate`'s `NavHost` following the existing `composable<Route.Labels>` and `composable<Route.MatchClips>` patterns, and route the list's row click by kind: `MatchRow.Video` to `Route.MatchClips(videoId)` exactly as today, `MatchRow.Score` to `Route.ScoreMatch(scoreLogId)`.

- [ ] **Step 4: Verify**

```bash
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Expected: PASS, with `ClipListViewModelTest` still green and unedited except for the new constructor argument.

Then run the app and check by hand, because none of this is reachable from a unit test:

1. Create a match. It appears in "My matches", in date order among the video matches, not in a section of its own.
2. Its row is the same height as the video rows either side of it, and the cover placeholders line up.
3. Open it. The header reads correctly and the point list says "No points scored yet."
4. Swipe it. Confirm. It goes, and it does not come back on pull to refresh.
5. Kill the app from the recents list mid-form and reopen: no half-created match.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/
git commit -m "feat(android): show scored matches in the match list and open them"
```

---

### Task 10: iOS - the row union

**Files:**
- Modify: `iosApp/Sources/ClipList/MatchGrouping.swift`
- Test: `iosApp/Tests/MatchGroupingTests.swift`

**Interfaces:**
- Consumes: `MatchSummary` (unchanged), `ScoreMatchCard` (Task 3, through `Shared`).
- Produces:
  - `enum MatchRow: Identifiable { case video(MatchSummary); case score(ScoreMatchCard) }` with `var id: String` and `var sortAtEpochMs: Int64`
  - `func mergeMatchRows(videoMatches: [MatchSummary], scoreMatches: [ScoreMatchCard]) -> [MatchRow]`

Port of Android's `MatchRow` and `mergeMatchRows`, and it must stay one: the two lists have to order identically or the same account looks different on the two phones in a coach's bag. `MatchGrouping.matches` is not touched.

- [ ] **Step 1: Write the failing test**

Add to `iosApp/Tests/MatchGroupingTests.swift`, keeping every existing test in the file unchanged:

```swift
    private func scoreCard(_ id: String, at millis: Int64) -> ScoreMatchCard {
        ScoreMatchCard(
            scoreLogId: id,
            title: "Thu League",
            createdAtEpochMs: millis,
            playersLine: "Coen vs Marco",
            scoreLine: "11-9",
            statusLine: "Scoring",
            isLive: true,
            hasVideo: false
        )
    }

    private func videoSummary(_ videoId: String, at millis: Int64) -> MatchSummary {
        MatchSummary(
            videoId: videoId, rallyCount: 3, latestCreatedAtMillis: millis,
            coverClipId: "c-\(videoId)", isOwned: true, sharerEmail: nil,
            title: nil, description: nil
        )
    }

    func testScoredAndVideoMatchesInterleaveByDate() {
        let rows = mergeMatchRows(
            videoMatches: [videoSummary("v1", at: 300), videoSummary("v2", at: 100)],
            scoreMatches: [scoreCard("s1", at: 200)]
        )
        XCTAssertEqual(rows.map(\.id), ["video-v1", "score-s1", "video-v2"])
    }

    func testTiesKeepAFixedOrderMatchingAndroid() {
        let rows = mergeMatchRows(
            videoMatches: [videoSummary("v1", at: 100)],
            scoreMatches: [scoreCard("s1", at: 100)]
        )
        XCTAssertEqual(rows.map(\.id), ["score-s1", "video-v1"])
    }

    func testKeysFromTheTwoKindsCannotCollide() {
        let rows = mergeMatchRows(
            videoMatches: [videoSummary("same", at: 100)],
            scoreMatches: [scoreCard("same", at: 200)]
        )
        XCTAssertEqual(rows.map(\.id), ["score-same", "video-same"])
    }

    func testAnAccountWithOnlyScoredMatchesStillGetsAList() {
        XCTAssertEqual(
            mergeMatchRows(videoMatches: [], scoreMatches: [scoreCard("s1", at: 100)]).map(\.id),
            ["score-s1"]
        )
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run the iOS test command from Global Constraints.
Expected: FAIL to compile, "cannot find 'mergeMatchRows' in scope".

- [ ] **Step 3: Write the implementation**

Add to `iosApp/Sources/ClipList/MatchGrouping.swift`, below `MatchSummary` and leaving `MatchGrouping.matches` untouched:

```swift
/// One row of the match list, which since live scoring holds two kinds of thing: a
/// match cut from a video, and a match scored courtside that may never have one.
/// Port of Android's `MatchRow`.
enum MatchRow: Identifiable {
    case video(MatchSummary)
    case score(ScoreMatchCard)

    /// Prefixed: both ids are UUIDs from the same generator and would otherwise collide.
    var id: String {
        switch self {
        case .video(let match): return "video-\(match.videoId)"
        case .score(let card):  return "score-\(card.scoreLogId)"
        }
    }

    var sortAtEpochMs: Int64 {
        switch self {
        case .video(let match): return match.latestCreatedAtMillis
        case .score(let card):  return card.createdAtEpochMs
        }
    }
}

/// Interleaves the two kinds into one newest-first list. Score logs are owner-only
/// by RLS, so this only ever builds the owned section.
///
/// The tie-break on `id` is not decoration, and it must match Android's exactly:
/// the same account on two phones has to produce the same order.
/// Port of Android's `mergeMatchRows`.
func mergeMatchRows(videoMatches: [MatchSummary], scoreMatches: [ScoreMatchCard]) -> [MatchRow] {
    let rows = videoMatches.map(MatchRow.video) + scoreMatches.map(MatchRow.score)
    return rows.sorted {
        $0.sortAtEpochMs != $1.sortAtEpochMs
            ? $0.sortAtEpochMs > $1.sortAtEpochMs
            : $0.id < $1.id
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run the iOS test command.
Expected: PASS, the four new tests plus every existing `MatchGroupingTests` case unchanged.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Sources/ClipList/MatchGrouping.swift iosApp/Tests/MatchGroupingTests.swift
git commit -m "feat(ios): merge scored matches into the match list beside video ones"
```

---

### Task 11: iOS - create, show, open, delete

**Files:**
- Create: `iosApp/Sources/Scoring/NewMatchView.swift`
- Create: `iosApp/Sources/Scoring/ScoreMatchView.swift`
- Create: `iosApp/Sources/Scoring/ScoreMatchModel.swift`
- Modify: `iosApp/Sources/ClipList/ClipListModel.swift`
- Modify: `iosApp/Sources/ClipList/ClipListView.swift`
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj` (xcodegen output)

**Interfaces:**
- Consumes: `MatchRow`, `mergeMatchRows`, `RallyApp.scoreLogs`, `buildScoreMatchCard`, `createMatchProblem`, `SwiftInteropKt.syncScoreLogsOrMessage`, `SwiftInteropKt.deleteScoreMatchOrMessage`.
- Produces:
  - `ClipListModel.ownedRows: [MatchRow]`, `ClipListModel.deleteScoreMatch(scoreLogId:)`
  - `struct ScoreMatchRoute: Hashable { let scoreLogId: String }`
  - `NewMatchView`, `ScoreMatchView`, `ScoreMatchModel`

- [ ] **Step 1: Feed the rows through the list model**

In `ClipListModel`, observe the score logs alongside the clips and rebuild the rows in `regroup()`:

```swift
    private(set) var ownedRows: [MatchRow] = []
    private var scoreCards: [ScoreMatchCard] = []
```

`start()` gains a second `for await` over `rally.scoreLogs.logs`, mapping each log through `ScoreMatchCardKt.buildScoreMatchCard(log:)` and calling `regroup()`. Run it in its own `Task` rather than after the clips loop, which never returns.

`regroup()` gains one line after it assigns `owned` and `shared`:

```swift
        ownedRows = mergeMatchRows(videoMatches: owned, scoreMatches: scoreCards)
```

`refresh()` gains a score log sync with the same soft-failure contract as the shares and metadata reads - a failure leaves the local matches on screen and says nothing, because they are all still there:

```swift
        _ = try? await SwiftInteropKt.syncScoreLogsOrMessage(rally.scoreLogs)
```

and a delete:

```swift
    func deleteScoreMatch(scoreLogId: String) async {
        if let message = try? await SwiftInteropKt.deleteScoreMatchOrMessage(rally.scoreLogs, id: scoreLogId) {
            error = message
        }
        // No refresh: the repository already dropped it locally, and its flow has
        // already pushed the shorter list through regroup().
    }
```

- [ ] **Step 2: Render both row kinds**

In `ClipListView.content(_:)`, replace `ForEach(model.owned, id: \.videoId)` with `ForEach(model.ownedRows)` and switch on the row. The `.video` case keeps its existing `row(match, model: model)` and its swipe action verbatim; the `.score` case gets a new `scoreRow(card)` and a delete swipe routed through `PendingMatchAction`.

`scoreRow` mirrors `row` exactly - same 96 x 54 leading slot filled with `Shuttl.bgTertiary` and a `list.number` SF Symbol instead of an `AsyncImage`, same 12pt `HStack` spacing, same three text lines with the same fonts, kerning and `lineLimit`s. The trailing share button is rendered `.disabled(true)` with the reason as its accessibility label, per §7.1's explicit-disabled-state requirement.

The empty state loses its video-only wording, matching Android: "No matches yet. Score one or record a video with the + button above."

Add "New match" to the existing plus menu, above "Record video" and "Import video".

Navigation follows the file's established pattern: `NavigationLink(value: ScoreMatchRoute(scoreLogId:))` plus a third destination beside the two already there, leaving the existing `String` and `LocalPlayerRoute` destinations alone:

```swift
        .navigationDestination(for: ScoreMatchRoute.self) { route in
            ScoreMatchView(rally: rally, scoreLogId: route.scoreLogId)
        }
```

- [ ] **Step 3: Write the two screens**

`NewMatchView` is the SwiftUI counterpart of Task 8's form, and it must agree with it on every rule, because both call `createMatchProblem`. A `Form` with four sections - name, format, players, rules and coin toss - a "Create" toolbar button disabled while the problem is non-nil, and the same "nothing red until the first keystroke" behaviour. On create it calls `rally.scoreLogs.create(...)` and pushes the new match onto the navigation path.

`ScoreMatchModel` observes `rally.scoreLogs.logs`, finds this id, and publishes the log, its `state()` and its card. `ScoreMatchView` renders the same header and point list as Android's, with the same "No points scored yet." empty state.

- [ ] **Step 4: Regenerate, build and verify**

```bash
cd iosApp && xcodegen generate && cd ..
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS.

Then the same five hand checks as Android's Task 9, plus one this codebase has been bitten by before: tap the disabled share button on a score row inside the `List` and confirm it does not route the tap to the row's `NavigationLink`. `iosAppUITests` exists because a swatch grid did exactly that.

Screenshot the list on both platforms side by side and compare row heights and baselines directly. "Looks about right" is not the bar here.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Sources/Scoring/ iosApp/Sources/ClipList/ iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "feat(ios): create a match, list it beside video matches, open and delete it"
```

---

### Task 12: Close it out

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run everything**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO
```

Expected: PASS, everywhere. Report the actual output; do not summarise a run you did not do.

- [ ] **Step 2: Confirm the constraint that this plan turns on**

Re-run migration check 2 from Task 1 against the development project, now that the app writes real rows: bind a score log to a video, delete that video through the app's own swipe gesture, and confirm the match survives in the list with no video attached. This is the one behaviour that cannot be caught by any test in this plan and that L2 depends on completely.

- [ ] **Step 3: Write the changelog entry**

Add under the current unreleased heading, in the file's existing voice:

```markdown
- Matches can now be created on the phone before any video exists. Name a match,
  name its players, pick its scoring rules, and it appears in your match list
  alongside the matches cut from video. Scoring itself lands next.
```

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: note that a match can now exist before its video"
```

---

## What this leaves for L1b

- **The scoring surface**: landscape, one large tap target per side, serving side and service court from `MatchState`, undo, reset game, switch ends, score history, keep-awake.
- **Tagging while scoring**: the palette is `AnnotationLabelsRepository.labels`, already seeded, already cached per owner and readable cold and offline. One tap scores, a second tags, never a modal.
- **iOS orientation**: `iosApp/project.yml` declares `UISupportedInterfaceOrientations: [UIInterfaceOrientationPortrait]`. Allowing landscape app-wide would rotate every existing screen, so the scoring view needs a per-view orientation policy. Android has no such lock; its activity already declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`.
- **The tag tally and the text export**, both of which read `MatchState.points` and should share their sorting and rounding rules with `buildMatchLabelSummary` rather than restating them.
- **Writing through to the store**: the scoring surface calls `replaceEvents` after every tap, which is already the shape `undoLast` and `dropPointsFrom` produce. `finish` moves a completed match to `unbound`.
