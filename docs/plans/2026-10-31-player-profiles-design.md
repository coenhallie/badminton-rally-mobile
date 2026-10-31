# Design: player profiles under the coach's account

**Date:** 2026-10-31
**Status:** Draft, for Coen's review. Doubles and the attach-time end question
answered by Coen 2026-10-31 (section 9).
**Asked for:** Coen, 2026-10-19: the coach creates separate player accounts,
toggles between them to see each player's analytics, and later each player logs
in to see their own data and the coach's recommendations. "Let's go step by step."
**Follows:** `2026-08-28-coaching-capability-review-design.md` item #2 ("Player
identity"), which proposed a `players` table and never recorded an answer to its
open question 1, "individuals or a club?". This document answers it: a coach with
individual players.
**Stacked on:** `feat/match-moments` (PR #16). Branch `feat/player-profiles`.

This document designs **step 1** in full and sketches steps 2 to 5 only as far as
step 1 must not box them in.

| Step | What | This document |
|---|---|---|
| 1 | Player profiles, assigning matches to players, Players as the home screen | Designed |
| 2 | A player's metrics across their matches | Sketched (section 8) |
| 3 | Coach recommendations, distinct from match notes | Sketched |
| 4 | Player logins by email invite, a read-only player view | Sketched, with the constraints step 1 must honour |
| 5 | Workouts driven by the metrics | Named only |

---

## 1. Intent

The coach films a player, the models analyse the video, the coach adds their own
judgement (score, moments, labels, notes), and all of it lands on **that player**.
Today it lands on the coach's account and nowhere else: there is no player, only
two free-text names on a match. The product moves from *analyse this match* to
*coach this player*.

What Coen said, in order:

- The coach uploads the video, the YOLO models and other metrics analyse it, the
  coach adds their own analysis manually, and that is added to the correct
  player's account. The player later analyses the data and does workouts based
  on the metrics.
- The coach adds players through their own account first. Invitation emails are
  for later.
- Whether one or both players on court are the coach's own varies, so the coach
  tells the app: one player tracked, or both.
- Players usually stay on their side of the court for the whole video (training
  and drills, not full matches with end changes).
- Switching to a player shows **the player's profile first**, with an easy way
  to all their matches.
- Home screen: the list of players (mockup option A). Player page: a
  Profile | Matches toggle (option C), because the profile is where the player's
  metrics will go.

### Goals

- A coach can create, rename, give a playing hand to, and delete players.
- Every match (filmed, scored, or both) can say which of the coach's players it
  is for, on which end of the court.
- The app opens on the coach's players. A player's page shows their profile and,
  one tap away, their matches.
- The data shape is the one step 4 grants a player login read access through,
  without moving any stored data.

### Non-goals (this step)

- Player logins, invites, email. (Step 4.)
- Metrics aggregated across matches. (Step 2.) The profile shows counts only.
- Recommendations. (Step 3.)
- End changes within a video. A full match filmed through an end change credits
  each half of the video to whoever stood on that end; this is a known limit, not
  something this step detects.
- The web app (`badminton-tracker/src`). It neither shows nor edits players.

### Anti-goals

- Copying a player's name into each match. A rename must reach every match.
- A second "who is on this match" rule beside the one the list already uses
  (section 2.4). One rule decides it.
- Moving storage objects or rewriting `owner_id`. The coach stays the owner of
  every row and every file.
- Two platform ports of the same rule. Filtering, counts and copy live in
  `shared`.

---

## 2. What exists today

Verified on `feat/match-moments` (3c9bda8c) unless marked otherwise.

### 2.1 One account owns everything

- Every table scopes access by `owner_id = auth.uid()`: `videos`, `rally_clips`,
  `processing_logs` (tracker `0002_rls_policies.sql`), `annotation_labels`
  (`20260824000000_annotation_labels.sql`), `score_logs`
  (`20260827000000_score_logs.sql`). Storage paths start with the owner's uid
  (tracker `0003_storage_buckets.sql`; `VideosRepository.kt:525`).
- The app never filters reads by owner; it relies on RLS
  (`ClipsRepository.kt:36` selects every page with no filter).
- `MatchListInput.currentUserId` (`MatchListInput.kt:33`) is compared to a clip's
  `ownerId` only to split "mine" from "Shared with me".

### 2.2 A player is two free-text names

- `score_logs.home_players` / `away_players` are `text[]`, 0 to 2 names, no nulls
  (`20260920000000_score_logs_optional_details.sql:12-21`). `videos` has the same
  columns with the same checks (`20260922000000_video_match_details.sql:16-30`).
- `videos` UPDATE is granted per column: `title, description, format,
  home_players, away_players` (`20260922000000_video_match_details.sql:63`). A
  write to a column missing from that grant fails with `insufficient_privilege`
  (`supabase/tests/video_match_details_test.sql:23-32`); a stranger's update
  matches zero rows under RLS.
- `list_match_metadata()` returns those columns for owned or shared videos
  (`20260922000000_video_match_details.sql:79-91`).
- Names are typed only in the match details editor (`EditMatchDetailsDialog.kt`,
  `EditMatchDetailsSheet.swift`), never on the new-match form, which was cut back
  to name, points and sets on purpose (`2026-09-20-new-match-form-simplification-design.md`).
- `videos.player_labels` (tracker `0001`) holds names the web app gives tracker
  slots. The mobile app does not read it.

### 2.3 The analysis knows court ends, not people

- Analysis identifies a player by court half, `CourtSide.NEAR` / `FAR`
  (`NearPlayer.kt:106-117`). A **device run fills NEAR only**; a cloud run fills
  both (`NearPlayer.kt:106`, `HeatmapPanel.kt:68`, `AnalysisSection.swift:325`).
- The Analysis panels carry a Near | Far toggle defaulting to Near
  (`HeatmapPanel.kt:147`, `AnalysisSection.swift:214`).
- Racket arm is a per-video phone preference, `racket_arm:{entryId}`, LEFT, RIGHT
  or unset meaning both (`RacketArmPreferenceRepository.kt:5-30`). It is one
  choice per video, shared by both sides (`SkeletonPanel.kt:269`, beside the
  per-side toggle at `:172`).
- Nothing links NEAR/FAR to HOME/AWAY, a name, or a person.

### 2.4 Which record speaks for a match

There is no match entity. The list joins score logs, cloud clips, local entries and
video metadata (`MatchListBuilder.kt`). When a score log exists for a match its
details win: `scoreItem` takes `playersLine` from the score log
(`MatchListBuilder.kt:301`) and a video-only match takes it from the video's
metadata (`MatchListBuilder.kt:197, 217`). **This document reuses that rule** for
player assignment rather than adding a second one.

### 2.5 Local entries

`LocalVideoEntry` (`LocalVideoEntry.kt:149-178`) is the phone's record of a video;
it holds a title and description but no player names, so a match that exists only
on the phone shows no players line (`videoItem` reads players from cloud metadata
only, `MatchListBuilder.kt:197, 217`).
its `id` becomes `videos.id` on upload and its `title` / `description` ride along on
the INSERT. A device-analysed match may never be uploaded.

### 2.6 Navigation

- Android: one `NavHost`; the start destination is `Route.Home`, the match list
  (`nav/Route.kt:7`). One `ClipListViewModel` is created for the `Route.Home`
  back-stack entry and the Match and Settings screens reach it through
  `nav.getBackStackEntry<Route.Home>()` (`AuthGate.kt:186-201, 401, 471`).
- iOS: `HomeView` owns one `NavigationStack` with `MatchesScreen` as its root
  (`HomeView.swift:115`).
- Record and Import auto-open the "Match details" sheet (name and description)
  while the entry is still LOCAL (`MatchDetailsSheet.kt:31-50`).

### 2.7 Sharing

`share_match` grants one account read access to one video's clips, annotations,
clip and thumbnail files, and `list_match_metadata`
(`20260506000000_match_shares.sql`). A recipient cannot read the `videos` row,
`score_logs`, the `results` bucket, or the pose artifact. The "own or shared"
predicate is written out five times: the `rally_clips` select, the
`rally_annotations` select, two storage select policies, and
`list_match_metadata` (`20260922000000_video_match_details.sql:87-90` says they
must change together).

---

## 3. The model

### 3.1 `players`

A new table, owned by the coach. New migration in this repo's
`supabase/migrations`.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` pk, `default gen_random_uuid()` | |
| `owner_id` | `uuid not null default auth.uid()` references `auth.users` on delete cascade | The coach |
| `name` | `text not null`, 1 to 40 characters after trim | 40 is `MAX_PLAYER_NAME` (`MatchDetails.kt:24`) |
| `racket_arm` | `text null`, `check in ('left','right')` | Null: not said |
| `created_at`, `updated_at` | `timestamptz` | `updated_at` by a trigger like `touch_score_logs_updated_at()` (`20260827000000_score_logs.sql:105-118`) |

- RLS: four per-verb policies, `owner_id = auth.uid()`, as `annotation_labels`.
- Index `(owner_id, lower(name))`, not unique: a coach may have two players
  called Tom. (My call.)

### 3.2 Who is on a match

Two columns on **both** `score_logs` and `videos`:

- `home_player_id uuid null references players(id) on delete set null`
- `away_player_id uuid null references players(id) on delete set null`

Rules:

1. **A side is a profile or typed names, never both.** Check constraint:
   `home_player_id is null or cardinality(home_players) = 0`, the same for away.
   When a side is a profile, its name is read from `players.name`, so a rename
   reaches every match.
2. **Same coach.** A `before insert or update` trigger on both tables refuses a
   player id whose `players.owner_id` is not the row's `owner_id`. An FK alone
   does not check this.
3. **The record that speaks for the match holds the assignment** (section 2.4):
   the score log when one exists, otherwise the video. When a video is attached
   to a score log, the score log's assignment stands and the video's columns
   stay null. No copying between the two.
4. **Deleting a player keeps the history readable.** A `before delete` trigger
   on `players` sets, in one UPDATE per table, the side's `text[]` to the
   player's name **and** its id to null on every row that points at them, so
   rule 1's check holds at every step. The FK's `on delete set null` is the
   backstop. The matches stay, unassigned, with the name still shown. (My call.)
   The phone does the same to its own copies once the delete has landed on the
   server: every cached score log and every `LocalVideoEntry` that names the
   player gets the name and loses the id, through one shared function. Server
   first, because rewriting first would unassign matches on a delete that then
   fails. A sync that runs in the instant between the two steps hits the dead FK
   once and succeeds on the next sync, and the list's refresh also rewrites the
   copies of any player that has vanished from the server's list (deleted on
   another device of the same coach). Without the rewrite, an unsynced score log
   would fail its upsert on the dead FK and block every other match's sync (the
   hazard `ScoreLogsRepository.kt:210-215` already describes for `video_id`), and
   an un-uploaded video would fail its INSERT.
5. `videos` UPDATE grant gains the new columns (section 3.3 too). The SQL test
   proves the owner's write lands, a stranger's matches zero rows, and a column
   left out of the grant is refused.

### 3.3 Which end each side stood on

One column on `videos` only, because ends are a fact about the footage:

- `near_side text null check in ('home','away')`. Null: not said.

A video-only match records "near is home": the "Who's playing" section
(section 4.3) writes the near player as home. A score-born match that gets a video
attached is asked which end Home was on during the attach (section 4.4a).

**Singles only** (Coen, 2026-10-31: "We don't support doubles for now"). A match
whose format is doubles or mixed doubles cannot hold a profile on either side: a
check constraint on both tables requires both player ids to be null unless
`format` is null or `singles`. The details editor hides the player pickers for
doubles, and offers Doubles / Mixed only while neither side is a profile, with a
line saying to remove the players first. (My call: switching format silently
unassigning a player would be a surprise.)

### 3.4 Reading it back

- `list_match_metadata()` additionally returns `home_player_id`,
  `away_player_id`, `near_side`, and **resolved** side names (the profile's name
  when a side is a profile, otherwise the typed names). A share recipient cannot
  read `players`, and must still see names.
- `score_logs` rows already come back whole through the owner's select.
- `LocalVideoEntry` gains `homePlayerId`, `awayPlayerId`, `homePlayers`,
  `awayPlayers` (typed names, for the opponent) and `nearSide`, which ride along
  on the `videos` INSERT like `title` today. A device-only match keeps them on
  the phone, and `videoItem` reads them from the entry when there is no cloud
  metadata, so a phone-only match shows its players too.
- For a score-born match with a video, `list_match_metadata` resolves the names
  through the bound score log (`score_logs.video_id`) inside the security-definer
  function, so a share recipient sees names there too.

### 3.5 A player's matches

One shared rule, in `shared/matchlist`: a match belongs to player P when the
record that speaks for it (section 2.4) has P as home or away. `MatchListInput`
gains an optional `playerId`; `buildMatchList` filters with it. Both the player
page's Matches tab and the profile counts use it.

`PlayerSummary(matchCount, rallyCount, lastMatchAtEpochMs)` is computed in
`shared` from the same filtered list, so the count on the Players row, the
Profile tiles and the Matches tab can never disagree.

### 3.6 Which side's track is the player's

For a match with `near_side` set, a shared function maps `CourtSide` to the side's
player or typed name. The Analysis panels use it:

- The Near | Far toggle shows names ("Marco | Jip") instead of Near | Far when
  both are known; one known name shows as that name and "Opponent".
- Racket arm for a side whose player has `racket_arm` is the player's hand, and the
  arm choice is not offered while that side is shown. Every other side keeps
  today's single per-video choice, unchanged (no per-side key). (My call:
  handedness is a fact about the person, so a per-video override for a profile
  only creates disagreement.)

### 3.7 The player list on the phone

`PlayersRepository` in `shared/repo`, on the pattern of
`AnnotationLabelsRepository`: an owner-scoped cache that survives a cold start
offline, refreshed on launch and on pull. Creating, renaming and deleting a player
need a connection, like labels; picking a cached player for a match works
offline. (My call: a player created offline would need its own sync queue, and a
coach adds a player far less often than they film one.)

---

## 4. The screens

Mockups: `.superpowers/brainstorm/*/content/step1-flow.html` (local, not
committed). Both platforms, light and dark, large text; copy in `shared/*Copy.kt`.

### 4.1 Players (the new home)

- Title "Players", the background-work indicator and the gear where they are now.
- One row per player: initials avatar, name, "12 matches · last Oct 28" from
  `PlayerSummary`. Sorted by name, case-insensitive. (My call: a coach looks a
  player up by name.)
- An "Add player" row at the end of the list, and an "All matches" button below
  it that opens today's match list unchanged: every match, assigned or not, with
  its filters, sort and "Shared with me".
- The add (+) button stays and opens the existing Add sheet; a match added here
  has no player until the coach picks one.
- Empty state: "Add your first player" with the Add player action, and All
  matches still reachable, so a coach with history is never stranded.

### 4.2 Player page

- Top bar: back, the player's name as a switcher (a menu of all players; picking
  one replaces this page), and an overflow with Rename, Playing hand, Delete.
- Pill toggle Profile | Matches, the same component as the match page's sections.
- **Profile:** avatar, name, playing hand, and three tiles: matches, rallies,
  last match. Nothing else in step 1; step 2 adds metrics below the tiles.
- **Matches:** today's match list filtered to this player (section 3.5), with
  its filters and sort.
- The add (+) button here pre-fills this player: a new scored match makes them
  home; a recorded or imported video makes them the near player.
- Delete asks first: "Delete Marco? The 12 matches stay in All matches, with
  the name still on them."

### 4.3 Who's playing (adding a video)

Not a new sheet. The "Match details" sheet that already auto-opens after Record
and Import (`MatchDetailsSheet.kt`) gains a **Who's playing** section above the
name, and stays skippable.

- **Tracked: One player | Both players.** Coen's toggle.
- A court with two ends, Far above Near, as the camera sees it.
  - One player: the near end is a player picker; the far end is an optional
    typed opponent name. A "swap ends" control moves the player to the far end
    for when the coach filmed from the other side.
  - Both players: both ends are player pickers.
- The player picker lists the coach's players and ends with "Add player", which
  creates one inline.
- When the coach's player is on the far end, one line under the court says
  analysis on this phone follows only the near player, and the cloud follows
  both. (Device runs fill NEAR only, section 2.3.)
- Saving writes home = near, away = far, `near_side = 'home'`.

### 4.4 Editing later

The match details editor (`EditMatchDetailsDialog.kt`,
`EditMatchDetailsSheet.swift`) replaces each side's free-text name fields with
the same control: a player picker or a typed name. For a match with a video it
also shows the court ends with the swap. This is how an existing, unassigned
match is assigned, and how a score-born match that later got a video says which
end Home was on. It writes to the record that speaks for the match
(section 3.2 rule 3).

Today that editor is reachable only for a score log or an owned cloud video that
has clips (`MatchScreen.kt:500-510`, `MatchView.swift:206-215, 406-417`), and a
phone-only video gets the "Match details" sheet only while LOCAL
(`canEditLocalVideoDetails`). Who's playing must be editable on **every** match
the coach owns, so:

- a phone-only match (any stage before upload, including after a device run)
  edits the `LocalVideoEntry`;
- an uploaded video with no clips yet (processing, failed, zero rallies) edits
  its `videos` row, which the grant allows at any status, and mirrors to the
  entry the way `mirrorVideoDetails` does today.

Which entry point shows the control in each case (the details editor gaining
more matches, or Who's playing on its own in the overflow) is left to the
builder; the rule is that no owned match lacks one.

### 4.4a Attaching a video to a scored match

When a video is attached to a score log that has a profile on either side, one
question comes before court marking: **"Which end was Home on?"** with Near and
Far, naming the Home side (the profile's name or the typed name). It is asked once,
on both platforms, whichever way the video arrives (record or import from the match
page's attach). The answer is stored as `nearSide` on the `LocalVideoEntry` and
rides along on the `videos` INSERT as `near_side`. Skipping leaves it null, and the
details editor (section 4.4) can set it later.

A score log with no profile on either side is not asked: nothing would use the
answer in step 1. (My call.)

### 4.5 Navigation changes

- Android: new routes `Players` (start destination) and `Player(id)`;
  `Route.Home` becomes the All matches list. The one `ClipListViewModel` moves
  from the `Route.Home` entry to the `Players` root entry, and Match, Settings,
  All matches and Player read it from there, so the list stays one instance.
- iOS: the `NavigationStack` root becomes the Players screen; `MatchesScreen`
  is pushed for All matches and embedded in the Player page's Matches tab.

---

## 5. Decisions

| Question | Chosen | Rejected, and why |
|---|---|---|
| Who owns a player's data | The coach. `owner_id` stays the coach's on every row and file; a player is a row the coach owns | A Supabase account per player now: logins are step 4, and moving rows and storage between owners later is the expensive direction |
| Where the assignment lives | `home_player_id` / `away_player_id` on `score_logs` and `videos`, read through the existing "score log speaks for the match" rule | A `match_players` join table: still needs to point at either a video or a score log, so it moves the same two-record problem into a third table and adds a join to every read |
| Names | The profile's name, never copied; a side is a profile or typed names | Writing the profile name into `home_players`: a rename would leave every old match wrong |
| Court ends | `videos.near_side` | Ends on the score log: a score log without a video has no ends to speak of |
| One or both tracked | An explicit One player / Both players toggle, as Coen asked | Inferring it from which ends hold a profile: the same data, but the coach asked to say it out loud |
| Players on the new-match form | No; pre-filled when started from a player's page, otherwise set in the details editor | A picker on the form: the form was cut back on purpose (2026-09-20) |
| A separate "Who's playing" sheet | Folded into the auto-opening Match details sheet | Two sheets back to back after every recording |
| Deleting a player | Matches keep the name as typed text and become unassigned | Refusing delete while matches exist: a coach who stops coaching someone has to hand-edit every match first. Cascading the delete: loses footage and notes |
| Player list on the phone | Cloud rows with an owner-scoped cache | Phone-only players: step 4 links logins to these rows, so they must exist on the server |

---

## 6. Constraints step 4 imposes now

Written down so step 1 does not contradict them.

- **One access predicate.** Step 4 replaces the five copies of "own or shared"
  (section 2.7) with one `security definer` SQL function, roughly
  `can_read_video(video_id)`: owner, or shared, or a linked player login on
  either side of the match, where "the match" is the score log bound to the video
  if any, else the video. Step 1's columns are chosen so that function is two
  joins, not a search.
- **Login link: many profiles to one login.** A `player_logins(player_id,
  user_id)` table, so a player with two coaches needs no schema change.
- **Only cloud matches are visible to a player.** A device-only match has no
  rows to grant. Step 4 must decide whether the coach is nudged to upload.
- **Storage reads** need the same predicate on the four buckets; the SQL test
  harness stubs storage (`supabase/tests/00_storage_stub.sql`), so those policies
  are a known verification gap.
- **`seed_annotation_labels_on_signup`** fires on every new account, so invited
  players would get the starter labels. Step 4 decides whether that is wrong.
- **What a player can see that a share recipient cannot today:** the score log,
  the `videos` row and the analysis artifact. Step 4 widens those three policies;
  step 1 does not touch them.
- **The phone's local stores are not keyed by account.** Sign-out leaves local
  videos, notes, tracks and skeletons for the next account, and the score log
  cache is overwritten by a second account's first write, losing unsynced rows
  (`ScoreLogsRepository.kt:253-270`). Harmless with one coach per phone; step 4
  must fix it before a player signs in on a shared device.

---

## 7. Deliberately not in this pass

- Player photos. Initials only.
- Archiving a player instead of deleting.
- Bulk-assigning existing matches.
- `duplicate-video` (web app edge function) carrying the assignment over to the
  copy. The web app does not show players, and nothing in the mobile app
  duplicates.
- `videos.player_labels` (the web app's names for tracker slots). Left alone.
- Doubles assignment (Coen: not supported for now).

---

## 8. Later steps, sketched

- **Step 2, metrics across matches.** The Profile tab's space below the tiles.
  Cross-match numbers need per-match results the profile can read without each
  video's file: today a cloud track is installed only on the phone that
  uploaded it, and a device track never leaves the phone. Step 2 decides what
  per-match summary is stored server-side.
- **Step 3, recommendations.** A coach-written item addressed to a player, not a
  match note. Its own table keyed by `player_id`.
- **Step 4, player logins.** Section 6.
- **Step 5, workouts.** Driven by step 2's metrics.

---

## 9. Open questions

Answered by Coen, 2026-10-31:

- **Doubles:** not supported for now. Singles only (section 3.3).
- **Ends for a scored match that later gets a video:** ask during the attach,
  before court marking (section 4.4a).

None open.

---

## 10. Verification

- SQL (`supabase/tests/`, new `players_test.sql`): owner-only RLS on
  `players`; the same-coach trigger refuses another coach's player on both
  tables; the profile-or-names check; the `videos` grant actually writes the new
  columns as the owner and writes nothing as a stranger; deleting a player writes
  the name into the sides and clears the ids; `list_match_metadata` returns
  resolved names to a share recipient.
- Shared (`:shared:jvmTest`): the player filter and `PlayerSummary` on a list
  built the way the screen builds it (score-born, video-born, attached,
  device-only, shared); the side-to-name mapping; the players cache across an
  owner change.
- Android and iOS: the Players list, the Player page (both tabs, the switcher,
  delete), the Who's playing section (one and both, swap, inline Add player, the
  far-end device line), the details editor's pickers. Screenshots in light, dark
  and large text.
- **On a real phone (owed to Coen, not closable by the agent):** create two
  players, record a video for one, analyse it in the cloud with both tracked,
  and confirm each player's page shows it and the Analysis panel names the
  sides. See memory "plans ship with the device check unrun".
- **Owner step:** `supabase db push` for the new migration before running a build
  against the real project.

---

## 11. Found while designing, outside this step

- `rally_clips` has no UPDATE policy, so the `update (title)` grant (tracker
  `0004`) matches zero rows and renaming a rally clip
  (`ClipsRepository.kt:48-54`) is a no-op on a database built from the
  migrations. Prod may have a hand-added policy; unverified.
- Sign-out leaves the previous account's local data, and the score log cache
  overwrite (section 6). Both matter from step 4.
