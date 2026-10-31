# Player Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A coach creates players, says which of them is on each end of every match, and opens the app on a Players screen where each player has a Profile | Matches page.

**Architecture:** A `players` table owned by the coach, and `home_player_id` / `away_player_id` on `score_logs` and `videos` plus `near_side` on `videos`. The score log speaks for a match when one exists, otherwise the video, and a phone-only video keeps the same fields on its `LocalVideoEntry`. Every rule lives in `shared`: the assignment and its validation, the drafts the two editors edit, the save routing, the player filter and counts, the side names in the Analysis panels, the delete rewrite, and all copy. Android and iOS only render and dispatch.

**Tech Stack:** Postgres via Supabase migrations (SQL tests in a throwaway container), Kotlin Multiplatform `shared` (kotlinx.serialization 1.10, supabase-kt, kotest assertions, Turbine, MapSettings), Jetpack Compose (release resolves Compose Foundation **1.7.6**), SwiftUI with SKIE 0.10.13, XCTest.

**Spec:** `docs/plans/2026-10-31-player-profiles-design.md`

## Global Constraints

- Branch `feat/player-profiles`, stacked on `feat/match-moments` (PR #16). Never switch branches, push, or rebase.
- The app is not in production: no compatibility layer, no tolerant decoder, no data migration. Every new field on a `@Serializable` local type still gets a default, because `LocalVideoRepository.load()` and the score log cache swallow a decode failure into an empty store.
- Wire names are a schema: table `players(id, owner_id, name, racket_arm, created_at, updated_at)`; columns `home_player_id`, `away_player_id` on `score_logs` and `videos`; `near_side` on `videos`; `racket_arm` values `'left'`, `'right'`; `near_side` values `'home'`, `'away'`; RPC `list_match_metadata()` adds `home_player_id`, `away_player_id`, `near_side`, `home_names`, `away_names`.
- Singles only: a profile can sit on a side only when `format` is null or `singles`.
- A side is a profile **or** typed names, never both.
- `MAX_PLAYER_NAME = 40` (existing, `scoring/MatchDetails.kt:24`) is the player name cap.
- Every rule and every user-facing sentence comes from `shared` (`players/PlayersCopy.kt` or the existing copy objects). Copy, verbatim:
  - Players screen: title "Players", row "Add player", button "All matches", empty state "Add your first player" / "Each player gets a page with their matches. Matches you already have stay in All matches.", load failure "Couldn't load your players".
  - Player rows: "12 matches · last Oct 28", "1 match · last Oct 28", "No matches yet".
  - Player page: tabs "Profile", "Matches"; hand line "Right-handed" / "Left-handed" (absent when not set); tiles "Matches", "Rallies", "Last match" (value "None yet" when there is none); overflow "Rename", "Playing hand", "Delete player"; hand options "Right", "Left", "Not set".
  - Add / rename dialog: title "Add player" / "Rename player", field "Name", button "Save"; name problems "Enter a name." and "A player name can be up to 40 characters." (the second is the existing `matchDetailsProblem` sentence).
  - Delete: title "Delete {name}?", body "{n} matches stay in All matches, with the name still on them." / "1 match stays in All matches, with the name still on it." / "No matches are assigned to this player.", confirm "Delete".
  - Failures: "Couldn't save the player. Check your connection and try again.", "Couldn't delete the player. Check your connection and try again.", "Couldn't save who's playing. Check your connection and try again."
  - Who's playing: section title "Who's playing", toggle "One player" / "Both players", end labels "Far" / "Near", picker placeholder "Choose a player", picker last item "Add player", opponent field "Opponent (optional)", button "Swap ends", device line "Analysis on this phone follows only the near player. Cloud analysis follows both."
  - Details editor (score log): side rows "Home" / "Away" with a player picker whose last two items are "Someone else" (shows the name field) and "Add player"; ends row label "Home was at the" with pills "Near", "Far", "Not set" (only when a video is attached); doubles note "Remove the players to switch to doubles."
  - Attach question: title "Which end was {home} on?" (where `{home}` is the Home side's display name), subtitle "As the camera sees it.", buttons "Near", "Far", "Skip".
  - Analysis toggle: the side's display name; "Opponent" for a side with no name; "Near" / "Far" when `near_side` is unknown.
  - Upload in progress: "Who's playing can be changed once the upload finishes."
- Dates in player copy use the existing `formatMatchDate` short form (the list's own), made reachable from `players/` (it is `internal` in `matchlist/MatchListBuilder.kt:339`; same module, so no visibility change is needed).
- No em dashes in code comments, copy, or commit messages. Use " - ". No pronouns for a player in copy.
- Compose: nothing from Foundation 1.8+ (no `FlowRow(itemVerticalAlignment = ...)`). Build `assembleRelease` for every Android UI task.
- Swift sees no Kotlin default arguments: every shared function or constructor Swift calls has an overload or factory without defaults. Swift constructs `LocalVideoEntry` and `MatchListInput` with every argument spelled out, so new fields go **last**.
- iOS: no `.font(` anywhere outside `Theme/` and the `TypographyAdoptionTests` allowlist (comments included). A new Swift file needs `cd iosApp && xcodegen generate`; confirm its `.o` exists in DerivedData.
- New user-visible state on iOS routes must be added to `HomeView.nothingAbove` and `goToMatches()` (renamed in Task 12).
- Commits: no Co-Authored-By trailer.
- Every build or test command needs `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`; iOS also `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` and `xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null`.
- A background command's "exit code 0" can hide a failure: always grep the build's own verdict (`BUILD SUCCESSFUL`, `ok`/`FAIL`, `Executed ... 0 failures`).
- Migrations are committed, never applied. `supabase db push` is Coen's step.

### Commands

- SQL: Docker Desktop must be running (`open -a Docker`, then wait for `docker info` to succeed). `supabase/tests/run.sh` prints `ok    <name>` or `FAIL  <name>`.
- Shared: `./gradlew --offline :shared:jvmTest --tests "*<Class>*"` (drop `--offline` if dependencies are missing).
- Android unit: `./gradlew --offline :androidApp:testDebugUnitTest --tests "*<Class>*"`. Gate: `./gradlew --offline :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease 2>&1 | tail -20`.
- iOS build: `xcodebuild build-for-testing -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO | grep -E "error:|BUILD (SUCCEEDED|FAILED)"`.
- iOS unit tests (`xcodebuild test` hangs on this Mac, macOS 27.2 vs Xcode 26.6): boot the iPhone 17 Pro simulator, then

  ```bash
  P=$PWD/iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator
  PL=$DEVELOPER_DIR/Platforms/iPhoneSimulator.platform/Developer
  SIMCTL_CHILD_DYLD_FRAMEWORK_PATH="$P/iosApp.app/Frameworks:$P:$PL/Library/Frameworks:$PL/usr/lib:$P/iosApp.app" \
  SIMCTL_CHILD_DYLD_LIBRARY_PATH="$P/iosApp.app:$P:$PL/usr/lib:$PL/Library/Frameworks" \
  xcrun simctl spawn booted $PL/Library/Xcode/Agents/xctest -XCTest <Class> $P/iosApp.app/PlugIns/iosAppTests.xctest
  ```

  (drop `-XCTest <Class>` for the whole bundle). UI tests cannot run on this Mac; any UI test touched must compile and is listed for CI.

## Review Focus

1. **Clearing a value never reaches the server.** supabase-kt omits a property equal to its default, so a score log whose side goes from a profile back to typed names, or from names to `[]`, upserts without the cleared column, the server keeps the old value, and the new CHECK then refuses the whole batch, blocking every match's sync. Pinned in Task 3 (`the_upsert_sends_cleared_assignment_columns_explicitly`).
2. **Deleting a player with unsynced or un-uploaded matches.** The phone's cached score logs and local entries must take the name and lose the id, or the next sync fails on the dead FK. Pinned in Task 5 (`deleting_a_player_rewrites_cached_logs_and_entries`) and Task 1 (`deleting a player keeps the name on every side`).
3. **A match changed to doubles while a side is a profile.** The editor must refuse, and so must the database. Pinned in Task 4 (`doubles_with_a_profile_is_a_problem`) and Task 1 (`refuses doubles with a profile`).
4. **A phone-only or still-processing match must be assignable**, and a match mid-upload must say why it is not. Pinned in Task 6 (`players_target_*` tests).
5. **A share recipient sees names, not blanks**, including on a score-born match whose names live on the score log. Pinned in Task 1 (`metadata resolves names for a recipient through the bound score log`).

---

## File structure

**SQL (`supabase/`)**

| File | Change | Responsibility |
|---|---|---|
| `migrations/20261031000000_player_profiles.sql` | create | `players`, the assignment columns, checks, triggers, grant, `list_match_metadata` |
| `tests/players_test.sql` | create | everything above, against a throwaway database |

**shared (`shared/src/commonMain/kotlin/com/badmintontracker/shared/`)**

| File | Change | Responsibility |
|---|---|---|
| `players/Player.kt` | create | `Player`, `playerNameProblem`, `initials`, `sortedPlayers` |
| `players/PlayersRepository.kt` | create | interface + `PlayersRepositoryImpl`, owner-scoped cache |
| `players/MatchPlayers.kt` | create | `MatchPlayers`, `matchPlayersProblem`, `forgetting`, display names |
| `players/PlayersDraft.kt` | create | the editors' draft: by end and by side |
| `players/SaveMatchPlayers.kt` | create | `PlayersTarget`, `playersTarget`, `saveMatchPlayersOrMessage` |
| `players/DeletePlayer.kt` | create | `deletePlayerOrMessage` |
| `players/PlayerMatches.kt` | create | `forPlayer`, `PlayerSummary`, `playerSummary`, row detail |
| `players/PlayersCopy.kt` | create | every sentence above |
| `analytics/CourtSideNames.kt` | create | `CourtSideNames`, `courtSideNames`, toggle labels, profile arm |
| `prefs/RacketArmPreferenceRepository.kt` | modify | `@Serializable` `RacketArm` with serial names |
| `scoring/ScoreLog.kt` | modify | `homePlayerId`, `awayPlayerId`; `@EncodeDefault` on the assignment and format |
| `scoring/ScoreLogsRepository.kt` | modify | `assignPlayers`, `forgetPlayer` |
| `localvideo/LocalVideoEntry.kt` | modify | five new trailing fields |
| `localvideo/LocalVideoRepository.kt` | modify | `setMatchPlayers`, `forgetPlayer` |
| `localvideo/AnalyzeCoordinator.kt` | modify | pass the assignment to `createVideo` |
| `repo/VideosRepository.kt` | modify | `createVideo` carries the assignment; `updateMatchPlayers` |
| `model/MatchMetadata.kt` | modify | ids, `nearSide`, resolved names |
| `matchlist/MatchListInput.kt`, `MatchListItem.kt`, `MatchListBuilder.kt` | modify | `players` input; item ids, `nearSide`, `canEditPlayers`; names resolved |
| `RallyApp.kt` | modify | `val players` |
| iosMain `SwiftInterop.kt`, `testing/PlayersTestDoubles.kt` | modify / create | Swift entry points and doubles |

**Android (`androidApp/src/main/java/com/badmintontracker/android/`)**: `nav/Route.kt`, `nav/AuthNavigation.kt`, `AuthGate.kt`, new `players/PlayersScreen.kt`, `players/PlayersViewModel.kt`, `players/PlayerScreen.kt`, `players/PlayerDialogs.kt`, `players/PlayerAvatar.kt`, `players/WhoIsPlayingSection.kt`, `players/HomeEndDialog.kt`; modified `home/HomeScreen.kt`, `cliplist/ClipListViewModel.kt`, `cliplist/ClipListScreen.kt`, `localvideo/MatchDetailsSheet.kt`, `localvideo/VideoIntake.kt`, `scoring/EditMatchDetailsDialog.kt`, `scoring/NewMatchViewModel.kt`, `match/MatchScreen.kt`, `match/MatchViewModel.kt`, `match/AnalysisSection.kt`, `localanalysis/HeatmapPanel.kt`, `BasePositionPanel.kt`, `SkeletonPanel.kt`, `MetricsStrip.kt`.

**iOS (`iosApp/Sources/`)**: new `Players/PlayersScreen.swift`, `Players/PlayersModel.swift`, `Players/PlayerView.swift`, `Players/PlayerDialogs.swift`, `Players/PlayerAvatar.swift`, `Players/WhoIsPlayingSection.swift`, `Players/HomeEndPrompt.swift`; modified `Home/HomeView.swift`, `Home/MatchesScreen.swift`, `ClipList/ClipListModel.swift`, `ClipList/ClipListView.swift`, `LocalVideo/MatchDetailsSheet.swift`, `LocalVideo/LocalVideoIntake.swift`, `Scoring/EditMatchDetailsSheet.swift`, `Scoring/NewMatchView.swift`, `Match/MatchView.swift`, `Match/MatchModel.swift`, `Match/AnalysisSection.swift`, `LocalAnalysis/SkeletonPanel.swift`, `LocalAnalysis/BasePositionPanel.swift`.

---

### Task 1: The database

**Files:**
- Create: `supabase/migrations/20261031000000_player_profiles.sql`
- Create: `supabase/tests/players_test.sql`

**Interfaces:**
- Produces: the wire names in Global Constraints. `list_match_metadata()` returns `(video_id uuid, title text, description text, home_players text[], away_players text[], format text, home_player_id uuid, away_player_id uuid, near_side text, home_names text[], away_names text[])`.

House style is `20260922000000_video_match_details.sql`: header comment with `See docs/plans/2026-10-31-player-profiles-design.md`, idempotent statements, constraints guarded by `pg_constraint` lookups, `drop policy if exists` before `create policy`.

- [ ] **Step 1: Write `supabase/tests/players_test.sql`** in the shape of `video_match_details_test.sql` (two users `...0001` owner, `...0002` other; `pg_temp.expect_refused`, `pg_temp.expect_denied`, `pg_temp.act_as`; `begin` ... `rollback`). Add `pg_temp.expect_raised(label, stmt)` that swallows `raise_exception` (SQLSTATE `P0001`) for the trigger refusals. Cases, each a labelled block:
  1. `players are owner-only`: the owner inserts `Marco` (no `owner_id` given, the default fills it); the other user selects zero rows, and updates and deletes match zero rows.
  2. `refuses an empty or long name`: `''`, `'   '` and a 41-character name are refused.
  3. `refuses an unknown hand`: `racket_arm = 'both'` refused; `'left'`, `'right'`, null accepted.
  4. `refuses names and a profile on one side`: a score log with `home_player_id` set and `home_players = '{Marco}'` is refused; the same on `videos`.
  5. `refuses doubles with a profile`: `format = 'doubles'` with `away_player_id` set is refused on both tables; `format = 'singles'` and `format = null` are accepted.
  6. `refuses another coach's player`: the other user creates `Tom`; the owner's score log and video naming Tom's id are refused (`expect_raised`), on insert and on update.
  7. `the owner can write the new video columns`: as owner, `update videos set home_player_id = <Marco>, near_side = 'home'` lands (row count 1, values read back).
  8. `a stranger's update writes nothing`: as other, the same update matches zero rows.
  9. `near_side is checked`: `near_side = 'left'` refused.
  10. `deleting a player keeps the name on every side`: Marco is home on a score log and away on a video; delete Marco; both rows now have the id null and `'{Marco}'` in the side's names.
  11. `metadata resolves names`: owner's video with `home_player_id = Marco` and typed `away_players = '{Jip}'` returns `home_names = '{Marco}'`, `away_names = '{Jip}'`, and the ids and `near_side` as stored.
  12. `metadata resolves names for a recipient through the bound score log`: owner's video with no assignment, a score log bound to it (`video_id`) with Marco home; share the video with the other user (insert into `match_shares` as `supabase_admin` before switching role); as other, `list_match_metadata()` returns that video with `home_names = '{Marco}'`.

- [ ] **Step 2: Run it and see it fail**

Run: `supabase/tests/run.sh`
Expected: `FAIL  players_test` (relation "players" does not exist); the three existing tests `ok`.

- [ ] **Step 3: Write the migration**, in this order:
  1. `players` per design section 3.1: `name` checked as `char_length(btrim(name)) between 1 and 40`; `racket_arm text check (racket_arm in ('left','right'))`; `created_at`, `updated_at timestamptz not null default now()`; non-unique index `players_owner_name_idx on (owner_id, lower(name))`; RLS enabled; policies `players_select_own`, `players_insert_own`, `players_update_own`, `players_delete_own` (`to authenticated`, `owner_id = auth.uid()`, update with `using` and `with check`); a `touch_players_updated_at()` trigger with the body of `touch_score_logs_updated_at()`.
  2. On `score_logs` and `videos`: `home_player_id`, `away_player_id uuid references public.players(id) on delete set null`; on `videos` only `near_side text`. Constraints `<table>_home_profile_or_names_check` (`home_player_id is null or cardinality(home_players) = 0`), the same for away, `<table>_profiles_singles_check` (`(home_player_id is null and away_player_id is null) or format is null or format = 'singles'`), `videos_near_side_check`.
  3. `public.check_match_players_owner()` (plpgsql, `security definer`, `set search_path = public`): for each non-null id, `raise exception 'player % belongs to another coach', id` unless `exists (select 1 from players p where p.id = id and p.owner_id = new.owner_id)`. Triggers `score_logs_players_owner` and `videos_players_owner`, `before insert or update of home_player_id, away_player_id, owner_id`.
  4. `public.forget_player_on_delete()` (plpgsql, `security definer`, `before delete on players`): `update score_logs set home_players = array[old.name], home_player_id = null where home_player_id = old.id`, the same for away, the same four on `videos`. Both changes in one UPDATE per side, so the profile-or-names check holds.
  5. `grant update (home_player_id, away_player_id, near_side) on public.videos to authenticated;` (INSERT has no column grant in either repo; nothing to add).
  6. `drop function if exists public.list_match_metadata();` then recreate with the columns above, the same `where` clause as today, and names resolved by a `left join lateral` on the bound score log (`select ... from score_logs s where s.video_id = v.id order by s.created_at limit 1`): `home_names` is the score log's resolution when it exists, otherwise the video's, where a side's resolution is `array[p.name]` when its id is set, else its `home_players`. Keep `revoke all ... from public; grant execute ... to authenticated;` and the comment that this is one of the five copies of "own or shared".

- [ ] **Step 4: Run the tests**

Run: `supabase/tests/run.sh`
Expected: `ok    players_test`, and `ok` for the three existing tests.

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations/20261031000000_player_profiles.sql supabase/tests/players_test.sql
git commit -m "feat(db): players owned by the coach, who is on each side of a match, and names resolved for the list"
```

---

### Task 2: Players in shared

**Files:**
- Create: `shared/.../players/Player.kt`, `shared/.../players/PlayersRepository.kt`, `shared/.../players/PlayersCopy.kt`
- Modify: `shared/.../prefs/RacketArmPreferenceRepository.kt`, `shared/.../RallyApp.kt`
- Create: `shared/src/iosMain/.../testing/PlayersTestDoubles.kt`
- Modify: `shared/src/iosMain/.../SwiftInterop.kt`
- Create: `androidApp/src/test/java/com/badmintontracker/android/testing/FakePlayersRepository.kt`
- Test: `shared/src/commonTest/.../players/PlayerTest.kt`, `shared/src/commonTest/.../players/PlayersRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable enum class RacketArm { @SerialName("left") LEFT, @SerialName("right") RIGHT }` (prefs keep storing `arm.name`).
  - `@Serializable data class Player(val id: String, @SerialName("owner_id") val ownerId: String, val name: String, @SerialName("racket_arm") val racketArm: RacketArm? = null, @SerialName("created_at") val createdAt: Instant, @SerialName("updated_at") val updatedAt: Instant)`
  - `fun playerNameProblem(name: String): String?` ("Enter a name." for blank after trim, the 40-character sentence above it).
  - `fun initials(name: String): String`: uppercase first letter of the first word plus first letter of the last word when there are two or more words ("Marco Vidal" -> "MV", "Marco" -> "M", "  " -> "").
  - `fun sortedPlayers(players: List<Player>): List<Player>`: by `name.lowercase()`, then `id`.
  - `interface PlayersRepository { val players: StateFlow<List<Player>>; suspend fun refresh(): Result<Unit>; suspend fun create(name: String, racketArm: RacketArm?): Result<Player>; suspend fun rename(id: String, name: String): Result<Unit>; suspend fun setRacketArm(id: String, racketArm: RacketArm?): Result<Unit>; suspend fun delete(id: String): Result<Unit> }`
  - `class PlayersRepositoryImpl internal constructor(client: SupabaseClient, settings: Settings, awaitAuthReady: suspend () -> Unit)` plus `constructor(client, settings)`; cache key `"players_cache"`, envelope `CachedPlayers(@SerialName("owner_id") ownerId: String?, players: List<Player>)`.
  - `RallyApp.players: PlayersRepository`.
  - `object PlayersCopy` with a `const val` (or a function for the templated ones) per sentence in Global Constraints.
  - Swift: `SwiftInteropKt.refreshPlayersOrMessage(repo)`, `createPlayerForSwift(repo, name, racketArm) -> CreatePlayerOutcome(player: Player?, errorMessage: String?)`, `renamePlayerOrMessage`, `setPlayerRacketArmOrMessage`; test factory `testPlayersRepository(players: List<Player>): PlayersRepository`.
  - Android test fake `FakePlayersRepository(initial: List<Player> = emptyList())` recording `created`, `renamed`, `deleted`, with `nextError: Throwable?`.

Mirror `AnnotationLabelsRepositoryImpl` throughout: the auth-restore reconciliation job, `publish(next)`, owner-checked `loadCache()`, validation before the round trip, sorted state (`sortedPlayers`). Refresh is `select { order("created_at", Order.ASCENDING) }`; create is `insert(NewPlayerRow(name.trim(), racketArm)) { select() }.decodeSingle()`; rename and hand are `update(...) { filter { eq("id", id) } }`; delete is `delete { filter { eq("id", id) } }`, publishing the list without the player on success. The hand patch must send `"racket_arm":null` when clearing (use `@EncodeDefault` or a non-default property).

- [ ] **Step 1: Write the failing tests.** `PlayerTest`: `initials_take_the_first_and_last_word`, `initials_of_one_word_is_one_letter`, `a_blank_name_is_a_problem`, `a_41_character_name_is_a_problem`, `players_sort_by_name_ignoring_case`. `PlayersRepositoryTest`, on the `AnnotationLabelsRepositoryTest` pattern (`TestSupabase.client`, `signInAs`, handler by path): `refresh_publishes_players_sorted_by_name`, `the_cache_survives_a_cold_start_offline`, `another_owner_never_sees_the_cache` (two clients over one `MapSettings`), `create_refuses_a_blank_name_without_a_request`, `create_sends_the_trimmed_name_and_hand`, `clearing_the_hand_sends_null` (body contains `"racket_arm":null`), `delete_drops_the_player_from_the_list`.

- [ ] **Step 2: Run them, expect compile failures.** `./gradlew --offline :shared:jvmTest --tests "*Player*"`.

- [ ] **Step 3: Implement** the produced interfaces above, wire `val players: PlayersRepository = PlayersRepositoryImpl(client, settings)` beside `labels` in `RallyApp.kt`, the Swift entry points (the `...OrMessage` pattern in `SwiftInterop.kt`), the iOS test factory, and the Android fake.

- [ ] **Step 4: Run** `./gradlew --offline :shared:jvmTest --tests "*Player*"` (PASS), then `./gradlew --offline :shared:jvmTest :androidApp:testDebugUnitTest` (BUILD SUCCESSFUL).

- [ ] **Step 5: Commit** `feat(shared): players repository with an owner-scoped cache`.

---

### Task 3: The assignment on score logs, local entries and videos

**Files:**
- Create: `shared/.../players/MatchPlayers.kt`
- Modify: `scoring/ScoreLog.kt`, `scoring/ScoreLogsRepository.kt`, `localvideo/LocalVideoEntry.kt`, `localvideo/LocalVideoRepository.kt`, `localvideo/AnalyzeCoordinator.kt`, `repo/VideosRepository.kt`, `model/MatchMetadata.kt`, the three `FakeVideosRepository`/`NoopVideosRepository` doubles, Swift `LocalVideoEntry(...)` call sites (`LocalVideoIntake.swift:61-76`, `LocalVideoCleanupWiringTests.swift`, `MatchModelTests.swift`, `RowSwipeTests.swift`)
- Test: `players/MatchPlayersTest.kt`, extend `scoring/ScoreLogsRepositoryTest.kt`, `repo/VideosRepositoryTest.kt`, `localvideo/LocalVideoEntrySerializationTest.kt`, `model/MatchMetadataSerializationTest.kt`

**Interfaces:**
- Produces:
  - `data class MatchPlayers(val homePlayerId: String?, val awayPlayerId: String?, val homePlayers: List<String>, val awayPlayers: List<String>)` with `companion val NONE`.
  - `val ScoreLog.matchPlayers: MatchPlayers`, `val LocalVideoEntry.matchPlayers: MatchPlayers`, `val MatchMetadata.matchPlayers: MatchPlayers`.
  - `fun MatchPlayers.forgetting(playerId: String, name: String): MatchPlayers` (the side holding the id takes `listOf(name)` and loses the id).
  - `fun MatchPlayers.names(side: Side, players: List<Player>): List<String>` (profile name when the id resolves, otherwise the typed names; an id that does not resolve yields `emptyList()`).
  - `ScoreLog` gains `@SerialName("home_player_id") val homePlayerId: String? = null`, `@SerialName("away_player_id") val awayPlayerId: String? = null`; these two plus `homePlayers`, `awayPlayers`, `format` carry `@EncodeDefault`.
  - `LocalVideoEntry` gains, last and defaulted: `homePlayerId: String? = null`, `awayPlayerId: String? = null`, `homePlayers: List<String> = emptyList()`, `awayPlayers: List<String> = emptyList()`, `nearSide: Side? = null`.
  - `ScoreLogsRepository.assignPlayers(id: String, players: MatchPlayers)` (through `edit`, dirty), `ScoreLogsRepository.forgetPlayer(playerId: String, name: String)` (every cached log naming it, dirty).
  - `LocalVideoRepository.setMatchPlayers(id: String, players: MatchPlayers, nearSide: Side?)` (no stage check; the caller routes), `LocalVideoRepository.forgetPlayer(playerId: String, name: String)`.
  - `VideosRepository.createVideo(videoId, filename, sizeBytes, title, description, players: MatchPlayers, nearSide: Side?)`: the insert row gains the five columns.
  - `VideosRepository.updateMatchPlayers(videoId: String, players: MatchPlayers, nearSide: Side?): Result<Unit>`: patch of the five columns, every one sent even when null or empty; updates the metadata cache on success.
  - `MatchMetadata` gains defaulted `homePlayerId`, `awayPlayerId`, `nearSide: Side?`, `homeNames: List<String>`, `awayNames: List<String>` with the RPC's serial names.
  - `AnalyzeCoordinator` passes `entry.matchPlayers` and `entry.nearSide` to `createVideo`.

- [ ] **Step 1: Write the failing tests.**
  - `MatchPlayersTest`: `forgetting_moves_the_name_onto_the_side_and_clears_the_id`, `forgetting_another_player_changes_nothing`, `names_resolve_a_profile_and_keep_typed_names`, `an_unknown_profile_has_no_names`.
  - `ScoreLogsRepositoryTest`: `the_upsert_sends_cleared_assignment_columns_explicitly` (assign Marco home, sync, assign typed `["Jo"]` with no id, sync; the second POST body contains `"home_player_id":null` and `"away_players":[]`). This test must push through a **real** supabase-kt client with Postgrest installed (`TestSupabase.client` over a `MockEngine` handler that echoes the POST, as `FakeScoreLogsServer` does), because the omission it guards against is supabase-kt's own serializer (its default `Json` sets only `ignoreUnknownKeys`); a `client = null` repository never encodes through it. Also `forget_player_rewrites_and_dirties_every_cached_log`.
  - `VideosRepositoryTest`: `createVideo_writes_the_assignment_on_the_insert`, `updateMatchPlayers_sends_every_column_even_when_cleared`.
  - `LocalVideoEntrySerializationTest`: `an_entry_without_the_assignment_decodes_with_none`.
  - `MatchMetadataSerializationTest`: decodes a row with the five new keys; decodes a row without them.

- [ ] **Step 2: Run, expect failures.** `./gradlew --offline :shared:jvmTest --tests "*MatchPlayers*" --tests "*ScoreLogsRepository*" --tests "*VideosRepository*" --tests "*Serialization*"`.

- [ ] **Step 3: Implement** the produced interfaces. Update every `createVideo` implementer and the Swift `LocalVideoEntry(...)` constructions (append `homePlayerId: nil, awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil`).

- [ ] **Step 4: Run** the focused tests (PASS), then `./gradlew --offline :shared:jvmTest :androidApp:testDebugUnitTest` and the iOS build command (`BUILD SUCCEEDED`).

- [ ] **Step 5: Commit** `feat(shared): who is on each side, on score logs, local entries and videos`.

---

### Task 4: Validation and the editors' draft

**Files:**
- Modify: `players/MatchPlayers.kt`
- Create: `players/PlayersDraft.kt`
- Test: `players/PlayersDraftTest.kt`, extend `players/MatchPlayersTest.kt`

**Interfaces:**
- Consumes: `MatchPlayers`, `Player`, `Side`, `MatchFormat`, `CourtSide` (analysis module).
- Produces:
  - `fun matchPlayersProblem(players: MatchPlayers, format: MatchFormat?): String?`: a profile with typed names on one side, or a profile with doubles or mixed, returns "Remove the players to switch to doubles." for the doubles case; a typed name over 40 returns the existing sentence; else null.
  - `enum class Tracked { ONE, BOTH }`
  - `data class EndsDraft(val tracked: Tracked, val nearPlayerId: String?, val farPlayerId: String?, val opponentName: String, val playerAtNear: Boolean)`: the Who's playing section (a match born as a video). In ONE mode the coach's player is at the near end when `playerAtNear`, the far end otherwise, and the other end is `opponentName`; in BOTH mode both ends are profiles.
  - `fun endsDraft(players: MatchPlayers, nearSide: Side?, prefillPlayerId: String?): EndsDraft`: reads an existing assignment back (BOTH when both sides are profiles); a blank match with `prefillPlayerId` is ONE with that player at near.
  - `fun EndsDraft.swapped(): EndsDraft`, `fun EndsDraft.withTracked(t: Tracked): EndsDraft` (BOTH keeps the near player and leaves far empty; ONE keeps whichever end held a profile).
  - `fun EndsDraft.toAssignment(): Pair<MatchPlayers, Side?>`: home is the near end, away the far end, `nearSide = Side.HOME`; a draft with no profile and no name returns `MatchPlayers.NONE to null`.
  - `fun EndsDraft.showsDeviceLine(): Boolean`: true when a profile is at the far end.
  - `data class SidesDraft(val homePlayerId: String?, val awayPlayerId: String?, val homeNames: List<String>, val awayNames: List<String>, val nearSide: Side?)`: the details editor of a score log. `fun sidesDraft(log: ScoreLog, nearSide: Side?): SidesDraft`, `fun SidesDraft.toMatchPlayers(format: MatchFormat?): MatchPlayers` (names through `submittedPlayers`, emptied on a side that holds a profile).
  - `fun asksForHomeEnd(log: ScoreLog): Boolean` (either side is a profile).
  - `fun homeEndTitle(log: ScoreLog, players: List<Player>): String` ("Which end was {home} on?", `{home}` from `names(Side.HOME, players)` joined with " / ", or "Home").

- [ ] **Step 1: Write the failing tests** in `PlayersDraftTest`: `a_prefilled_player_starts_at_near_in_one_mode`, `swapping_moves_the_player_to_far`, `one_mode_saves_the_player_and_the_opponent_by_end`, `both_mode_saves_two_profiles_home_near`, `an_existing_assignment_reads_back_as_both`, `a_far_profile_shows_the_device_line`, `an_empty_draft_saves_nothing`, `a_side_with_a_profile_saves_no_names`, `asks_for_the_home_end_only_with_a_profile`, `the_home_end_title_names_the_home_side`. In `MatchPlayersTest`: `doubles_with_a_profile_is_a_problem`, `names_and_a_profile_on_one_side_is_a_problem`.
- [ ] **Step 2: Run, expect failures.** `./gradlew --offline :shared:jvmTest --tests "*PlayersDraft*" --tests "*MatchPlayers*"`.
- [ ] **Step 3: Implement.** Every function Swift calls gets a no-default form.
- [ ] **Step 4: Run** the same command: PASS.
- [ ] **Step 5: Commit** `feat(shared): the who's playing and details drafts, with singles and one-source rules`.

---

### Task 5: Saving and deleting

**Files:**
- Create: `players/SaveMatchPlayers.kt`, `players/DeletePlayer.kt`
- Test: `players/SaveMatchPlayersTest.kt`, `players/DeletePlayerTest.kt`

**Interfaces:**
- Consumes: Task 3's repository methods, Task 4's `matchPlayersProblem`.
- Produces:
  - `enum class PlayersTarget { SCORE_LOG, LOCAL_ENTRY, VIDEO_ROW, UPLOADING }`
  - `fun playersTarget(scoreLogId: String?, entry: LocalVideoEntry?, videoId: String?): PlayersTarget?`: `SCORE_LOG` when `scoreLogId != null`; `LOCAL_ENTRY` when the entry has no videos row yet (stage `LOCAL`, or `FAILED` with `failedStep` `UPLOAD` or `CREATE_ROW`); `UPLOADING` when stage is `UPLOADING`; `VIDEO_ROW` when `videoId != null` otherwise; null when there is nothing to write to.
  - `suspend fun saveMatchPlayersOrMessage(target: PlayersTarget, scoreLogs: ScoreLogsRepository, videos: VideosRepository, localVideos: LocalVideoRepository, scoreLogId: String?, videoId: String?, entryId: String?, players: MatchPlayers, nearSide: Side?, format: MatchFormat?): String?`: validates with `matchPlayersProblem` first; `SCORE_LOG` calls `assignPlayers`, then routes `nearSide` (with `MatchPlayers.NONE`, since the log speaks for the players) by the **video's** own target, exactly as a video-born match: `playersTarget(null, entry, videoId)` is `LOCAL_ENTRY` -> `setMatchPlayers` on the entry (it rides on the INSERT), `VIDEO_ROW` -> `updateMatchPlayers` on the row then mirror to the entry, `UPLOADING` -> the upload sentence after the log is saved, null (no video) -> nothing; `LOCAL_ENTRY` calls `setMatchPlayers`; `VIDEO_ROW` calls `updateMatchPlayers` and, on success, mirrors to the entry when one exists; `UPLOADING` returns the upload sentence. Returns null on success or the sentence to show.
  - `suspend fun deletePlayerOrMessage(players: PlayersRepository, scoreLogs: ScoreLogsRepository, localVideos: LocalVideoRepository, player: Player): String?`: server delete first; on success `scoreLogs.forgetPlayer` and `localVideos.forgetPlayer`, then return null; on failure return the delete sentence and change nothing locally. (Deliberate change from design section 3.2 rule 4's "before the delete is sent": a sync that runs in the instant between the two hits the dead FK once and succeeds on the next sync, while rewriting first would unassign matches on a delete that then fails. Update that sentence in the design doc in this task's commit.)

  - `fun forgetVanishedPlayers(before: List<Player>, after: List<Player>, scoreLogs: ScoreLogsRepository, localVideos: LocalVideoRepository)`: every player in `before` and not in `after` is forgotten with its cached name. Called after every successful `PlayersRepository.refresh()` by the list's owner (`ClipListViewModel.refresh()` and `ClipListModel.refresh()`, Task 6), so a player deleted on another device of the same coach cannot leave this phone's cached logs or un-uploaded entries pointing at a dead id, and the same-phone window of the server-first delete closes on the next refresh. `before` is the repository's state read just before the refresh.

- [ ] **Step 1: Write the failing tests.** `SaveMatchPlayersTest` (repositories over `MapSettings`, `FakeVideosRepository`): `players_target_prefers_the_score_log`, `players_target_is_the_entry_before_the_row_exists`, `players_target_is_the_entry_after_a_failed_upload`, `players_target_waits_during_the_upload`, `players_target_is_the_row_once_uploaded`, `a_problem_saves_nothing`, `saving_to_a_score_log_dirties_it`, `saving_the_home_end_of_a_scored_match_writes_the_entry_before_upload`, `saving_the_home_end_of_a_scored_match_with_an_uploaded_video_writes_the_row`, `saving_to_a_row_mirrors_the_entry`, `a_refused_row_save_leaves_the_entry_alone`. `DeletePlayerTest`: `deleting_a_player_rewrites_cached_logs_and_entries`, `a_failed_delete_rewrites_nothing`, `a_player_missing_after_refresh_is_forgotten_in_cached_logs_and_entries`.
- [ ] **Step 2: Run, expect failures.** `./gradlew --offline :shared:jvmTest --tests "*SaveMatchPlayers*" --tests "*DeletePlayer*"`.
- [ ] **Step 3: Implement**, plus Swift entry points `SwiftInteropKt.saveMatchPlayersOrMessage(...)` and `deletePlayerOrMessage(...)` if the shared `suspend fun` shapes do not bridge directly (they return `String?`, so they should).
- [ ] **Step 4: Run** the same command: PASS.
- [ ] **Step 5: Commit** `feat(shared): one save path for who's playing, and a delete that keeps the name`.

---

### Task 6: The match list knows its players

**Files:**
- Modify: `matchlist/MatchListInput.kt`, `matchlist/MatchListItem.kt`, `matchlist/MatchListBuilder.kt`
- Create: `players/PlayerMatches.kt`
- Modify every `MatchListInput(...)` construction: `ClipListViewModel.kt`, `ClipListModel.swift`, and the tests listed in the data-layer reference (`MatchListBuilderTest`, `MatchListFilterTest`, `MatchListSortTest`, `MatchListCopyTest`, `MatchListConfirmationTest`, `MatchPageSectionTest`, Android `RowGesturesTest`, Swift `MatchListBridgingTests`, `RowSwipeTests`)
- Test: extend `matchlist/MatchListBuilderTest.kt`; create `players/PlayerMatchesTest.kt`

**Interfaces:**
- Produces:
  - `MatchListInput.players: List<Player>`, appended last.
  - `MatchListItem` gains `homePlayerId: String?`, `awayPlayerId: String?`, `homeNames: List<String>`, `awayNames: List<String>` (each side's resolved display names, the same resolution `playersLine` uses), `nearSide: Side?`, `playersTarget: PlayersTarget?`, `canEditPlayers: Boolean` (`isOwned && playersTarget != null`).
  - `playersLine` resolves through `MatchPlayers.names` and `input.players` for every owned item: a score item from its log; a video item from the entry when there is no cloud metadata, otherwise from the metadata's ids and typed names, falling back to `homeNames` / `awayNames` when an id does not resolve (a shared match always takes `homeNames` / `awayNames`).
  - `nearSide`, for video and score items alike: the joined entry's when present, else the metadata of the item's `videoId`.
  - `fun MatchList.forPlayer(playerId: String): List<MatchListItem>` (owned items naming it on either side; shared items never).
  - `data class PlayerSummary(val matchCount: Int, val rallyCount: Int, val lastMatchAtEpochMs: Long?)`, `fun playerSummary(list: MatchList, playerId: String): PlayerSummary`.
  - `fun playerRowDetail(summary: PlayerSummary): String` and `fun lastMatchTile(summary: PlayerSummary): String` (system time zone, plus `internal` overloads taking `TimeZone` for tests), producing the Global Constraints copy.

- [ ] **Step 1: Write the failing tests.** `MatchListBuilderTest`: `a_score_match_names_its_profile`, `a_phone_only_match_shows_its_players`, `a_video_match_names_its_profile_from_metadata`, `a_shared_match_takes_resolved_names`, `a_renamed_player_renames_every_match`, `players_target_is_set_per_kind`, `a_shared_match_cannot_edit_players`. `PlayerMatchesTest`: `for_player_keeps_matches_naming_the_player_on_either_side`, `for_player_skips_shared_matches`, `the_summary_counts_matches_rallies_and_the_last_date`, `a_player_with_no_matches_reads_no_matches_yet`, `one_match_is_singular`.
- [ ] **Step 2: Run, expect failures.** `./gradlew --offline :shared:jvmTest --tests "*MatchListBuilder*" --tests "*PlayerMatches*"`.
- [ ] **Step 3: Implement**; feed `players.players` into `ClipListViewModel` (combine it into `cloud` or the outer `combine`; `refresh()` also calls `players.refresh()`, failures soft like the others, and on success `forgetVanishedPlayers(before, after, ...)` from Task 5) and into `ClipListModel.swift` (a seventh child in `observe()`, a `players` init parameter defaulting to `rally.players`, and `refresh()` calls `SwiftInteropKt.refreshPlayersOrMessage` followed by `forgetVanishedPlayers` on success). Update every construction site.
- [ ] **Step 4: Run** `./gradlew --offline :shared:jvmTest :androidApp:testDebugUnitTest`, the iOS build, and the iOS `ClipListModelTests` and `MatchListBridgingTests`. All pass.
- [ ] **Step 5: Commit** `feat(shared): the match list resolves players, and filters and counts per player`.

---

### Task 7: Side names in the Analysis panels (shared)

**Files:**
- Create: `analytics/CourtSideNames.kt`
- Test: `analytics/CourtSideNamesTest.kt`

**Interfaces:**
- Produces:
  - `data class CourtSideNames(val near: String, val far: String, val nearArm: RacketArm?, val farArm: RacketArm?)`
  - `fun courtSideNames(item: MatchListItem, players: List<Player>): CourtSideNames?`: null when `item.nearSide` is null. A side's name is `item.homeNames` / `awayNames` joined with " / ", or "Opponent" when empty. A side's arm is its profile's `racketArm`, looked up by `item.homePlayerId` / `awayPlayerId` (null for typed names or no hand).
  - `fun sideToggleLabel(side: CourtSide, names: CourtSideNames?): String` ("Near"/"Far" when `names` is null).
  - `fun profileArm(side: CourtSide, names: CourtSideNames?): RacketArm?`
- [ ] **Step 1: Write the failing tests**: `unknown_ends_keep_near_and_far`, `home_near_names_the_ends`, `home_far_swaps_them`, `an_empty_side_is_the_opponent`, `a_profile_hand_is_the_sides_arm`, `typed_names_have_no_arm`. Two of them must go through `buildMatchList` -> item -> `courtSideNames`, not a hand-made item, because `nearSide` reaches the item from different places: `a_scored_match_with_a_phone_video_takes_the_end_from_the_entry` and `a_cloud_video_takes_the_end_from_its_metadata`.
- [ ] **Step 2: Run, expect failures.** `./gradlew --offline :shared:jvmTest --tests "*CourtSideNames*"`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run**: PASS.
- [ ] **Step 5: Commit** `feat(shared): name the near and far player when the ends are known`.

---

### Task 8: Android, Players as the home screen

**Files:**
- Modify: `nav/Route.kt`, `nav/AuthNavigation.kt`, `AuthGate.kt`, `home/HomeScreen.kt`, `cliplist/ClipListScreen.kt`
- Create: `players/PlayersScreen.kt`, `players/PlayersViewModel.kt`, `players/PlayerDialogs.kt`, `players/PlayerAvatar.kt`, `localvideo/AutoMatchDetailsHost.kt`
- Test: `nav/AuthNavigationTest.kt`, create `players/PlayersViewModelTest.kt`

**Interfaces:**
- Consumes: `RallyApp.players`, `sortedPlayers`, `initials`, `playerSummary`, `playerRowDetail`, `PlayersCopy`.
- Produces:
  - `Route.Players` (data object, the start destination), `Route.Player(val playerId: String)`, `Route.AllMatches` (the old `Route.Home`, renamed everywhere), `Route.NewMatch(val playerId: String? = null)` (now a data class).
  - `class PlayersViewModel(players: PlayersRepository, scoreLogs: ScoreLogsRepository, localVideos: LocalVideoRepository)` with `state: StateFlow<PlayersUiState(players: List<Player>, loadFailed: Boolean, errorMessage: String?)>`, `refresh()`, `suspend fun create(name, arm): Player?`, `rename(id, name)`, `setRacketArm(id, arm)`, `delete(player)` (through `deletePlayerOrMessage`), `errorShown()`; modelled on `LabelsViewModel`.
  - `@Composable fun PlayerAvatar(name: String, size: Dp)`: a circle in `sideHome` with `initials(name)` in the accent, sized 40dp on rows and 60dp on the profile.
  - `@Composable fun PlayerNameDialog(title: String, initialName: String, initialArm: RacketArm?, showArm: Boolean, onDismiss, onSave: (String, RacketArm?) -> Unit)` for Add (with hand pills "Right", "Left", "Not set") and Rename (name only).
  - `@Composable fun AutoMatchDetailsHost(...)`: the auto-opened details sheet after Record and Import, moved out of `ClipListScreen` to sit above the `NavHost`, so it shows over Players, a Player page or All matches (`AuthGate.kt:166-169` comment updated).

Layout of `PlayersScreen` (design 4.1 and the mockup): `HomeTopBar(PlayersCopy.PLAYERS_TITLE) { BackgroundWorkAction(); gear }`, a `LazyColumn` of player rows built from `DrawerList` and `DrawerRowContainer` (avatar, name, `playerRowDetail`, chevron), an "Add player" row, an "All matches" `ShuttlButton(variant = Secondary)`, `AddMatchButton` + `AddMatchSheet` as on `HomeScreen`, `ShuttlEmptyState` for no players (with Add player as its action and All matches still below it). The run-announce effect and the intake-error snackbar move from `HomeScreen` to `PlayersScreen`; `HomeScreen` becomes the All matches screen (back arrow `TopAppBar`, title "All matches", list unchanged). The one `ClipListViewModel` is scoped to the `Route.Players` entry; Settings, Match, All matches and Player read it with `nav.getBackStackEntry<Route.Players>()`. Review every `Route.Home` site listed in `AuthGate.kt` (144, 315, 341-344, 359, 401, 455, 471).

- [ ] **Step 1: Write the failing tests.** `AuthNavigationTest`: the authenticated start is `Route.Players`. `PlayersViewModelTest` (`FakePlayersRepository`, `StandardTestDispatcher`): `refresh_failure_sets_load_failed`, `create_returns_the_player`, `a_failed_rename_shows_the_save_sentence`, `delete_goes_through_the_shared_rewrite`.
- [ ] **Step 2: Run, expect failures.** `./gradlew --offline :androidApp:testDebugUnitTest --tests "*AuthNavigation*" --tests "*PlayersViewModel*"`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the Android gate: `BUILD SUCCESSFUL` for unit tests, debug and release.
- [ ] **Step 5: Commit** `feat(android): Players is the home screen, the match list is All matches`.

---

### Task 9: Android, the player page

**Files:**
- Create: `players/PlayerScreen.kt`
- Modify: `cliplist/ClipListScreen.kt`, `AuthGate.kt`, `localvideo/VideoIntake.kt`, `scoring/NewMatchViewModel.kt`
- Test: `scoring/NewMatchViewModelTest.kt`, extend `cliplist/ClipListViewModelMatchListTest.kt` or create `players/PlayerScreenStateTest.kt` for any state derived outside composables

**Interfaces:**
- Consumes: `MatchList.forPlayer`, `playerSummary`, `lastMatchTile`, `PlayerNameDialog`, `PlayerAvatar`, `PlayersViewModel`.
- Produces:
  - `ClipListScreen` gains `playerId: String?`: when set, the list shows `matchList.forPlayer(playerId)` with its filters and sort, no "Shared with me" section, and its own empty state ("No matches yet").
  - `VideoIntake.record(forMatch: MatchTarget?, forPlayer: String?)` and `import(...)` likewise: a new entry for a player starts with `homePlayerId = forPlayer`, `nearSide = Side.HOME`.
  - `NewMatchViewModel(scoreLogs, placeNames, playerId: String?)`: after `create`, `assignPlayers(id, MatchPlayers(homePlayerId = playerId, ...))` when a player is given.

Layout (design 4.2 and the mockup): a `TopAppBar` with back, the player's name with `ShuttlIcons.ChevronDown` as a `DropdownMenu` switcher listing `sortedPlayers` (picking one `navigate(Route.Player(id)) { popUpTo<Route.Player> { inclusive = true } }`), and an overflow with "Rename", "Playing hand" (a small dialog with the three pills), "Delete player" (`ConfirmDialog` with the delete copy and the player's `matchCount`; on success pop back to Players). `ShuttlPillTabs(listOf("Profile", "Matches"))` saved in `rememberSaveable`. Profile: 60dp avatar, name in `headlineMedium`, the hand line, three `ShuttlStatTile`s in a row. Matches: `ClipListScreen(playerId = ...)`. The add button opens `AddMatchSheet`; New match navigates `Route.NewMatch(playerId)`, Record/Import call the intake with `forPlayer`.

- [ ] **Step 1: Write the failing tests**: `NewMatchViewModelTest.a_match_started_for_a_player_makes_them_home`; any derived-state test the implementation introduces.
- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the Android gate: `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** `feat(android): a player's page with their profile and their matches`.

---

### Task 10: Android, who's playing, the details editor, and the attach question

**Files:**
- Create: `players/WhoIsPlayingSection.kt`, `players/HomeEndDialog.kt`
- Modify: `localvideo/MatchDetailsSheet.kt`, `localvideo/AutoMatchDetailsHost.kt`, `cliplist/ClipListScreen.kt` (row menu), `scoring/EditMatchDetailsDialog.kt`, `match/MatchScreen.kt`, `match/MatchViewModel.kt`, `AuthGate.kt` (intake `onAdded`)
- Test: `match/MatchViewModelTest.kt`, `scoring/EditMatchDetailsDialog` has no unit seam: put any branching in shared (Task 4) or in `MatchViewModel`

**Interfaces:**
- Consumes: `EndsDraft` and friends, `SidesDraft`, `matchPlayersProblem`, `saveMatchPlayersOrMessage`, `asksForHomeEnd`, `homeEndTitle`, `item.playersTarget`, `item.canEditPlayers`.
- Produces:
  - `@Composable fun WhoIsPlayingSection(draft: EndsDraft, players: List<Player>, onChange: (EndsDraft) -> Unit, onAddPlayer: suspend (String, RacketArm?) -> Player?)`: the "One player | Both players" `ShuttlPillTabs`, then Far above Near, each end a player dropdown ("Choose a player", the players, "Add player") or, for the non-profile end in ONE mode, `ShuttlOutlinedTextField("Opponent (optional)", maxLength = 40)`; a "Swap ends" text button in ONE mode; the device line when `showsDeviceLine()`.
  - `MatchDetailsSheet(..., playersDraft: EndsDraft?, playersEditable: Boolean, ...)` shows the section above the name; `onSave` also returns the draft. The section shows whenever the entry is owned; the title and description stay LOCAL-only as today.
  - `EditMatchDetailsDialog` for a score log replaces each side's `PlayerField`s with a dropdown (the players, "Someone else", "Add player"); "Someone else" reveals today's name field(s). When a video is attached, a "Home was at the" row with pills "Near", "Far", "Not set". Doubles pills disabled while a side holds a profile, with the doubles note. For a video-born match the dialog shows `WhoIsPlayingSection` instead of the side rows.
  - `MatchViewModel.saveMatchPlayers(players: MatchPlayers, nearSide: Side?, format: MatchFormat?)` calling `saveMatchPlayersOrMessage` with the page's target, error surfaced like `updateVideoDetails`.
  - `MatchScreen`: "Edit details" offered whenever `matchItem.canEditPlayers` or today's condition; a phone-only or no-clips match opens the dialog with only the fields it can save (players always; title and description only when the existing rules allow). `UPLOADING` shows the section read-only with the upload sentence.
  - `HomeEndDialog(title: String, onNear, onFar, onSkip)`: an `AlertDialog`-shaped dialog using `ShuttlButton`s, subtitle "As the camera sees it.". In `AuthGate`'s intake `onAdded` `else` arm (an entry with `scoreLogId`), when `asksForHomeEnd(log)` show it before `nav.navigate(Route.CourtMarking(entry.id))`; Near/Far call `localVideos.setMatchPlayers(entry.id, MatchPlayers.NONE, Side.HOME or Side.AWAY)` (Home at near is `nearSide = HOME`; Home at far is `nearSide = AWAY`); Skip leaves it null. Navigation follows any of the three.

- [ ] **Step 1: Write the failing tests**: `MatchViewModelTest.saving_players_on_a_scored_match_assigns_the_log`, `saving_players_on_a_phone_only_match_writes_the_entry`, `a_refused_save_surfaces_the_sentence`.
- [ ] **Step 2: Run, expect failures.** `./gradlew --offline :androidApp:testDebugUnitTest --tests "*MatchViewModel*"`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the Android gate: `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** `feat(android): who's playing after a recording, in the details editor, and when a video joins a scored match`.

---

### Task 11: Android, names and hands in the Analysis panels

**Files:**
- Modify: `match/AnalysisSection.kt`, `localanalysis/HeatmapPanel.kt`, `localanalysis/BasePositionPanel.kt`, `localanalysis/SkeletonPanel.kt`, `localanalysis/MetricsStrip.kt`, `AuthGate.kt` (the `analysisSection` slot)
- Test: `localanalysis/HeatmapSourceTest.kt` (only if a helper's signature changes)

**Interfaces:**
- Consumes: `courtSideNames`, `sideToggleLabel`, `profileArm`.
- Produces: `AnalysisSection(..., sideNames: CourtSideNames?)` threaded to the three panels; `SideToggle(sides, selected, onSelect, names: CourtSideNames?)` labels with `sideToggleLabel` (update its KDoc, which says names are out of scope); in `SkeletonPanel`, when `profileArm(shownSide, names)` is non-null the arm pills are hidden and that arm is used; otherwise the per-video preference as today.

- [ ] **Step 1: Implement** (the logic is Task 7's and is tested there; this is wiring).
- [ ] **Step 2: Run** the Android gate: `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** `feat(android): the analysis names the near and far player`.

---

### Task 12: iOS, Players as the home screen

**Files:**
- Create: `Players/PlayersScreen.swift`, `Players/PlayersModel.swift`, `Players/PlayerDialogs.swift`, `Players/PlayerAvatar.swift`
- Modify: `Home/HomeView.swift`, `Home/MatchesScreen.swift`, `ClipList/ClipListView.swift`
- Test: create `iosApp/Tests/PlayersModelTests.swift`

**Interfaces:**
- Consumes: `rally.players`, the Swift entry points from Task 2, `testPlayersRepository`, `PlayerSummaryKt` functions.
- Produces:
  - `@Observable @MainActor final class PlayersModel` with `players: [Player]`, `loadFailed`, `errorMessage`, `func observe() async` (returns on cancel, as `ClipListModel`), `refresh()`, `create(name:arm:) async -> Player?`, `rename`, `setRacketArm`, `delete(_:) async -> Bool` (do/catch, never `try?` over a `String?` result).
  - `PlayerAvatar(name: String, size: CGFloat)`, `PlayerNameSheet` (Add with hand pills, Rename name only).
  - `HomeView`'s `NavigationStack` root becomes `PlayersScreen`; `MatchesScreen` is pushed for All matches (new `@State showAllMatches`); the header, gear, background-work action, add button and toast move to `PlayersScreen`, and `MatchesScreen` takes a back-button title bar ("All matches"). `.feedsMatchList(model:localAnalysis:)` stays on the one stack. `nothingAbove` and the renamed `goToPlayers()` (was `goToMatches()`) include the new routes.

- [ ] **Step 1: Write the failing tests**: `PlayersModelTests.testRefreshFailureSetsLoadFailed`, `testCreateReturnsThePlayer`, `testLeavingStopsObserving` (the `ObservationLifecycle.runAndCancel` pattern from `ClipListModelTests`).
- [ ] **Step 2: Build and run them, expect failures.** `cd iosApp && xcodegen generate && cd ..`, the iOS build, then the xctest recipe with `-XCTest PlayersModelTests`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the build (`BUILD SUCCEEDED`), `PlayersModelTests`, `ClipListModelTests`, and `TypographyAdoptionTests`: all pass.
- [ ] **Step 5: Commit** `feat(ios): Players is the home screen, the match list is All matches`.

---

### Task 13: iOS, the player page

**Files:**
- Create: `Players/PlayerView.swift`
- Modify: `ClipList/ClipListView.swift`, `Home/HomeView.swift`, `LocalVideo/LocalVideoIntake.swift`, `Scoring/NewMatchView.swift`
- Test: extend `iosApp/Tests/PlayersModelTests.swift` or create `PlayerViewTests.swift` for derived state

**Interfaces:**
- Produces: `MatchesList(..., playerId: String?)` (filters `mine` with `forPlayer`, hides "Shared with me", gates the empty overlay to "No matches yet"); `LocalVideoIntake.add(tempURL:suggestedName:isRecording:forMatch:forPlayer:)`; `NewMatchView(rally:playerId:onCreated:)` assigning home after create. `PlayerView` mirrors Task 9's layout: a toolbar with the name as a `Menu` switcher, overflow `Menu` with Rename, Playing hand, Delete player (`confirmationDialog` with the delete copy), `ShuttlPillTabs(["Profile", "Matches"])` under `.pillLabelCeilingHost()`, the profile with `PlayerAvatar(size: 60)`, the hand line and three `ShuttlStatTile`s, and Matches as `MatchesList(playerId:)`.

- [ ] **Step 1: Write the failing test(s)** for any derived state (`testANewMatchForAPlayerMakesThemHome` against `testScoreLogsRepository`).
- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement**; `xcodegen generate` for new files.
- [ ] **Step 4: Run** the build and the iOS unit bundle: all pass.
- [ ] **Step 5: Commit** `feat(ios): a player's page with their profile and their matches`.

---

### Task 14: iOS, who's playing, the details editor, the attach question, and the Analysis names

**Files:**
- Create: `Players/WhoIsPlayingSection.swift`, `Players/HomeEndPrompt.swift`
- Modify: `LocalVideo/MatchDetailsSheet.swift` (both call sites: `HomeView` and `MatchesList`), `Scoring/EditMatchDetailsSheet.swift`, `Match/MatchView.swift`, `Match/MatchModel.swift`, `Match/AnalysisSection.swift`, `LocalAnalysis/SkeletonPanel.swift`, `LocalAnalysis/BasePositionPanel.swift`
- Test: extend `iosApp/Tests/MatchModelTests.swift`

**Interfaces:**
- Mirrors Task 10 and Task 11: `WhoIsPlayingSection(draft: Binding<EndsDraft>, players: [Player], onAddPlayer: (String, RacketArm?) async -> Player?)`; `MatchDetailsSheet` with the section; `EditMatchDetailsSheet` with side pickers, "Someone else", the ends row and the doubles rule; `MatchModel.saveMatchPlayers(players:nearSide:format:) async`; the editor reachable for every owned match (`MatchView.swift:206-215, 406-429, 814-826`); `HomeEndPrompt` shown in `MatchView`'s `.onChange(of: intake.lastAddedId)` before `navigationTarget = CourtMarkingRoute(...)` when `asksForHomeEnd(log)`; `SideToggle(..., names:)` and the skeleton arm rule.

- [ ] **Step 1: Write the failing tests**: `MatchModelTests.testSavingPlayersOnAScoredMatchAssignsTheLog`, `testSavingPlayersOnAPhoneOnlyMatchWritesTheEntry`.
- [ ] **Step 2: Run, expect failures.**
- [ ] **Step 3: Implement**; `xcodegen generate`.
- [ ] **Step 4: Run** the build and the iOS unit bundle, including `TypographyAdoptionTests` and `SkeletonPanelTests`: all pass. `VideoMatchDetailsUITests` must still compile.
- [ ] **Step 5: Commit** `feat(ios): who's playing, the details editor, the attach question, and names in the analysis`.

---

### Task 15: Screens checked, changelog, version, hand-over

The UI bar is pixel level, on both platforms, light and dark, and large text (Android font 2x, iOS AX5).

**Files:**
- Create: screenshots `docs/screenshots/2026-10-31-<android|ios>-players-<state>-<light|dark>.png`
- Modify: `CHANGELOG.md` (`## [Unreleased]` / `### Added`), the version (0.13.0 (30), wherever 0.12.0 (29) was set in `3c9bda8c`), `docs/plans/2026-10-31-player-profiles-design.md` (status, and a "What shipped differently" section if anything did)

- [ ] **Step 0: Confirm the migration is on the project.** Do not start until Coen has run `supabase db push` for `20261031000000_player_profiles.sql`: a build of this branch against a project without it fails every score log sync (the upsert names unknown columns) and cannot load players. Builds already installed (0.12.0) are unaffected by the migration: supabase-kt decodes with `ignoreUnknownKeys`, and every new column is nullable with checks and triggers that only bite when an id is set.
- [ ] **Step 1: Install.** Android: `ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:installDebug` if the emulator takes it, else the S23 by serial (never unpinned: `installDebug` hits every attached device). iOS: the iPhone 17 Pro simulator; sign-in needs Coen, so hand that step over.
- [ ] **Step 2: Walk each state, capturing to a scratch path and viewing it before copying it into `docs/screenshots/`:** Players empty; Players with three players; Add player; a player's Profile (with and without a hand, with no matches); Matches tab; the switcher open; Delete confirmation; record or import from a player's page (Who's playing pre-filled, ONE mode); BOTH mode; the far-end device line; Swap ends; the details editor of a scored match (pickers, "Someone else", the ends row, doubles disabled); the attach question; the Analysis toggle with names; All matches. Check alignment to the 24dp gutter, truncation of long names (40 characters) in rows, the switcher, the toggle and the attach title, and that nothing overlaps at 2x / AX5.
- [ ] **Step 3: Report, do not fix silently.** List anything below the bar with its screenshot. Ask before fixing anything outside this feature's surfaces.
- [ ] **Step 4: Changelog**, under `### Added`:

```markdown
- **Players.** The app opens on your players. Each player has a page with a
  profile and their matches, and the name at the top switches between them.
  After recording or importing, "Who's playing" says which of your players is
  on which end, one or both; a scored match that gets a video asks which end
  Home was on. The analysis then names the near and far player, and a player's
  playing hand is set once on the player. Every match you already had is in
  All matches, and any of them can be given a player from its details.
```

- [ ] **Step 5: Commit** `docs: players on both platforms, with screenshots, the changelog and 0.13.0 (30)`.
- [ ] **Step 6: Hand over to Coen**, explicitly, on a real phone: create two players, record a video for one from their page, analyse it in the cloud with both tracked, and confirm both players' pages list it and the Analysis toggle names them; score a match for one player, attach a video, answer the end question, and confirm the same.
