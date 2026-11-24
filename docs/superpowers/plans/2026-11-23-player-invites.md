# Coach-to-Player Invites & Focused Player View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement coach-generated single-use shareable invite codes (+ QR), invite-gated player account creation via a Supabase Edge Function, a focused read-only Player view (`Profile | Matches | Drills`) with on-demand cloud pose analysis (Heatmap/Base/Drills), cloud-synced workout drill logs, and per-account local cache isolation across Supabase, `:shared`, Android, and iOS.

**Architecture:** Postgres owns `player_logins`, `player_invites`, `invite_rate_limits`, and `drill_session_logs` plus unified read helpers (`can_read_video`, `is_linked_player_on_video`, `is_coach_account`) and narrow read RPCs (`list_linked_score_logs`, `get_cloud_pose_artifact`). A small Deno Edge Function (`redeem-player-invite`, `verify_jwt = false`) previews and atomically redeems codes via `auth.admin.createUser({ email_confirm: true, app_metadata: { account_role: 'player' } })` while public registration stays closed. All invite formatting, copy, repositories, coached match-list logic, offline-first drill sync, and account-scoped local store envelopes live in `:shared`, consumed identically by Android (Compose + ZXing QR + Play Services code scanner) and iOS (SwiftUI + CoreImage QR + AVFoundation scanner).

**Tech Stack:** PostgreSQL / Supabase RLS + Deno Edge Functions (TypeScript), Kotlin Multiplatform (`:shared`, supabase-kt 3.5.0, Ktor, multiplatform-settings), Android (Jetpack Compose, Material 3, ZXing core, Play Services code-scanner), iOS (SwiftUI, CoreImage, AVFoundation, XCTest).

**Spec:** `docs/superpowers/specs/2026-11-23-player-invites-design.md`

## Global Constraints
- Invite codes: 8 uppercase chars from unambiguous Crockford alphabet `23456789ABCDEFGHJKMNPQRSTUVWXYZ`, formatted `XXXX-XXXX`, 7-day expiry, single-use, QR payload `shuttl-invite:<CODE>`.
- Public registration stays closed; `redeem-player-invite` sets `app_metadata.account_role = 'player'`, skips `seed_annotation_labels_on_signup`, and enforces `is_coach_account()` on all coach write RLS policies (`players`, `videos`, `score_logs`, `annotation_labels`, `rally_clips`, `rally_annotations`, `storage.objects` `videos` bucket).
- Raw `public.videos` and `public.score_logs` `SELECT` stay owner-only; linked players read via `list_linked_score_logs()` and `get_cloud_pose_artifact(p_video_id)` with opponent `player_id` UUIDs masked and names resolved.
- Linked players see Heatmap, Base, and Drills only (no Skeleton / no `videos` storage bucket read); `list_match_metadata()` returns `has_cloud_pose` and `AnalysisSection` auto-downloads + caches `poses.raw` once on open.
- Kotlin models exposed to Swift use plain data classes and enums (no deeply nested sealed hierarchies, explicit parameters rather than defaults on Swift interop helpers).
- Existing coaches' pre-upgrade local videos, annotations, racket-arm prefs, and drill logs migrate automatically to the first signed-in account.

## Review Focus
1. **Concurrent / duplicate redemption or existing email on redeem:** Two simultaneous `redeem` calls on one code must link at most one user and roll back the losing `auth.users` row; `email_exists` must NOT consume the invite code so the user can switch to "Sign in to link".
2. **Score-only coach-vs-coach match visibility:** When Marco plays Tom (both coach profiles) in a score-only match with no video, Marco sees `"Tom"` in the players line, cannot read Tom's `players` row or UUID, and cannot edit/resume/delete the score log.
3. **Revoked / deleted player mid-session:** After coach calls `unlink_player_login` or deletes Marco, refreshing drops Marco's `linkedProfiles`, clears access to matches/clips/poses/drills, and shows the Unlinked recovery screen with "Enter invite code".
4. **Shared-phone account switch (Coach ↔ Player):** Signing out of Coach and into Player on the same phone must hide all coach local videos, local annotations, racket-arm prefs, drill logs, and cached cloud pose tracks — and signing back into Coach must restore Coach's local data cleanly.
5. **Read-only enforcement on every Match & Clip write affordance:** A linked player opening a bound scored match or video match must never see Share, Edit details, Who's playing, Delete, Attach/Remove video, Resume scoring, Edit score, Re-analyze, or Add/Delete annotation on either Android or iOS.

