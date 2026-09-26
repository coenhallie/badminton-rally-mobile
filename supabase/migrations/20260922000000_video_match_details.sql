-- Details on a match that was only ever filmed: who played and what kind of
-- match it was, plus the right to change its name after upload.
-- See docs/plans/2026-09-22-video-match-details-design.md
--
-- A match scored courtside keeps these on its score log. A match born from a
-- video has no score log and never gets one - there is no board to play a
-- match that is already on film - so the same three optional details live on
-- its videos row, under the same names and the same checks as on score_logs
-- (20260920000000). The phone reads them back through list_match_metadata.

------------------------------------------------------------------
-- 1. The columns, with the score_logs checks
------------------------------------------------------------------

alter table public.videos
  add column if not exists home_players text[] not null default '{}',
  add column if not exists away_players text[] not null default '{}',
  add column if not exists format text;

-- Guarded one by one, the way 0008 and 20260825000000 add their constraints:
-- the live project and a fresh rebuild have diverged before on this table.
do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.videos'::regclass and conname = 'videos_home_players_check'
  ) then
    alter table public.videos
      add constraint videos_home_players_check
      check (cardinality(home_players) <= 2 and array_position(home_players, null) is null);
  end if;
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.videos'::regclass and conname = 'videos_away_players_check'
  ) then
    alter table public.videos
      add constraint videos_away_players_check
      check (cardinality(away_players) <= 2 and array_position(away_players, null) is null);
  end if;
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.videos'::regclass and conname = 'videos_format_check'
  ) then
    alter table public.videos
      add constraint videos_format_check
      check (format is null or format in ('singles', 'doubles', 'mixed_doubles'));
  end if;
end $$;

------------------------------------------------------------------
-- 2. The owner may now edit these, title and description included
------------------------------------------------------------------

-- 0008 withheld the UPDATE grant on title, and 20260825000000 on description,
-- because there was no rename-after-upload flow and both files said the grant
-- would become mandatory the day one existed. This is that day: the match page
-- edits a video-born match's name, description, players and format in place.
-- The owner_id RLS policy (0002 videos_owner_update) still scopes WHICH rows;
-- this grant scopes WHICH COLUMNS, and every other column stays out of reach
-- of the authenticated role. The web app's own MAX_TITLE_LENGTH (60) and the
-- phone's normalisation are the client-side caps; the 1..100 CHECK from 0008
-- is the guard.
grant update (title, description, format, home_players, away_players)
  on public.videos to authenticated;

------------------------------------------------------------------
-- 3. list_match_metadata() returns the new columns
------------------------------------------------------------------

-- Dropped and recreated rather than replaced: `create or replace` refuses to
-- change a function's return type.
drop function if exists public.list_match_metadata();

create function public.list_match_metadata()
returns table (
  video_id     uuid,
  title        text,
  description  text,
  home_players text[],
  away_players text[],
  format       text
)
language sql
security definer
set search_path = public
as $$
  -- The exists clause is a structural copy of the using clause on
  -- "clips: select own or shared" (20260506000000_match_shares.sql), which is the
  -- authority on which matches a user can see. If the two diverge, a card's title
  -- would appear or vanish independently of the match itself, so change them together.
  select v.id, v.title, v.description, v.home_players, v.away_players, v.format
    from public.videos v
   where v.owner_id = auth.uid()
      or exists (
        select 1 from public.match_shares ms
         where ms.video_id = v.id
           and ms.shared_with_user_id = auth.uid()
      )
$$;

revoke all   on function public.list_match_metadata() from public;
grant execute on function public.list_match_metadata() to authenticated;
