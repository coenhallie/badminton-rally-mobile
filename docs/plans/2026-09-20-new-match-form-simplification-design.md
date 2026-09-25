# New match form simplification

> **Amended the same day** by `2026-09-20-match-details-editor-design.md`: player names and
> format came back as optional details edited after creation, so the migration no longer
> drops the player columns. Rows below marked (amended) describe what was first built.

2026-09-20. Requested by Coen: the form asked for more than a match needs courtside.

## What existed

The form (`NewMatchScreen.kt`, `NewMatchView.swift`) asked for a name, singles or
doubles, two or four player names, one of three scoring presets (21, 15 win-by-two,
15 straight) and the coin toss. All of it was required before Create enabled.
`2026-08-26-live-scoring-review-design.md` had kept the coin toss on purpose (row 7)
and specified "name, singles or doubles, player or pair names" (L1 form spec). This
document supersedes both of those points.

## What it is now

Name (prefilled), Points `15 / 21 / 11`, Sets `3 / 1`, Create. The form can be
submitted the moment it opens.

## Decisions

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Doubles, player names, coin toss | Removed from the model, not just the form: `MatchSetup` is now only `firstServer`; `ScoreLog` has no player lists; `PairPlayer`, `servingPlayer`, `receivingPlayer`, `rightCourtPlayer` and the board's player chips are gone | Keeping them defaulted. The app is not live, so there is no stored history or older client to serve, and unreachable code is a question the next reader has to ask |
| What a side is called | `Side.label`: "Home" / "Away", in shared so both platforms and the share text agree | Writing placeholder names into the record. Fake data is indistinguishable from real data later |
| Database (amended) | `20260920000000_score_logs_drop_players.sql` dropped `home_players` / `away_players` | Relaxing the check to allow empty arrays: a column nothing reads. `setup` jsonb is left alone; old rows carry keys the client ignores on decode (pinned by `ScoreLogTest`) |
| Rules | `GamePoints` (15 cap 21 mid 8, 21 cap 30 mid 11, 11 cap 15 mid 6) x `MatchSets` (3, 1) through `ScoringRules.of`. Total over both enums, so the form cannot build a rule set the constructor throws on (a Kotlin throw aborts the iOS app) | One list of presets selected by identity: six entries, and the old `indexOf` lookup returned -1 for any varied copy. "15 straight" is dropped from the form; `ScoringRules` is still a parameter, so the engine plays it if handed it |
| First server | Home. Until the first point the board's SERVE pill carries a swap icon and hands the serve across when tapped (`MatchScorer.switchFirstServer`, `ScoreLogsRepository.setFirstServer`). Refused once a point exists, because the serve chain is folded from this seed | Always home with no correction: the serve marker would be wrong in half of all matches |
| Default name | `defaultMatchTitle`: "Sep 20, 14:30", or "Sep 20, 14:30 - Amsterdam". Shared, 24 hour, no year (the list prints the date). The place is cut to fit the 80 character cap | Per-platform formatting: two chances to differ |
| Location | Coarse / reduced accuracy, town only (locality, then outwards; never a street). Asked once: Android skips the prompt when a rationale is due (= already refused), iOS only prompts while undetermined. Every failure is `null` and the name is the time alone. A place arriving after the user typed does not overwrite their name | Asking on every open after a refusal |
| List rows | The players line is removed from match rows; the analytics row subtitle is now the score line. The match page keeps a "Home vs Away" legend under the score | "Home vs Away" on every list row: identical on all of them, so it says nothing |

## Deliberately not in this pass

- Naming players or pairs. It returned the same day as exactly that: see the details editor design.
- A straight-race or custom rule set on the form.
- Remembering the last used points and sets as the next default.

## Verification

- Shared: `:shared:jvmTest` scoring package (rules options, default title, first
  server, side labels, legacy setup decode).
- Android: `NewMatchViewModelTest`, `ScoringViewModelTest`; form, permission prompt,
  place in the name, 11 x 1 match and the serve switch checked on the Pixel 9a emulator.
- iOS: `ScoringEngineTests`, `ScoringModelTests`; `ScoringBoardUITests` drives the form.
- SQL: `supabase/tests/score_logs_insert_test.sql`, shown to fail without the migration
  ("null value in column home_players") and pass with it.

## Owner steps

- `supabase db push` for `20260920000000_score_logs_optional_details.sql` (which replaced the drop-columns migration), BEFORE running a
  build with this change against the real project: until then every new match fails to
  sync on the NOT NULL columns.
- A real-phone check of the location prompt and the town it names, on both platforms.
