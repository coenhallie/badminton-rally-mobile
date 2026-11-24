# Design: Coach-to-Player Invites & Focused Player View

**Date:** 2026-11-23
**Status:** Approved by Coen 2026-11-24 (builder check complete)
**Size:** Project (Supabase migration + RLS/storage policies + 1 Edge Function + KMP `:shared` + Android + iOS)
**Asked for:** Coen, 2026-11-23: design and build a clean UX flow for coaches to invite existing player profiles (already created under the coach's account) so players can log in and view their own profile, matches, analysis, and workouts.
**Follows:** `docs/plans/2026-10-31-player-profiles-design.md` (Step 4, honouring every constraint in Section 6) and `docs/plans/2026-11-22-exercise-suggestions-design.md` (guided drill runner & player Drills tab).
**Visual mockups:** `.superpowers/brainstorm/89853-1795495966/content/invite-flow.html` (local, not committed).

---

## 1. Intent & Why

Today every player profile (`public.players`) lives strictly inside the coach's account: the coach films, scores, annotates, and analyses matches on that profile, and the player cannot see any of it on their own phone without ad-hoc per-video `match_shares` (which require an existing Shuttl account and expose only clips and annotations — not the profile, score logs, or cloud analysis).

The goal is to close the loop between coach and player without transferring data ownership:
- The **coach** keeps owning every player row, video, clip, annotation, score log, and storage file (`owner_id` never moves).
- From an existing player's Profile tab, the coach generates a **single-use shareable invite code** (plus a scannable QR code for courtside handoff and native Share sheet text for WhatsApp/Messages).
- The **player** opens Shuttl, taps **"I have an invite code"** on the Sign-in screen, enters or scans the code, sees a confirmation of their profile and coach, and creates an email + password login (or signs into an existing Shuttl account to link it) — while public registration stays closed to uninvited strangers.
- Once signed in, an invited player lands in a **focused Player view** straight on their own `Profile | Matches | Drills` page: everything from the coach (profile details, cloud matches, scores/points, rally clips, coach annotations, and track-driven cloud analysis: **Heatmap, Base, and Drills**) is read-only, while the **Drills** tab is interactive and **syncs completed workout sessions back to Supabase** so the coach sees what the player has been practising.

### Goals
- Coach can generate, view, copy, share (text + QR), regenerate, and cancel a single-use invite code for any unlinked player, and revoke an active player's access at any time without deleting the player profile, matches, or drill history.
- Uninvited users still cannot self-register; a valid invite code gates account creation atomically server-side without requiring SMTP/email infrastructure or Universal Links.
- An existing Shuttl user (including a player invited by a second coach) can link a new coach profile using an invite code without creating a duplicate account.
- Invited players (`account_role = 'player'`) are enforced read-only server-side across coach tables and the `videos` storage bucket (outside `drill_session_logs`) so they cannot log into the web app or API to create matches or trigger paid GPU analysis jobs.
- Invited players land straight on their own profile (`Profile | Matches | Drills`), with a header switcher when linked to 2+ coaches and a clean recovery screen if access is revoked.
- Linked players see all **cloud** matches assigned to them — including score-only matches, video-only matches, bound matches, rally clips, coach annotations, and on-demand downloaded cloud pose analysis (**Heatmap, Base, Drills**) — strictly read-only.
- Completed drill sessions (`DrillSessionLog`) sync offline-first through Supabase so both player and coach see up-to-date training logs on the player's `Drills` tab.
- All phone-local stores (`localVideos`, `localAnnotations`, `racketArmPrefs`, `drillProgress`, and cached pose tracks/skeletons) are isolated per signed-in user so switching between coach and player on a shared phone never leaks data.

### Non-Goals (This Pass)
- **Skeleton overlay for linked players / remote raw video streaming.** `SkeletonPanel` draws over the local raw recording (matching a coach on a second phone today, who gets Heatmap, Base, and Drills only). Full raw recordings in the `videos` storage bucket stay strictly owner-only.
- Automated email delivery / SMTP setup (`auth.admin.inviteUserByEmail`).
- Hosted web landing page, Universal Links (iOS), or Android App Links.
- Sign in with Apple, Google OAuth, or passwordless email OTP (can be layered onto the same `player_logins` model later).
- Push notifications or real-time live updates (pull-to-refresh + launch sync matches the rest of the app).
- Syncing phone-only (un-uploaded) videos, on-device tracks/skeletons, or local video annotations to the player.
- Letting players edit their profile name, playing hand, match scores, or coach annotations.
- Web app (`badminton-tracker`) changes.
- Cross-match aggregated metrics (Step 2) or separate coach recommendation items (Step 3).

### Anti-Goals
- **Re-opening public registration** or leaving orphan `auth.users` rows if invite redemption fails halfway.
- **Moving `owner_id` or copying storage objects** when linking/unlinking a player.
- **Exposing another player's profile, UUID, or matches** to a linked player (even when two of the coach's players played each other — the opponent's display name is resolved server-side, never their `players` row or UUID).
- **Keeping five copies of the "can read video" RLS predicate** drifting apart across tables and buckets.
- **Letting a player account accidentally push/overwrite or delete the coach's `score_logs`** from `ScoreLogsRepository`.
- **Two platform ports of invite validation, copy, match-list filtering, or drill sync.** All rules, copy, and sync live in `:shared`.

---

## 2. Approaches Considered

1. **Approach 1: Small Supabase Edge Function (`redeem-player-invite`) + Unified SQL/RLS Migration + Full KMP/iOS/Android *(Chosen by Coen)***
   - Postgres `player_invites` + `player_logins` tables and coach/authenticated-player RPCs handle invite creation, revocation, linking existing users, and access-status listing.
   - One small Deno Edge Function (`supabase/functions/redeem-player-invite/index.ts`, `verify_jwt = false`) handles unauthenticated code preview and atomic account creation via `auth.admin.createUser({ email_confirm: true, app_metadata: { account_role: 'player' } })` + `player_logins` link.
   - One unified SQL migration replaces the 5 duplicated "own or shared" checks with `can_read_video(video_id)`, enforces `account_role <> 'player'` on coach write policies, opens read access on `players`, `results` storage, and narrow RPCs (`list_linked_score_logs`, `get_cloud_pose_artifact`) for linked players, adds `drill_session_logs` with offline-first sync, and ships native QR display/scanning on both platforms.
   - *Trade-offs:* Keeps Supabase public sign-ups 100% closed, needs zero SMTP setup, makes user-creation + profile-linking atomic (with rollback on error), and cleanly tags player accounts in `app_metadata` so `seed_annotation_labels_on_signup` skips them. Requires deploying one Edge Function (`supabase functions deploy redeem-player-invite`) alongside `supabase db push`.
2. **Approach 2: Pure Postgres + Client `auth.signUp()` + Auth Hook *(Rejected)***
   - Enable public email sign-up in Supabase Auth (`enable_signup = true`, `enable_confirmations = false`), gate it with a manually configured `before_user_created` Dashboard hook, and redeem the invite in a second RPC after `signUp()`.
   - *Trade-offs:* Avoids deploying an Edge Function, but requires manual Dashboard hook wiring, breaks if email confirmation is ever enabled, and risks orphan unlinked `auth.users` rows if the network drops between `signUp()` and linking.
3. **Approach 3: Same Backend as Approach 1, Split into Two Milestones *(Not chosen)***
   - Ship core code invites + focused read-only Player view first, then `drill_session_logs` cloud sync + QR scanning in a second PR.
   - *Trade-offs:* Smaller incremental PRs, but delays the closed-loop workout sync and courtside QR experience Coen wants in the full flow.

---

## 3. Current State & Verified Constraints

Verified on `feat/exercise-suggestions` (`4766058d`):

- **Auth & Sign-in:** `AuthRepositoryImpl` (`shared/.../repo/AuthRepository.kt`) supports only `signInWith(Email)` and `signOut()`. `SignInCopy.FOOTNOTE` states registration is closed. `SupabaseFactory.kt` already installs `Auth`, `Postgrest`, `Storage`, and `Functions` (`client.functions`).
- **Players & Ownership:** `public.players(id, owner_id, name, racket_arm, created_at, updated_at)` has owner-only RLS (`20261031000000_player_profiles.sql`). `PlayersRepositoryImpl` filters published rows by `it.ownerId == owner`.
- **Matches & Read Predicates:**
  - The authoritative record for a match's player assignment is the bound `score_logs` row (oldest `created_at` where `s.video_id = v.id and s.owner_id = v.owner_id`) when one exists, else `videos` (`20261121000000_security_hardening_and_indexes.sql:64-95`).
  - Five duplicated "own or shared" predicates exist across `rally_clips` SELECT, `rally_annotations` SELECT, `storage.objects` SELECT (`clips`, `thumbnails`), and `list_match_metadata()`.
  - `videos`, `score_logs`, and the `results` storage bucket (`results/{owner}/{video_id}/poses.raw`, read by `CloudPoseRepository.kt:64-93`) are owner-only today.
  - `list_match_metadata()` currently masks `home_player_id` and `away_player_id` to `null` for non-owners.
  - `seed_annotation_labels_on_signup` (`20260824000000_annotation_labels.sql`) inserts 3 default coach labels on every `auth.users` INSERT.
- **Shared Match List & Read-Only Gating:**
  - `MatchListBuilder.kt` currently assumes every `ScoreLog` is owned (`scoreItem` hardcodes `isOwned = true`, `canShare = videoId != null`, `canEditPlayers = target != null`, `delete = BOUND_MATCH/SCORE_MATCH`), joins only clips owned by `currentUserId` (`ownedVideo()`), and places all non-owned clips into `shared` with `homePlayerId = null` and `delete = LEAVE_SHARE`.
  - `MatchScreen.kt:338` (`val editsDetails = log != null || ...`) and `MatchView.swift` similarly treat presence of a `ScoreLog` as implying ownership.
- **Drills & Local Stores:**
  - `DrillProgressRepository.kt` stores `DrillSessionLog` (`id`, `exerciseId`, `playerId`, `matchId`, `completedAtEpochMs`, `setsCompleted`, `setsTarget`, `durationSeconds`, `effort`, `note`) and custom `DrillPlan`s in un-scoped `Settings` keys (`drill_session_logs_v1`, `drill_custom_plans_v1`) with synthetic `"drill_<ms>_<n>"` IDs.
  - `LocalVideoRepository.kt`, `LocalAnnotationsRepository.kt`, and `RacketArmPreferenceRepository.kt` are also un-scoped in `Settings`; `RallyApp.sessionCacheCleaner` clears only `clips`, `videos`, `media`, and reloads `scoreLogs`.
- **Platform Capabilities:**
  - iOS has `CoreImage` (`CIQRCodeGenerator`) and `AVFoundation` (`AVCaptureMetadataOutput`) built in, and `NSCameraUsageDescription` is already in `iosApp/Sources/Info.plist`.
  - Android (`androidApp/build.gradle.kts`, `minSdk 26`, `compileSdk 36`) has no QR libraries yet; adding `com.google.zxing:core` (QR bitmap generation) and `com.google.android.gms:play-services-code-scanner` (zero-permission system scanner) keeps APK overhead minimal.

---

## 4. Backend Design (Supabase)

### 4.1 Schema Additions (`supabase/migrations/20261123000000_player_invites.sql`)

#### `public.player_logins`
Links a coach's player profile to a Shuttl login. At most one login per player profile; one login can link to many profiles across different coaches.

| Column | Type | Notes |
|---|---|---|
| `player_id` | `uuid primary key references public.players(id) on delete cascade` | Deleting a player cleanly unlinks them |
| `user_id` | `uuid not null references auth.users(id) on delete cascade` | The player's login |
| `linked_at` | `timestamptz not null default now()` | |

- Index: `player_logins_user_idx on public.player_logins (user_id)`.
- RLS: `SELECT` for `authenticated` where `user_id = auth.uid()` or `exists (select 1 from public.players p where p.id = player_id and p.owner_id = auth.uid())`. No direct `INSERT`/`UPDATE`/`DELETE` policies (writes go through `SECURITY DEFINER` RPCs / `service_role`).

#### `public.player_invites`
Single-use invite codes generated by a coach for a specific player profile.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid primary key default gen_random_uuid()` | |
| `player_id` | `uuid not null references public.players(id) on delete cascade` | |
| `coach_id` | `uuid not null default auth.uid() references auth.users(id) on delete cascade` | |
| `code` | `text not null unique` | 8 uppercase chars from unambiguous alphabet `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (~39.6 bits entropy; displayed as `XXXX-XXXX`) *(My call — see Section 8)* |
| `expires_at` | `timestamptz not null default (now() + interval '7 days')` | 7-day expiry *(My call)* |
| `redeemed_at` | `timestamptz null` | |
| `redeemed_by` | `uuid null references auth.users(id) on delete set null` | |
| `revoked_at` | `timestamptz null` | |
| `created_at` | `timestamptz not null default now()` | |

- Check constraint: `code ~ '^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$'`.
- Partial unique index: `player_invites_one_active_per_player_idx on public.player_invites (player_id) where redeemed_at is null and revoked_at is null`.
- RLS: `SELECT` only where `coach_id = auth.uid()`. No direct `INSERT`/`UPDATE`/`DELETE` policies.

#### `public.invite_rate_limits` (Service-Role Only)
Prevents brute-force guessing against the unauthenticated Edge Function across cold starts. *(My call.)*

| Column | Type | Notes |
|---|---|---|
| `bucket_key` | `text primary key` | Hashed client IP or global window key |
| `window_start` | `timestamptz not null` | 15-minute sliding/fixed bucket |
| `attempt_count` | `int not null` | Refuses above 20 attempts per 15 min per IP with HTTP 429 |

- RLS enabled with zero policies for `anon`/`authenticated`; accessed only by `service_role` inside the Edge Function via a helper RPC `check_invite_rate_limit(p_bucket_key text, p_max_attempts int, p_window_seconds int)`.

#### `public.drill_session_logs`
Cloud-synced workout sessions for a player, readable and writable by both the owning coach and the linked player.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid primary key default gen_random_uuid()` | Client-generated UUID for offline-first idempotency |
| `player_id` | `uuid not null references public.players(id) on delete cascade` | |
| `logged_by_user_id` | `uuid not null default auth.uid() references auth.users(id) on delete cascade` | Who recorded the session (player or coach) |
| `exercise_id` | `text not null check (char_length(btrim(exercise_id)) between 1 and 80)` | Matches `Exercise.id` slug |
| `match_id` | `uuid null` | Optional soft reference to `videos.id` or `score_logs.id` |
| `completed_at` | `timestamptz not null` | |
| `sets_completed` | `int not null check (sets_completed >= 0)` | |
| `sets_target` | `int not null check (sets_target >= 1)` | |
| `duration_seconds` | `int not null check (duration_seconds >= 0)` | |
| `effort` | `text not null check (effort in ('easy', 'just_right', 'hard'))` | Wire names for `DrillEffort` |
| `note` | `text not null default '' check (char_length(note) <= 500)` | |
| `created_at` | `timestamptz not null default now()` | |

- Index: `drill_session_logs_player_completed_idx on public.drill_session_logs (player_id, completed_at desc, id)`.
- Helper `public.can_access_player(p_player_id uuid) returns boolean` (`security definer`, `stable`, `set search_path = pg_catalog, public`): true when caller owns `p_player_id` in `public.players` OR has `(p_player_id, auth.uid())` in `public.player_logins`.
- RLS on `drill_session_logs`:
  - `SELECT`: `public.can_access_player(player_id)`
  - `INSERT`: `public.can_access_player(player_id) and logged_by_user_id = auth.uid()`
  - `DELETE`: `logged_by_user_id = auth.uid()` OR caller owns `player_id` in `public.players` (coach can clean up any session on their player; player can delete their own sessions). *(My call.)*
  - No `UPDATE` policy (sessions are immutable once logged, matching today's UI).

### 4.2 Unified Access Predicates, Write Role Guards & Widened Read Policies

All helper functions are `security definer`, `stable`, `set search_path = pg_catalog, public`, with `revoke all ... from public; grant execute ... to authenticated;` (except `is_coach_account`, which needs only `security invoker`):

1. **`public.is_coach_account() returns boolean`** (`language sql stable`):
   ```sql
   select coalesce(auth.jwt() -> 'app_metadata' ->> 'account_role', '') <> 'player';
   ```
   Returns `true` for all legacy/existing coach JWTs (which omit `account_role`) and `false` for player accounts created via `redeem-player-invite`. An admin can promote a player to coach at any time by updating `app_metadata.account_role`.
2. **`public.is_linked_player_on_video(p_video_id uuid) returns boolean`**:
   Checks the **authoritative match record** for `p_video_id` (matching Section 2.4 of `2026-10-31-player-profiles-design.md`):
   - Find the oldest bound score log (`select s.home_player_id, s.away_player_id from public.score_logs s join public.videos v on v.id = p_video_id where s.video_id = v.id and s.owner_id = v.owner_id order by s.created_at limit 1`).
   - If found, return `exists (select 1 from public.player_logins pl where pl.user_id = auth.uid() and pl.player_id in (s.home_player_id, s.away_player_id))`.
   - Else check `v.home_player_id` / `v.away_player_id` against `player_logins` for `auth.uid()`.
3. **`public.can_read_video(p_video_id uuid) returns boolean`**:
   True if for `videos v where v.id = p_video_id`:
   - `v.owner_id = auth.uid()`, OR
   - `exists (select 1 from public.match_shares ms where ms.video_id = v.id and ms.shared_with_user_id = auth.uid() and ms.granted_by = v.owner_id)`, OR
   - `public.is_linked_player_on_video(v.id)`.

**Server-side player write guards (protecting mobile, web app, and paid GPU pipeline):**
- Add `and public.is_coach_account()` to `INSERT` (and `UPDATE`/`DELETE` where owner-writable) RLS policies on:
  - `public.players`
  - `public.videos`
  - `public.score_logs`
  - `public.annotation_labels`
  - `public.rally_clips`
  - `public.rally_annotations`
  - `storage.objects` (`videos` bucket upload/write policy)
- `public.drill_session_logs` intentionally does **not** require `is_coach_account()` so linked players can log and delete their own workouts.

**Read policy updates:**
- `public.players` SELECT: `owner_id = auth.uid() or exists (select 1 from public.player_logins pl where pl.player_id = id and pl.user_id = auth.uid())`.
- `public.videos` SELECT: **remains strictly owner-only (`owner_id = auth.uid()`)!** Linked players never read raw `videos` rows (avoiding leaks of opponent `home_player_id`/`away_player_id` UUIDs, `storage_path`, or `player_labels`); instead `CloudPoseRepository.artifact()` calls the narrow `get_cloud_pose_artifact(p_video_id)` RPC below. *(Matches Anti-Goal #3.)*
- `public.score_logs` SELECT: **remains strictly owner-only (`owner_id = auth.uid()`)!** Linked players read their coached score logs through `list_linked_score_logs()` below instead of raw table SELECT — preventing opponent `player_id` UUID leaks on score-only coach-vs-coach matches and preventing `ScoreLogsRepository`'s owner pull from ever mixing writable and read-only rows. *(My call — see Section 8.)*
- `public.rally_clips` SELECT: `public.can_read_video(video_id)`.
- `public.rally_annotations` SELECT: `exists (select 1 from public.rally_clips rc where rc.id = clip_id and public.can_read_video(rc.video_id))`.
- `storage.objects` SELECT (`clips`, `thumbnails`): replace existing `shared viewers` policies with `can_read_video(rc.video_id)` while preserving the UID-prefix check `(storage.foldername(storage.objects.name))[1] = rc.owner_id::text`.
- `storage.objects` SELECT (`results` bucket — new policy `"storage results: linked players"`):
  ```sql
  bucket_id = 'results'
  and exists (
      select 1 from public.videos v
       where v.results_meta->>'poses_artifact_path' = storage.objects.name
         and (storage.foldername(storage.objects.name))[1] = v.owner_id::text
         and public.is_linked_player_on_video(v.id)
  )
  ```
- `storage.objects` (`videos` bucket): remains strictly owner-only (`videos` raw footage is not exposed to linked players).
- `public.list_match_metadata()` (dropped and recreated to add `has_cloud_pose boolean`):
  - WHERE clause uses `public.can_read_video(v.id)`.
  - Returns `has_cloud_pose boolean`: `coalesce(v.results_meta->>'poses_artifact_path', '') <> ''` when `v.owner_id = auth.uid() or public.is_linked_player_on_video(v.id)`, else `false` (ad-hoc `match_shares` recipients still do not get pose artifacts).
  - For `home_player_id` and `away_player_id`, returns the UUID when `v.owner_id = auth.uid()` **OR** when that UUID belongs to `auth.uid()` in `player_logins` (resolved from the authoritative record — bound score log if present, else video — while still masking another player's UUID to `null` and returning their resolved display name in `home_names`/`away_names`).
- `public.seed_annotation_labels()` trigger update:
  - Skip inserting the 3 default coach labels when `coalesce(new.raw_app_meta_data ->> 'account_role', '') = 'player'`.

### 4.3 RPCs (`SECURITY DEFINER`, `set search_path = pg_catalog, public, auth`, granted to `authenticated`)

| RPC | Caller | Behaviour & Error Codes |
|---|---|---|
| `create_player_invite(p_player_id uuid)` | Coach | Verifies `is_coach_account()` and caller owns `p_player_id` (`P0002` `not_owner`), verifies player is not already linked (`P0005` `already_linked`), revokes any prior active invite for `p_player_id`, generates a unique 8-char Crockford code (`gen_random_bytes` modulo 31), inserts `player_invites` expiring in 7 days, returns `(invite_id, code, expires_at)`. |
| `revoke_player_invite(p_player_id uuid)` | Coach | Verifies ownership, stamps `revoked_at = now()` on any active invite for `p_player_id`. Idempotent. |
| `unlink_player_login(p_player_id uuid)` | Coach | Verifies ownership, deletes `player_logins` row for `p_player_id`, revokes any stray active invites. Keeps `players`, matches, and `drill_session_logs` intact. |
| `list_player_access_states()` | Coach | Returns one row per player owned by `auth.uid()`: `(player_id, status ['uninvited'\|'pending'\|'linked'], linked_email, linked_at, pending_code, pending_expires_at)`. |
| `list_my_player_profiles()` | Player (or dual-role) | Returns every row in `player_logins` for `auth.uid()` joined with `players` and coach `auth.users`: `(player_id, coach_id, coach_email, name, racket_arm, linked_at)` ordered by `linked_at asc`. |
| `list_linked_score_logs()` | Player (or dual-role) | Returns all `score_logs` where `home_player_id` or `away_player_id` is in `auth.uid()`'s `player_logins` (excluding rows where `owner_id = auth.uid()`). Resolves any opponent profile ID to `home_players`/`away_players = array[opponent.name]` and masks opponent `player_id` to `null`, while keeping the caller's own `player_id` intact so `MatchList.forPlayer` and `CourtSideNames` work seamlessly. |
| `get_cloud_pose_artifact(p_video_id uuid)` | Coach or Linked Player | Returns `(poses_artifact_path text, poses_frame_count int, manual_court_keypoints jsonb)` for `p_video_id` ONLY when `v.owner_id = auth.uid() or public.is_linked_player_on_video(v.id)` and `poses_artifact_path` + `manual_court_keypoints` are non-null (0 rows otherwise). Never exposes `home_player_id`, `away_player_id`, `storage_path`, or `player_labels`. |
| `redeem_player_invite_authenticated(p_code text)` | Signed-in user | Normalizes `p_code`, locks matching `player_invites` row `FOR UPDATE`, validates active + unexpired (`P0006` `invalid_or_expired`), rejects `coach_id = auth.uid()` (`P0004` `cannot_invite_self`), rejects if `player_id` is already linked (`P0005` `already_linked`), inserts `player_logins`, stamps `redeemed_at`/`redeemed_by`, returns `(player_id, player_name, racket_arm, coach_email)`. |

### 4.4 Edge Function (`supabase/functions/redeem-player-invite/index.ts`)

Deployed with `verify_jwt = false` (configured in `supabase/config.toml` under `[functions.redeem-player-invite]`) and uses `SUPABASE_URL` + `SUPABASE_SERVICE_ROLE_KEY`.

**Security & rate limiting:**
- Extracts caller IP (`x-forwarded-for` / `cf-connecting-ip`), hashes it with SHA-256, and checks `check_invite_rate_limit(bucket, 20, 900)` before any code lookup (returns HTTP `429` `{ error: "rate_limited" }` if exceeded).
- Normalizes `code` by stripping whitespace/hyphens and uppercasing. Invalid format, unknown code, expired code, revoked code, and already-used code ALL return the identical HTTP `400` `{ error: "invalid_or_expired" }` so valid codes cannot be enumerated.

**Actions (`POST /functions/v1/redeem-player-invite`):**
1. **`{ action: "preview", code }`**:
   - Looks up active, unexpired invite joined with `players` and coach's `auth.users.email`.
   - Returns HTTP `200` `{ playerName, racketArm, coachEmail }`. (Never returns `player_id`, `coach_id`, or invite `id`.)
2. **`{ action: "redeem", code, email, password }`**:
   - Validates `email` format and `password.length >= 8` (`400` `{ error: "weak_password" }` / `{ error: "invalid_email" }`).
   - Re-verifies the invite is active and unexpired (`400` `{ error: "invalid_or_expired" }`) and `player_logins` does not already hold `player_id` (`409` `{ error: "already_linked" }`).
   - Calls `supabaseAdmin.auth.admin.createUser({ email, password, email_confirm: true, app_metadata: { account_role: "player" } })`:
     - If Supabase reports an existing email (`email_exists` / already registered), returns HTTP `409` `{ error: "email_exists" }` **without consuming the invite** so the player can immediately tap *"Sign in to link"*.
   - Atomically claims the invite and inserts `player_logins(player_id, newUserId)` via a service-role SQL RPC `complete_player_invite_redemption(p_invite_id uuid, p_user_id uuid)` (which locks the invite row `FOR UPDATE` to prevent two concurrent requests redeeming the same code!):
     - If claiming fails (race condition / revoked in-flight), rolls back by calling `supabaseAdmin.auth.admin.deleteUser(newUserId)` and returns HTTP `400` `{ error: "invalid_or_expired" }`.
   - Returns HTTP `200` `{ playerId, playerName, coachEmail }`.
   - The mobile app immediately calls `auth.signInEmail(email, password)` to obtain a standard persisted Supabase session.

---

## 5. Shared Kotlin Layer (`:shared`)

### 5.1 Auth Role Detection
- Add `enum class AccountRole { COACH, PLAYER }` in `shared/.../repo/AuthState.kt`.
- Extend `AuthRepository`:
  - `fun accountRole(): AccountRole` — reads `client.auth.currentUserOrNull()?.appMetadata?.get("account_role")?.jsonPrimitive?.contentOrNull == "player"` → `PLAYER`, else `COACH`. Available immediately from the persisted JWT on cold start and offline.

### 5.2 Invite & Player Access Models and Repository
New package `shared/.../invites/`:
- `PlayerInviteModels.kt` (plain Swift-friendly data classes & enums):
  - `enum class PlayerAccessStatus { UNINVITED, PENDING, LINKED }`
  - `data class PlayerAccessState(val playerId: String, val status: PlayerAccessStatus, val linkedEmail: String?, val linkedAt: Instant?, val pendingCode: String?, val pendingExpiresAt: Instant?)`
  - `data class CreatedInvite(val inviteId: String, val code: String, val expiresAt: Instant)`
  - `data class InvitePreview(val playerName: String, val racketArm: RacketArm?, val coachEmail: String)`
  - `data class LinkedPlayerProfile(val playerId: String, val coachId: String, val coachEmail: String, val name: String, val racketArm: RacketArm?, val linkedAt: Instant)`
  - `enum class InviteErrorKind { INVALID_OR_EXPIRED, ALREADY_LINKED, EMAIL_EXISTS, WEAK_PASSWORD, INVALID_EMAIL, CANNOT_INVITE_SELF, RATE_LIMITED, NETWORK, UNKNOWN }`
  - `class InviteException(val kind: InviteErrorKind, message: String)`
- `InviteCodeFormat.kt`:
  - `normalizeInviteCode(raw: String): String` — strips spaces, hyphens, and optional `shuttl-invite:` QR prefix; uppercases; validates 8-char Crockford alphabet.
  - `formatInviteCode(code: String): String` — formats `"K7M4QP9R"` → `"K7M4-QP9R"`.
  - `qrPayloadForCode(code: String): String` — returns `"shuttl-invite:${normalizeInviteCode(code)}"`.
  - `parseQrInvitePayload(raw: String): String?` — accepts `"shuttl-invite:XXXXXXXX"` (or bare formatted 8-char code) and rejects unrelated QR strings.
- `PlayerAccessRepository.kt` (wired on `RallyApp.playerAccess`):
  - `val accessByPlayerId: StateFlow<Map<String, PlayerAccessState>>` (coach side, owner-scoped cache).
  - `val linkedProfiles: StateFlow<List<LinkedPlayerProfile>>` (player side, owner-scoped cache, `loaded: StateFlow<Boolean>` so cold start doesn't flash the unlinked empty screen before first refresh settles).
  - Coach calls: `refreshAccessStates()`, `createInvite(playerId)`, `revokeInvite(playerId)`, `unlinkPlayer(playerId)`.
  - Player calls: `refreshLinkedProfiles()`, `previewInvite(code)`, `redeemInviteNewAccount(code, email, password)` (Edge Function + `auth.signInEmail`), `redeemInviteExistingAccount(code)` (RPC + `refreshLinkedProfiles`).
- `PlayerInviteCopy.kt`: single source of truth for all UI strings, error messages, and the native share-sheet text (`shareInviteText(playerName, formattedCode)`).

### 5.3 Match List, Cloud Pose Discovery, Score Logs & Read-Only Support
- `MatchMetadata` (`shared/.../model/MatchMetadata.kt`): add `@SerialName("has_cloud_pose") val hasCloudPose: Boolean = false`.
- `CloudPoseRepositoryImpl.artifact(videoId)`: switches from `from("videos").select(...)` to `client.postgrest.rpc("get_cloud_pose_artifact", mapOf("p_video_id" to videoId))`, preserving the exact `CloudPoseArtifact?` return type so `CloudPoseCoordinator` works unchanged for both coaches and linked players.
- `ScoreLog` (`shared/.../scoring/ScoreLog.kt`): add optional `@SerialName("owner_id") val ownerId: String? = null` (backward compatible with existing caches).
- `ScoreLogsRepository`:
  - `sync()` filters the owner pull with `eq("owner_id", owner)` so coach writable rows stay isolated.
  - Add `val linkedLogs: StateFlow<List<ScoreLog>>` + `suspend fun refreshLinkedLogs(): Result<Unit>` calling `rpc("list_linked_score_logs")`, stored in an owner-scoped cache envelope (`linked_score_logs_cache`), cleared on sign-out / account switch, never marked dirty or pushed.
- `MatchListInput` & `MatchListBuilder`:
  - `MatchListInput` gains `linkedPlayerIds: Set<String> = emptySet()` and `linkedScoreLogs: List<ScoreLog> = emptyList()`.
  - `MatchList` gains `coached: List<MatchListItem> = emptyList()`.
  - `analysesFor(...)` adds `MatchAnalysis.HEATMAP` when `heatmapId != null || meta?.hasCloudPose == true` (setting `heatmapId = heatmapId ?: videoId.takeIf { meta?.hasCloudPose == true }`), so the match card and `MatchPageSection` expose the `Analysis` tab up front on any device that hasn't downloaded `poses.raw` yet (benefiting both linked players and coaches on a second phone!).
  - Linked score logs + cloud clips/metadata matching `linkedPlayerIds` build into `coached` (joining their cloud clips even though `clip.ownerId != currentUserId`!), with:
    - `isOwned = false`, `canShare = false`, `canEditDetails = false`, `canEditPlayers = false`, `canAnalyzeAgain = false`, `canRemoveFromPhone = false`, `delete = MatchDelete.NONE`.
    - Preserving the linked player's `homePlayerId` / `awayPlayerId` and resolved `homeNames` / `awayNames` (from `list_linked_score_logs()` or `list_match_metadata()`), so `CourtSideNames` names both sides and supplies the player's `racketArm` for Drills!
  - `MatchList.forPlayer(playerId)` searches `mine` plus `coached` (disjoint by ID: coach-owned players appear in `mine`, linked players appear in `coached`). `playerSummary()` automatically works identically for both coach and player!

### 5.4 Cloud-Synced `DrillProgressRepository`
- New sessions use `randomUuid()` instead of `"drill_<ms>_<n>"` and store `loggedByUserId: String? = null`.
- Cache format upgrades to an owner-scoped envelope `CachedDrillProgress(ownerId, logs: List<StoredDrillLog(log, dirty)>, pendingDeletes: List<String>, savedPlans: Map<String, DrillPlan>)` (mirroring `ScoreLogsRepository`'s proven offline-first design):
  - **One-time migration** for existing users: if legacy `drill_session_logs_v1` / `drill_custom_plans_v1` exist and no owner-scoped cache exists yet, adopt them under the first authenticated user's ID (converting legacy `"drill_..."` IDs with a valid UUID `playerId` to fresh UUIDs marked `dirty = true` so existing coach drill logs upload cleanly).
- `suspend fun sync(): Result<Unit>` (serialized with `Mutex`):
  1. Push dirty logs where `playerId != null` via `upsert` to `drill_session_logs` (ignoring logs with `playerId == null` which remain local-only).
  2. Delete any `pendingDeletes` from `drill_session_logs`.
  3. Pull all visible rows via `selectAllPages` ordered by `completed_at desc, id asc` and merge with any locally dirty rows.
- Triggers `sync()` on app refresh, pull-to-refresh, and fire-and-forget right after `recordSession` / `deleteSession` (failures keep the row dirty / pending-delete for the next sync, so logging drills works 100% offline in a sports hall!).

### 5.5 Shared-Phone Local Store Account Isolation
Fixes the security/data-isolation hazard noted in `2026-10-31-player-profiles-design.md` Section 6:
- `LocalVideoRepository`, `LocalAnnotationsRepository`, `RacketArmPreferenceRepository`, and `DrillProgressRepository` all become owner-scoped by `currentOwnerId()` (storing per-owner keys or owner-stamped envelopes, migrating any pre-upgrade un-scoped entries once to the first signed-in account so existing coaches keep every local video/note/preference).
- `RallyApp.sessionCacheCleaner` reloads all of these stores plus clears `playerAccess`, `clips`, `videos`, `media`, and `scoreLogs` whenever the signed-in user changes or signs out.
- On-device downloaded cloud pose track/skeleton caches (`PlayerTrackStore` / `SkeletonStore`) are stored in per-user subdirectories (or cleared on user switch when `videoId` is not owned locally) so a second user signing in on the same phone cannot read a prior user's cached pose files.

---

## 6. Android & iOS UX & Navigation

Both platforms follow `PlayerInviteCopy` and existing `Shuttl` design tokens (light, dark, and accessibility large text).

### 6.1 Coach Side
- **Players list (`PlayersScreen`):**
  - Each player row displays a subtle status cue when `accessState` is `LINKED` (small green connected dot/icon) or `PENDING` (subtle amber dot), keeping the row clean.
  - **Dual-role edge case:** If a `COACH` account has also redeemed an invite from another coach (`linkedProfiles.isNotEmpty()`), render a compact **"My profile"** card above the coach's players list that opens their linked profile in read-only player view. *(My call.)*
- **Player page — Profile tab (`PlayerScreen` / `PlayerView`):**
  - Directly below the 3 stat tiles (Option A), render a **`Player access`** card (`bgSecondary`, matching Settings cards):
    - `UNINVITED`: *"Invite [Name] to Shuttl"* + short explanation + primary **`Invite [Name]`** button.
    - `PENDING`: amber `Invite pending` badge + formatted code `K7M4-QP9R` + `"Expires [date]"` + **`Show / Share code`** button + subtle `Cancel` action.
    - `LINKED`: green `● Connected` badge + `linkedEmail` + `"Linked [date]"` + destructive **`Remove access`** button (with confirmation dialog explaining Marco's profile and matches stay untouched).
- **Invite Bottom Sheet (`PlayerInviteSheet`):**
  - Opens when tapping `Invite [Name]` (auto-creates a code if none active) or `Show / Share code` (reuses active code).
  - Shows:
    1. Large monospace code box (`K7M4-QP9R`, tap to copy → toast `"Code copied"`).
    2. Scannable QR code (`shuttl-invite:K7M4QP9R`) — iOS `CoreImage` `CIQRCodeGenerator` scaled cleanly with `nearest` interpolation on a white quiet-zone card; Android `com.google.zxing:core` `QRCodeWriter` rendered to an `ImageBitmap`.
    3. Expiry caption: `"Single-use · expires in X days"`.
    4. Primary **`Share invite`** button opening the native share sheet (`UIActivityViewController` / `Intent.ACTION_SEND`) with prefilled WhatsApp/Messages text.
    5. Secondary **`Generate new code`** button (replaces the active code immediately) and subtle **`Cancel invite`** action.
    6. Conditional honesty banner (only if `forPlayer(playerId)` contains matches where `where == PHONE`, i.e. not uploaded): *"N phone-only matches won't appear on [Name]'s phone until uploaded."*
- **Drills tab (Coach):** pulls synced `drill_session_logs` so sessions logged on the player's phone appear alongside coach-logged sessions, with a subtle `"Logged by [Name]"` / `"Logged by you"` cue on session history rows.

### 6.2 Player Onboarding (Signed-Out Flow)
- **Sign-in screen (`SignInScreen` / `SignInView`):**
  - Retains the existing email + password form at top.
  - Adds a clean divider *"Invited by a coach?"* + secondary button **`I have an invite code`**.
  - Updates footnote to `"Accounts are invite-only. Ask your coach for a code."`
- **Join with Invite screen (`JoinInviteScreen` / `JoinInviteView`):**
  - **Code entry:** Large uppercase input auto-formatting `XXXX-XXXX` (paste-friendly), plus top-bar **`Scan QR`** button:
    - iOS: lightweight sheet wrapping `AVCaptureSession` + `AVCaptureMetadataOutput` (`.qr`), using the existing camera permission.
    - Android: `GmsBarcodeScanning.getClient(context).startScan()` (zero camera permission prompt; gracefully hidden/disabled with a snackbar if Google Play Services isn't available).
  - **Verification:** Submitting (or scanning) an 8-char code calls `previewInvite(code)`. Invalid/expired codes show an inline `ErrorBanner`.
  - **Verified state (same screen smoothly reveals the profile card + credentials):**
    - Profile preview card: `PlayerAvatar` + player name + playing hand (if set) + `"Invited by coach@..."` + green `Verified` pill.
    - Mode 1 (default): **Create account** — Email field + Password field (min 8 chars, show/hide toggle) + primary **`Create account & continue`** button.
    - Mode 2 (toggle link below: *"Already have an account? Sign in to link"*): **Sign in & link** — Email + Password + **`Sign in & link profile`**. If Mode 1 returns `EMAIL_EXISTS`, display a helpful banner offering one-tap switch to Mode 2 with the email already filled in!
    - On success: session becomes authenticated and `AuthGate` / `RootView` immediately routes to Focused Player Home.

### 6.3 Focused Player Home (`AccountRole.PLAYER`)
- **Root routing (`AuthGate.kt` / `RootView.swift`):**
  - `AccountRole.COACH` → existing Coach `Players` root.
  - `AccountRole.PLAYER` → `PlayerHomeScreen` / `PlayerHomeView`:
    - While initial `linkedProfiles` haven't settled and cache is empty → centered spinner (never flashes the unlinked screen).
    - **≥ 1 linked profile:**
      - Top bar: `SHUTTL.` wordmark + Settings gear `⚙` (no coach background-work indicator, no `+` FAB, no overflow Rename/Hand/Delete menu).
      - Header row: `PlayerAvatar` + Player name + `"Coach · coach@..."` subtitle. When `linkedProfiles.size > 1`, tapping this row opens a profile switcher menu (`Marco · coach1@...`, `Marco · coach2@...`) using the same switcher pattern as `PlayerView`.
      - Pill tabs: **`Profile | Matches | Drills`**:
        - **Profile:** Playing hand (if set), 3 stat tiles (`Matches`, `Rallies`, `Last match`), plus a subtle `"Coached by coach@..."` info row. Pull-to-refresh syncs profiles, matches, clips, score logs, and drill logs.
        - **Matches:** Existing `MatchesList` filtered to `selectedProfile.playerId` (with filter chips & sort, zero `+` FAB, zero row delete/edit/share actions).
        - **Drills:** Existing `PlayerDrillsTab` with full guided drill runner + session logging, writing to `DrillProgressRepository` and syncing to Supabase.
    - **0 linked profiles (after load settles — e.g. coach revoked access or deleted the profile):**
      - Clean empty state: *"No active coach profile"* + *"Ask your coach for an invite code to view your profile."* + primary **`Enter invite code`** button (opens in-app code redemption sheet) + Settings gear.
- **Read-Only Match & Clip Audit (both platforms):**
  - Gate EVERY write control on `matchItem.isOwned` (never `log != null` alone!):
    - `MatchScreen` / `MatchView`: hide Share button, Edit details / Who's playing, Delete match, Attach video, Remove video, Re-analyze, Resume scoring, Edit score.
    - `ClipDetailScreen` / `ClipDetailView`: hide Add annotation button and swipe-to-delete annotation (already gated on `isOwner`, verified for coached clips).
    - `AnalysisSection` (Android & iOS):
      - **On-demand cloud pose download:** When opened for a cloud match where `hasCloudPose == true` and `PlayerTrackStore` does not yet hold a cached track for that `videoId`, automatically invokes `CloudPoseCoordinator.install(videoId)` showing a clean progress bar (and a `Retry` button if offline/failed). Once cached to disk (inside the current user's isolated cache directory), subsequent opens are instant and work offline. This same on-demand path also works for a coach opening a cloud-analysed match on a second phone. *(My call — see Section 8.)*
      - Renders **Heatmap, Base, and Drills** (with the guided drill runner); **Skeleton** remains hidden because `hasVideo == false` (no raw recording on the player's phone).
      - For `CourtSideNames`, passes the player's `LinkedPlayerProfile` so their `racketArm` and side name populate identically to the coach's view!
- **Player Settings (`SettingsScreen` / `SettingsView`):**
  - Hide coach `Labels` row when `accountRole == PLAYER`.
  - Add **`Join another coach`** row (opens the in-app invite code sheet calling `redeemInviteExistingAccount`).
  - Keep Dark theme toggle, `Signed in as`, `Sign out`, and app version.

---

## 7. Verification Plan

1. **Database & RLS (`supabase/tests/player_invites_test.sql`, run via `supabase/tests/run.sh`):**
   - Coach creates, regenerates (revoking prior active code), and revokes an invite; stranger cannot create/read/revoke another coach's invite; already-linked player cannot create invite.
   - `redeem_player_invite_authenticated`: happy path links `player_logins` and marks invite redeemed; rejects expired, revoked, already-redeemed, self-invite, and already-linked player.
   - Read access for linked player: can SELECT own `players` row, `rally_clips`, `rally_annotations`, `list_match_metadata()` (sees `has_cloud_pose`, sees own `player_id`, masks opponent's `player_id`, resolves both names), `list_linked_score_logs()` (sees score-only match with opponent name resolved and opponent `player_id` masked), `get_cloud_pose_artifact(video_id)`, and `storage.objects` (`clips`, `thumbnails`, `results` with UID-prefix enforcement). Cannot directly `SELECT` `public.videos`, `public.score_logs`, or `storage.objects` in the `videos` bucket.
   - Server-side `account_role = 'player'` write guard: a JWT with `app_metadata: {"account_role": "player"}` is denied INSERT/UPDATE/DELETE on `players`, `videos`, `score_logs`, `annotation_labels`, `rally_clips`, `rally_annotations`, and `storage.objects` (`videos` bucket), while legacy coach JWTs without `app_metadata` continue passing unchanged.
   - Isolation: linked player CANNOT read coach's other players, unassigned matches, or other players' matches.
   - Unlinking (`unlink_player_login`) and deleting player immediately revoke all read access while keeping coach's matches intact.
   - `drill_session_logs`: both coach and linked player can INSERT and SELECT; stranger cannot; player cannot delete coach-logged session; coach can delete any session on their player.
   - `seed_annotation_labels_on_signup`: skips seeding when `raw_app_meta_data->>'account_role' = 'player'`, still seeds for normal/coach signups.
2. **Edge Function (`supabase/functions/redeem-player-invite/`):**
   - Deno unit tests for `preview` and `redeem` (invalid format, expired, rate-limited, `email_exists` does not consume code, happy path creates user with `account_role: 'player'` and links profile, rollback deletes auth user if claim fails).
3. **Shared Kotlin (`./gradlew :shared:jvmTest`):**
   - `InviteCodeFormatTest`: normalization, hyphen formatting, Crockford validation, QR payload parsing & rejection of non-Shuttl QR codes.
   - `PlayerAccessRepositoryTest` (MockEngine): coach states, create/revoke/unlink, preview/redeem error mapping (`email_exists`, `invalid_or_expired`, `rate_limited`, `already_linked`), owner-scoped cache across account switches.
   - `MatchListBuilderTest`: coached score-only, video-only, and bound matches land in `coached` with `isOwned = false`, `delete = NONE`, correct `forPlayer(id)` & `playerSummary`, and ad-hoc `match_shares` unchanged.
   - `DrillProgressRepositoryTest`: offline logging, dirty push, pending delete, remote merge, legacy-key one-time migration, and strict owner isolation on account switch.
   - Local store isolation tests (`LocalVideoRepository`, `LocalAnnotationsRepository`, `RacketArmPreferenceRepository`) across sign-out and account switch.
4. **Android (`./gradlew :androidApp:testDebugUnitTest`) & iOS (`xcodebuild test`):**
   - ViewModel / model tests for `JoinInvite`, `PlayerAccess`, `PlayerHome` routing (coach vs linked player vs unlinked player vs multi-coach), and read-only match gating.
   - Layout check (light, dark, large text) on Invite card, Invite sheet + QR, Join screen, Focused Player Home, and Unlinked screen.
5. **Owner Deployment & Real-Device Smoke Test (owed to Coen):**
   - `supabase db push` (migration) and `supabase functions deploy redeem-player-invite` (verify `verify_jwt = false`).
   - Two phones (or sign-out/sign-in on one phone): Coach creates invite for Marco → Marco scans QR / enters code → creates account → lands on Marco's profile → opens cloud match (score, clips, annotations, on-demand Analysis download → Heatmap/Base/Drills, no Skeleton) confirming zero edit buttons → completes a guided drill → Coach pulls to refresh on Marco's Drills tab and sees the completed session → Coach taps Remove access → Marco refreshes and lands on the Unlinked recovery screen.

---

## 8. Calls Made (For Coen to Check)

Decisions filled in to keep the design complete — any of these can be overridden before planning:

1. **8-character Crockford invite code (`XXXX-XXXX`, e.g. `K7M4-QP9R`), 7-day expiry, single-use:** Avoids ambiguous `0/O/1/I/L` characters, gives ~40 bits of entropy against guessing (paired with IP rate-limiting), and stays easy to type courtside.
2. **QR payload prefix (`shuttl-invite:K7M4QP9R`):** Lets the in-app scanner cleanly ignore random QR codes while still accepting a bare 8-char code if typed/pasted.
3. **Narrow RPCs for `score_logs` (`list_linked_score_logs()`) and pose artifacts (`get_cloud_pose_artifact()`), keeping raw `score_logs` and `videos` SELECT owner-only:** Resolves opponent names without ever leaking another player's UUID, `storage_path`, or `player_labels`, and guarantees `ScoreLogsRepository`'s offline upsert queue can never accidentally mutate a coach's score log.
4. **On-demand auto-download for cloud pose artifacts (`poses.raw`) when opening `Analysis`:** `list_match_metadata()` exposes `has_cloud_pose` so the Analysis tab appears up front without eager downloads; opening `Analysis` downloads and caches the track once with a progress bar + retry (also unlocking cloud analysis for coaches on a second phone).
5. **Dual-role fallback for coaches who also redeem a player invite:** A coach keeps Coach Home and gets a compact `"My profile"` card above their Players list so they are never locked out of either role.
6. **`drill_session_logs` deletion rule:** Players can delete sessions they logged themselves; coaches can delete any session on their player.
7. **IP rate-limiting table (`invite_rate_limits`, 20 attempts / 15 min):** Stored in Postgres so brute-force protection holds even across Edge Function cold starts.
8. **Legacy local data migration:** Existing on-device videos, annotations, racket-arm prefs, and drill logs automatically migrate to the first signed-in account on upgrade so existing coaches lose nothing when account isolation turns on.
9. **Android QR libraries:** `com.google.zxing:core` (QR generation) + `com.google.android.gms:play-services-code-scanner` (zero-permission scanning, graceful fallback to code typing on devices without Play Services); iOS uses built-in `CoreImage` + `AVFoundation` with zero new dependencies.

---

## 9. Left to the Builder

- Exact Compose / SwiftUI spacing, elevation, and icon choices for the `Player access` card, Invite bottom sheet, Join screen, and Unlinked screen using existing `Shuttl` theme tokens.
- Exact copy polish inside `PlayerInviteCopy.kt` matching the app's concise, direct tone.
- Whether `PlayerHomeScreen` / `PlayerHomeView` shares subviews (`ProfileTab`, `MatchesList`, `PlayerDrillsTab`) via extracted components or an `isPlayerMode` flag on `PlayerScreen` / `PlayerView` (as long as no coach-only affordance can render in player mode).
- Exact structure of the Edge Function's internal helper modules and Deno test runner invocation.