---

### Task 1: Supabase Migration, Config & SQL Test Suite

**Files:**
- Create: `supabase/migrations/20261123000000_player_invites.sql`
- Modify: `supabase/config.toml` (add `[functions.redeem-player-invite] verify_jwt = false`)
- Create: `supabase/tests/player_invites_test.sql`

**Interfaces:**
- Produces:
  - Tables: `public.player_logins`, `public.player_invites`, `public.invite_rate_limits`, `public.drill_session_logs`
  - Helpers: `public.is_coach_account()`, `public.can_access_player(uuid)`, `public.is_linked_player_on_video(uuid)`, `public.can_read_video(uuid)`, `public.check_invite_rate_limit(text, int, int)`
  - Coach/Player RPCs: `create_player_invite(uuid)`, `revoke_player_invite(uuid)`, `unlink_player_login(uuid)`, `list_player_access_states()`, `list_my_player_profiles()`, `list_linked_score_logs()`, `get_cloud_pose_artifact(uuid)`, `redeem_player_invite_authenticated(text)`, `complete_player_invite_redemption(uuid, uuid)`
  - Updated `list_match_metadata()` adding `has_cloud_pose boolean` and revealing caller's own `player_id`
  - Updated `seed_annotation_labels()` skipping `account_role = 'player'`
  - Updated RLS & storage policies (`players`, `rally_clips`, `rally_annotations`, `storage.objects` `clips`/`thumbnails`/`results`/`videos`, plus `is_coach_account()` write guards)

- [ ] **Step 1:** Write `supabase/tests/player_invites_test.sql` covering invite lifecycle, redemption, opponent UUID masking, `has_cloud_pose`, `get_cloud_pose_artifact`, `list_linked_score_logs`, storage policies (`clips`, `thumbnails`, `results`, `videos`), `is_coach_account()` write denials, `drill_session_logs` RLS, unlink/delete revocation, and `seed_annotation_labels` player skip.
- [ ] **Step 2:** Implement `supabase/migrations/20261123000000_player_invites.sql` and update `supabase/config.toml`.
- [ ] **Step 3:** Validate SQL syntax and run `supabase/tests/run.sh` (if Docker daemon available; otherwise static SQL inspection + record device/Docker check note).
- [ ] **Step 4:** Commit `feat(supabase): add player invites, linked player RLS, and synced drill logs migration`.

---

### Task 2: Supabase Edge Function (`redeem-player-invite`) + Deno Tests

**Files:**
- Create: `supabase/functions/redeem-player-invite/handler.ts` (pure testable handler with injectable DB/Auth ports)
- Create: `supabase/functions/redeem-player-invite/index.ts` (Deno `Deno.serve` entrypoint wiring Supabase admin client)
- Create: `supabase/functions/redeem-player-invite/handler_test.ts`

**Interfaces:**
- Consumes: `player_invites`, `players`, `player_logins`, `check_invite_rate_limit`, `complete_player_invite_redemption`
- Produces: HTTP `POST /functions/v1/redeem-player-invite` accepting `{ action: "preview", code }` → `{ playerName, racketArm, coachEmail }` and `{ action: "redeem", code, email, password }` → `{ playerId, playerName, coachEmail }` with error codes `invalid_or_expired`, `already_linked`, `email_exists`, `weak_password`, `invalid_email`, `rate_limited`.

- [ ] **Step 1:** Write failing Deno unit tests in `handler_test.ts` (preview happy path, invalid/expired/revoked identical error, rate-limited 429, redeem happy path with `app_metadata.account_role = 'player'`, `email_exists` 409 without consuming invite, weak password / invalid email 400, rollback `deleteUser` on claim race failure).
- [ ] **Step 2:** Run `deno test supabase/functions/redeem-player-invite/handler_test.ts` — verify RED.
- [ ] **Step 3:** Implement `handler.ts` and `index.ts`.
- [ ] **Step 4:** Run `deno test supabase/functions/redeem-player-invite/handler_test.ts` — verify GREEN.
- [ ] **Step 5:** Commit `feat(supabase): add redeem-player-invite edge function with tests`.

