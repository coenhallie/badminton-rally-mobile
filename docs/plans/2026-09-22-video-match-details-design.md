# Video match details

2026-09-22. Requested by Coen, after finding on his phone that the "cloudia" match could
not be renamed and had no players: "I do want to be able to change the name and add
players to an already added video".

## What existed

Two kinds of match, born differently. A match created on the board is a score log
(`MatchRow.Score`) and carries a name, players and a format that
`2026-09-20-match-details-editor-design.md` made editable. A match born from a video
upload (`MatchRow.Video`) has no score log: its name lives in `videos.title`, which
`0008_video_title.sql` (web repo) and `20260825000000_video_description.sql` made
insert-only on purpose, both saying that a rename-after-upload flow would make the
UPDATE grant mandatory. The match page offered "Match options" (Edit details, Export) only
when a score log existed, so a video-born match had no editor and nowhere to keep players.

## What it is now

A video-born match that is the coach's own gets "Match options" with "Edit details". The
editor is the one the scored match uses, plus a Description field, and it writes the name,
description, format and players onto the `videos` row. The list row and the match page
show "Coen / Ana vs Marco / Li" under the name, and the rallies header adds the format.
Nothing changes about scoring: a video-born match still never gets a Points facet or a
board.

## Decisions

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Where the details live | On `videos`: `home_players`, `away_players` (`text[] not null default '{}'`, 0 to 2 names, no nulls), `format` (checked against the three serial names), same names and checks as `score_logs`. `list_match_metadata()` returns them | A score log created lazily for the video. Coen ruled out a board on a match already filmed, and a score log with no score would be a fake record: the list would call it "Not started · Ended early" |
| The UPDATE grant | `grant update (title, description, format, home_players, away_players)` to `authenticated`, in `20260922000000_video_match_details.sql`. RLS still scopes rows to the owner; the SQL test proves a non-owner's UPDATE hits zero rows and that `storage_path` and `owner_id` stay denied | Editing `0008` / `20260825000000` to drop their "no grant" comments: applied migrations stay as they shipped; the new file says why this is the day they spoke of |
| The name cap | `VIDEO_MATCH_TITLE_CAP` = the web app's 60 for a video; a score log keeps 80. `matchDetailsProblem` takes the cap; `createMatchProblem` too | One cap: the two clients type into the same `videos.title`, and a name one cannot show in full is not a name |
| The description | In the editor for a video-born match only (`MatchDetailsDraft.description` null means "no such field"). Normalised like the upload's: blank becomes null | A description on score logs: Coen chose name, format and players for those two days ago. Leaving the video's description uneditable: a name that can change beside a note that cannot is a stuck field |
| One cache for metadata | `VideosRepository.observeMatchMetadata()`: `listMatchMetadata` fills it, `updateMatchDetails` writes the saved row into it, `deleteMatch` drops the row. Android's `ClipListViewModel` (built once per screen that lists matches) and iOS's `ClipListModel` and `MatchView` follow it | A map per view model, as before: a rename on the match page reached the drawer only on the next pull to refresh |
| The local copy | `updateVideoMatchDetailsOrMessage` writes the server first, then `LocalVideoRepository.mirrorVideoDetails` (no stage check) on the entry whose id is the video's. The entry that uploaded a video and the match built from it cannot show two names | Writing local first: a name ahead of a refused save is the divergence the LOCAL-only edit window of 2026-08-25 exists to prevent. That window stays as it was for entries not yet uploaded |
| Who gets the menu | `log != null || match.isOwned`: a scored match, or a filmed one of the coach's own. Export stays score-only. A shared match gets no menu | |
| Where the details show | `playersLine(home, away)` and `sideName` generalised off `ScoreLog` so a `MatchSummary` reads them by the same rule; `matchDetailsLine(players, format)` is the rallies header's one line, taken from the score log when there is one | A second legend on the rallies facet of a scored match: the points facet has one |

## Deliberately not in this pass

- Scoring a match that was only ever filmed. Coen: a live board on an already added video
  "doesn't make sense". Scoring from the video timeline would be its own design.
- The web app reading players or format. It sees the columns and ignores them.
- Renaming a `FAILED` local entry that never reached `CREATE_ROW`. The grant makes that
  possible now; the 2026-08-25 window is left as it was.

## Verification

- Shared: `MatchMetadataSerializationTest` (new columns, old rows), `MatchDetailsTest`
  (generic players line, caps, details line), `VideosRepositoryTest` (the PATCH body, the
  cache), `VideoMatchDetailsTest` (ordering, normalisation, refusal),
  `LocalVideoRepositoryTest` (the mirror).
- Android: `MatchMetadataMergeTest`, `MatchViewModelTest`; the editor, the row and the
  header checked on the Pixel 9a emulator.
- iOS: `MatchGroupingTests`, `MatchModelTests`; the same screens on the iPhone 17 Pro
  simulator.
- SQL: `supabase/tests/video_match_details_test.sql`: the edit, the RPC read-back, the
  three checks, the empty name, two ungranted columns, a non-owner.

## Owner steps

- `supabase db push` for `20260922000000_video_match_details.sql`, after
  `20260920000000_score_logs_optional_details.sql`. Until then "Edit details" on a
  video-born match fails with "Couldn't save the match details" (the UPDATE is refused
  and the RPC has no such columns), and nothing else changes.
- Rename "cloudia" on the phone and check the drawer row, the local video's row and the
  web app all agree.
