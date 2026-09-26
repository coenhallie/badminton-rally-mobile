# Match details editor

2026-09-20. Requested by Coen, straight after `2026-09-20-new-match-form-simplification-design.md`:
the details that form stopped asking for should be addable afterwards.

## What existed

Nothing edited a match. `ScoreLogsRepository.rename` existed and had no caller on either
platform. The simplification had removed player names and singles/doubles from the model
outright, and its (unapplied, uncommitted) migration dropped the two player columns.

## What it is now

"Edit details" in the match page's overflow menu and in the board's overflow menu opens an
editor: match name, format (Not set / Singles / Doubles / Mixed), and one or two player
names per side. Only the name is required.

## Decisions

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Scope of "etc." | Name, format, players. Coen picked this over a notes field or venue/date | More fields with no asker |
| Format and the board | Information only. `MatchFormat` is never read by the fold; doubles does not bring back the serve rotation or player chips | Restoring them: they were removed at Coen's request a change earlier, and the rotation needs "who starts in the right court", which nothing asks for now |
| Storage | `home_players` / `away_players` stay `text[] not null`, now `default '{}'` and 0 to 2 names; new nullable `format text` checked against the three serial names. The unapplied drop-columns migration was replaced by `20260920000000_score_logs_optional_details.sql` - still one migration to push | A `details jsonb`: no database check on its shape. Nullable arrays: a second way to say "nobody named" |
| "Not said" vs singles | `format` is nullable and the picker has an explicit "Not set" | Defaulting to singles: records a fact nobody stated |
| Where names show | `ScoreLog.sideName(side)`: the players joined with " / ", else "Home" / "Away". Used by the board, announcements, the match page, the point rows and the shared text. `playersLine()` is null until somebody is named, so list rows only grow the line then; the analytics row shows it in place of the score | Per-surface fallbacks: five chances to disagree about who won |
| Rules | `matchDetailsProblem` (title rules + 40 character names) and `submittedPlayers` (trim, drop blanks, cut to the format) in shared. The repository normalises on save, so both editors hand over what was typed | Validation per platform |
| Partner fields | Kept on screen state while the format is switched back and forth; only the fields showing are validated and sent, and a partner in a singles match is not stored | Clearing on switch: one mis-tap destroys typing |
| Android presentation | A full-screen `Dialog`, not the bottom sheet the design conversation assumed: sheets are drawn on `surfaceVariant`, which is also `ShuttlPillTabs`' track colour, so the format picker would lose its track; and five fields plus a keyboard do not fit a sheet. Not a route, because two screens open it. The dialog sets its own window's status bar icon colour, which a new window does not inherit | |
| iOS presentation | A `Form` sheet like `MatchDetailsSheet`. It keeps "Home" / "Away" section headers where Android has none: an iOS field's label is a placeholder that vanishes once typed into, an Android field's label is permanent | |
| `rename` | Removed; `updateDetails` is the one write | Two writers for the title |

Not enforced in SQL: the 40 character name limit (a CHECK cannot iterate an array without a
function). The client enforces it in the field and in `matchDetailsProblem`.

## Deliberately not in this pass

- Notes, venue, date, event.
- Doubles serve rotation on the board.
- Editing details of a video-first match (no `score_logs` row to hold them).

## Verification

- Shared: `MatchDetailsTest` (15), `ScoreLogsRepositoryTest` details cases, `ScoreMatchCardTest`.
- Android: unit suite; editor opened from the board and from the match page on the Pixel 9a
  emulator, mixed doubles with four names saved, names and format seen on the match page and board.
- iOS: `ScoringModelTests`, `ScoringEngineTests`; `ScoringBoardUITests.testDetailsEditedFromTheBoardNameTheSides`
  drives the real sheet.
- SQL: `score_logs_insert_test.sql` - empty insert, defaulted insert, edit, and four refusals.

## Owner steps

- `supabase db push` for `20260920000000_score_logs_optional_details.sql` before running a build
  with these changes against the real project.