---

### Task 3: `:shared` AccountRole, Invite Formatting, Models, Copy & `PlayerAccessRepository`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthState.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/AuthRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/invites/PlayerInviteModels.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/invites/InviteCodeFormat.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/invites/PlayerInviteCopy.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/invites/PlayerAccessRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/invites/InviteCodeFormatTest.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/invites/PlayerAccessRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `AccountRole { COACH, PLAYER }`, `AuthRepository.accountRole(): AccountRole`
  - `normalizeInviteCode(raw: String): String?`, `formatInviteCode(code: String): String`, `qrPayloadForCode(code: String): String`, `parseQrInvitePayload(raw: String): String?`
  - `PlayerAccessRepository` (`accessByPlayerId`, `linkedProfiles`, `linkedProfilesLoaded`, `refreshAccessStates`, `createInvite`, `revokeInvite`, `unlinkPlayer`, `refreshLinkedProfiles`, `previewInvite`, `redeemInviteNewAccount`, `redeemInviteExistingAccount`, `clear`)
  - `PlayerInviteCopy` (all copy + error messages + `shareInviteText`)

- [ ] **Step 1:** Write failing unit tests in `InviteCodeFormatTest.kt` and `PlayerAccessRepositoryTest.kt` (MockEngine covering RPCs, Edge Function preview/redeem, error codes, owner-scoped cache, account switch clearing).
- [ ] **Step 2:** Run `./gradlew :shared:jvmTest --tests "*Invite*" --tests "*PlayerAccess*"` — verify RED.
- [ ] **Step 3:** Implement `AccountRole`, `AuthRepository.accountRole()`, `PlayerInviteModels`, `InviteCodeFormat`, `PlayerInviteCopy`, and `PlayerAccessRepositoryImpl`.
- [ ] **Step 4:** Run `./gradlew :shared:jvmTest` — verify GREEN.
- [ ] **Step 5:** Commit `feat(shared): add player invites models, code formatting, copy, and PlayerAccessRepository`.

---

### Task 4: `:shared` CloudPose RPC, `hasCloudPose`, `linkedLogs` & Coached `MatchListBuilder`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/MatchMetadata.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/repo/CloudPoseRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLog.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/scoring/ScoreLogsRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListInput.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListItem.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchlist/MatchListBuilder.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/players/PlayerMatches.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/matchpage/MatchPageSection.kt`
- Test: update & extend `CloudPoseRepositoryTest.kt`, `ScoreLogsRepositoryTest.kt`, `MatchListBuilderTest.kt`, `PlayerMatchesTest.kt`, `MatchPageSectionTest.kt`

**Interfaces:**
- Consumes: `list_match_metadata` (`has_cloud_pose`), `get_cloud_pose_artifact`, `list_linked_score_logs`
- Produces:
  - `MatchMetadata.hasCloudPose: Boolean`
  - `ScoreLog.ownerId: String?`, `ScoreLogsRepository.linkedLogs: StateFlow<List<ScoreLog>>`, `ScoreLogsRepository.refreshLinkedLogs()`
  - `MatchListInput.linkedPlayerIds: Set<String>`, `MatchListInput.linkedScoreLogs: List<ScoreLog>`
  - `MatchList.coached: List<MatchListItem>`, `MatchList.itemWithKey` & `matchPageItem` & `forPlayer` searching `mine + coached`

- [ ] **Step 1:** Write failing tests covering `get_cloud_pose_artifact` RPC, `hasCloudPose` lighting up `MatchAnalysis.HEATMAP` & `MatchPageSection.ANALYSIS`, `ScoreLogsRepository.linkedLogs` read-only cache & owner-scoped `sync()`, and `MatchListBuilder` building coached score-only / video-only / bound matches with `isOwned = false`, `delete = NONE`, opponent names intact, and ad-hoc `match_shares` unchanged.
- [ ] **Step 2:** Run `./gradlew :shared:jvmTest` — verify RED.
- [ ] **Step 3:** Implement all `:shared` model, repository, and match-list changes.
- [ ] **Step 4:** Run `./gradlew :shared:jvmTest` — verify GREEN.
- [ ] **Step 5:** Commit `feat(shared): support linked player matches, score logs, and cloud pose discovery`.

---

