-- A match no longer has to name its players. The new match form stopped asking for
-- them, and for singles or doubles; both are now optional details, filled in later
-- from the match's "Edit details" sheet.
-- See docs/plans/2026-09-20-new-match-form-simplification-design.md
--  and docs/plans/2026-09-20-match-details-editor-design.md
--
-- home_players / away_players were NOT NULL with at least one name each, so every
-- insert from the new client was refused. They stay NOT NULL - an empty array is
-- "nobody named", and a second way to say that (NULL) is one more case for every
-- reader - but may now be empty, and default to it.
alter table public.score_logs
    drop constraint if exists score_logs_home_players_check,
    drop constraint if exists score_logs_away_players_check;

alter table public.score_logs
    alter column home_players set default '{}',
    alter column away_players set default '{}';

alter table public.score_logs
    add constraint score_logs_home_players_check check (
        cardinality(home_players) <= 2 and array_position(home_players, null) is null
    ),
    add constraint score_logs_away_players_check check (
        cardinality(away_players) <= 2 and array_position(away_players, null) is null
    );

-- What kind of match it was. NULL is "nobody said", which is what a new match is:
-- the form does not ask, and that is a different fact from singles. Information
-- only - the score is folded from rules, setup and events and never reads this.
-- The three spellings are MatchFormat's serial names in the shared module.
alter table public.score_logs
    add column if not exists format text
        check (format in ('singles', 'doubles', 'mixed_doubles'));