### Task 5: `:shared` Cloud-Synced `DrillProgressRepository`, Account-Scoped Local Stores, `RallyApp` & `SwiftInterop`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/exercises/DrillProgressRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalVideoRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/LocalAnnotationsRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/prefs/RacketArmPreferenceRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/RallyApp.kt`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/SwiftInterop.kt`
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/testing/IosTestDoubles.kt` (if affected by `AuthRepository` / `RallyApp` additions)
- Test: update & extend `DrillProgressRepositoryTest.kt`, `LocalVideoRepositoryTest.kt`, `LocalAnnotationsRepositoryTest.kt`, `RacketArmPreferenceRepositoryTest.kt`, `RallyAppSessionCacheTest.kt`

**Interfaces:**
- Produces:
  - `DrillSessionLog.loggedByUserId: String?`, `DrillProgressRepository.sync(): Result<Unit>`, `reloadForCurrentOwner()`, legacy-key migration
  - Account-scoped `LocalVideoRepository`, `LocalAnnotationsRepository`, `RacketArmPreferenceRepository` with one-time legacy migration and `reloadForCurrentOwner()`
  - `RallyApp.playerAccess: PlayerAccessRepository`, `sessionCacheCleaner` reloading all local stores + clearing `playerAccess`
  - Swift interop wrappers in `SwiftInterop.kt` for `PlayerAccessRepository`, `DrillProgressRepository.syncDrillLogsOrMessage`, `ScoreLogsRepository.refreshLinkedLogsOrMessage`, `AuthRepository.accountRole()`

- [ ] **Step 1:** Write failing tests for drill log cloud sync (dirty push, pending delete, remote merge, legacy ID migration, owner switch isolation) and local store isolation across account switches (preserving legacy data on first upgrade).
- [ ] **Step 2:** Run `./gradlew :shared:jvmTest` — verify RED.
- [ ] **Step 3:** Implement `DrillProgressRepository` sync + owner scoping across local stores + `RallyApp` wiring + `SwiftInterop`.
- [ ] **Step 4:** Run `./gradlew :shared:jvmTest` — verify GREEN.
- [ ] **Step 5:** Commit `feat(shared): sync drill logs to Supabase and isolate local stores per account`.

---

### Task 6: Android UI, Navigation, QR Code/Scanner, Read-Only Audit & On-Demand Cloud Pose Download

**Files:**
- Modify: `gradle/libs.versions.toml`, `androidApp/build.gradle.kts` (add `com.google.zxing:core` + `com.google.android.gms:play-services-code-scanner`)
- Create: `androidApp/src/main/java/com/badmintontracker/android/invites/PlayerAccessViewModel.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/invites/PlayerAccessCard.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/invites/PlayerInviteSheet.kt` (formatted code + copy + ZXing QR bitmap + native Share Intent + regenerate/cancel + phone-only matches warning)
- Create: `androidApp/src/main/java/com/badmintontracker/android/invites/JoinInviteViewModel.kt`
- Create: `androidApp/src/main/java/com/badmintontracker/android/invites/JoinInviteScreen.kt` (code entry + Play Services QR scan + preview + create account / sign in to link)
- Create: `androidApp/src/main/java/com/badmintontracker/android/invites/PlayerHomeScreen.kt` (focused player view with multi-coach switcher + `Profile | Matches | Drills` + `UnlinkedPlayerScreen` + in-app code sheet)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/signin/SignInScreen.kt` (add "I have an invite code" CTA + updated footnote)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/nav/Route.kt`, `AuthNavigation.kt`, `AuthGate.kt` (route `COACH` vs `PLAYER`, wire `JoinInvite`, `PlayerHome`, account-scoped pose track cache cleanup on user switch)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/players/PlayersScreen.kt`, `PlayerScreen.kt` (linked status indicator, dual-role "My profile" card, `PlayerAccessCard` on Profile tab, synced drill logs refresh)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/cliplist/ClipListViewModel.kt` (feed `linkedPlayerIds` & `linkedScoreLogs` into `MatchListInput`, refresh linked logs & drill logs)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/match/MatchScreen.kt`, `AnalysisSection.kt`, `PointsFacet.kt` (audit every write gate on `isOwned`, pass linked profile to `CourtSideNames`, auto-download cloud pose via `CloudPoseCoordinator.install` with progress + retry)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/settings/SettingsScreen.kt` (hide Labels for `PLAYER`, add "Join another coach" row)
- Test: `androidApp/src/test/java/com/badmintontracker/android/invites/JoinInviteViewModelTest.kt`, `PlayerAccessRoutingTest.kt`, plus update existing unit tests for new signatures.

- [ ] **Step 1:** Add Android unit tests for `JoinInviteViewModel`, role-based start/redirect routing, and read-only match gating.
- [ ] **Step 2:** Implement Android dependencies, invite screens, focused player home, read-only match gating, on-demand cloud pose download, and Settings updates.
- [ ] **Step 3:** Run `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug -PonnxModelsOptional=true` — verify GREEN with 0 errors.
- [ ] **Step 4:** Commit `feat(android): add coach player invites, QR code/scanner, focused player home, and synced drills`.

---

### Task 7: iOS UI, Navigation, CoreImage QR + AVFoundation Scanner, Read-Only Audit & On-Demand Cloud Pose Download

**Files:**
- Create: `iosApp/Sources/Invites/PlayerAccessModel.swift`
- Create: `iosApp/Sources/Invites/PlayerAccessCard.swift`
- Create: `iosApp/Sources/Invites/PlayerInviteSheet.swift` (formatted code + copy + `CIQRCodeGenerator` QR + `ShareLink` + regenerate/cancel + phone-only warning)
- Create: `iosApp/Sources/Invites/QrCodeScannerSheet.swift` (`AVCaptureSession` + `AVCaptureMetadataOutput` `.qr`)
- Create: `iosApp/Sources/Invites/JoinInviteView.swift` (code entry + Scan QR + preview + create account / sign in to link)
- Create: `iosApp/Sources/Invites/PlayerHomeView.swift` (focused player home with multi-coach switcher + `Profile | Matches | Drills` + `UnlinkedPlayerView` + in-app code sheet)
- Modify: `iosApp/Sources/SignIn/SignInView.swift` (add "I have an invite code" CTA + updated footnote)
- Modify: `iosApp/Sources/RootView.swift` (route `COACH` vs `PLAYER`, account-scoped local pose cache isolation on account switch)
- Modify: `iosApp/Sources/Home/HomeView.swift`, `HomeRoute.swift`, `SettingsView.swift` (dual-role "My profile", hide Labels for `PLAYER`, add "Join another coach")
- Modify: `iosApp/Sources/Players/PlayersScreen.swift`, `PlayerView.swift` (linked indicator, `PlayerAccessCard`, synced drill logs refresh)
- Modify: `iosApp/Sources/ClipList/ClipListModel.swift` (feed `linkedPlayerIds` & `linkedScoreLogs`, refresh linked logs & drill logs)
- Modify: `iosApp/Sources/Match/MatchView.swift`, `AnalysisSection.swift`, `PointsFacet.swift` (audit every write gate on `isOwned`, pass linked profile to `CourtSideNames`, on-demand `CloudPoseCoordinator.install` with progress + retry)
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj` (register all new Swift source & test files)
- Test: `iosApp/Tests/PlayerInviteFlowTests.swift` + update existing tests for new signatures.

- [ ] **Step 1:** Implement iOS models, views, QR generation/scanning, focused player home, read-only match audit, on-demand cloud pose download, and `project.pbxproj` entries.
- [ ] **Step 2:** Add XCTests in `PlayerInviteFlowTests.swift` covering routing, read-only gating, QR payload parsing, and invite state transitions.
- [ ] **Step 3:** Run `xcodebuild test` against iOS Simulator — verify build & all XCTests GREEN.
- [ ] **Step 4:** Commit `feat(ios): add coach player invites, QR code/scanner, focused player home, and synced drills`.

---

### Task 8: Whole-Branch Code Review, Fix Pass & Final Verification

- [ ] **Step 1:** Dispatch a fresh `research` reviewer subagent against the full `feat/exercise-suggestions..HEAD` diff, the spec, and the Review Focus list.
- [ ] **Step 2:** Fix every Critical and Important finding using TDD (RED → GREEN), commit cleanly.
- [ ] **Step 3:** Run full verification across all modules (`deno test`, `./gradlew :analysis:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug -PonnxModelsOptional=true`, `xcodebuild test`, `git status`, `git log`).
