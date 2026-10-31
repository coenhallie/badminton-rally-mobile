-- Player profiles: a coach's own players, who is on each side of a match, which
-- end of the court the camera saw them at, and the names the list reads back.
-- See docs/plans/2026-10-31-player-profiles-design.md
--
-- A side of a match is a profile OR typed names, never both. The record that
-- speaks for the match holds the assignment: the score log when one exists,
-- otherwise the video. Singles only - a profile cannot sit on a doubles match.

------------------------------------------------------------------
-- 1. players
------------------------------------------------------------------

create table if not exists public.players (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null default auth.uid()
               references auth.users(id) on delete cascade,
    -- 40 is MAX_PLAYER_NAME in the shared module (scoring/MatchDetails.kt).
    name       text not null check (char_length(btrim(name)) between 1 and 40),
    -- Null: not said.
    racket_arm text check (racket_arm in ('left', 'right')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Not unique: a coach may have two players called Tom.
create index if not exists players_owner_name_idx
    on public.players (owner_id, lower(name));

alter table public.players enable row level security;

-- Owner only, all four verbs, as annotation_labels.
drop policy if exists players_select_own on public.players;
create policy players_select_own on public.players
    for select to authenticated using (owner_id = auth.uid());

drop policy if exists players_insert_own on public.players;
create policy players_insert_own on public.players
    for insert to authenticated with check (owner_id = auth.uid());

drop policy if exists players_update_own on public.players;
create policy players_update_own on public.players
    for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists players_delete_own on public.players;
create policy players_delete_own on public.players
    for delete to authenticated using (owner_id = auth.uid());

-- updated_at is a trigger rather than a client write, for the reason given on
-- touch_score_logs_updated_at (20260827000000_score_logs.sql).
create or replace function public.touch_players_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists players_touch_updated_at on public.players;
create trigger players_touch_updated_at
    before update on public.players
    for each row execute function public.touch_players_updated_at();

------------------------------------------------------------------
-- 2. Who is on a match, and which end the camera saw
------------------------------------------------------------------

-- ON DELETE SET NULL is only the backstop: forget_player_on_delete below runs
-- first and keeps the name on the match.
alter table public.score_logs
    add column if not exists home_player_id uuid references public.players(id) on delete set null,
    add column if not exists away_player_id uuid references public.players(id) on delete set null;

alter table public.videos
    add column if not exists home_player_id uuid references public.players(id) on delete set null,
    add column if not exists away_player_id uuid references public.players(id) on delete set null,
    -- Ends are a fact about the footage, so they live on videos only. Null:
    -- not said. 'home' means the Home side was the near player.
    add column if not exists near_side text;

-- Deleting a player looks the matches up by these columns, and so does the
-- foreign key's own set-null; without them each delete scans every coach's rows.
create index if not exists score_logs_home_player_idx
    on public.score_logs (home_player_id) where home_player_id is not null;
create index if not exists score_logs_away_player_idx
    on public.score_logs (away_player_id) where away_player_id is not null;
create index if not exists videos_home_player_idx
    on public.videos (home_player_id) where home_player_id is not null;
create index if not exists videos_away_player_idx
    on public.videos (away_player_id) where away_player_id is not null;

-- Guarded one by one, the way 20260922000000 adds its constraints: the live
-- project and a fresh rebuild have diverged before on these tables.
do $$
declare
    t text;
begin
    foreach t in array array['score_logs', 'videos'] loop
        if not exists (
            select 1 from pg_constraint
            where conrelid = ('public.' || t)::regclass and conname = t || '_home_profile_or_names_check'
        ) then
            execute format(
                'alter table public.%I add constraint %I check (home_player_id is null or cardinality(home_players) = 0)',
                t, t || '_home_profile_or_names_check');
        end if;
        if not exists (
            select 1 from pg_constraint
            where conrelid = ('public.' || t)::regclass and conname = t || '_away_profile_or_names_check'
        ) then
            execute format(
                'alter table public.%I add constraint %I check (away_player_id is null or cardinality(away_players) = 0)',
                t, t || '_away_profile_or_names_check');
        end if;
        -- Singles only: a profile sits on a side only when the match is
        -- singles or has not said what it is.
        if not exists (
            select 1 from pg_constraint
            where conrelid = ('public.' || t)::regclass and conname = t || '_profiles_singles_check'
        ) then
            execute format(
                'alter table public.%I add constraint %I check ((home_player_id is null and away_player_id is null) or format is null or format = ''singles'')',
                t, t || '_profiles_singles_check');
        end if;
    end loop;

    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.videos'::regclass and conname = 'videos_near_side_check'
    ) then
        alter table public.videos
            add constraint videos_near_side_check
            check (near_side is null or near_side in ('home', 'away'));
    end if;
end $$;

------------------------------------------------------------------
-- 3. A match only names its own coach's players
------------------------------------------------------------------

-- A foreign key alone would accept any player's id. security definer, because
-- the caller's RLS hides every other coach's players and the check has to tell
-- "somebody else's" from "does not exist" without leaking which: both are
-- refused with the same message.
--
-- Trigger order on a table is alphabetical by name, and score_logs already has
-- two before-update triggers. This one reads only the player ids and owner_id,
-- which no other trigger on either table writes, so it holds in any order.
create or replace function public.check_match_players_owner()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_id uuid;
begin
    foreach v_id in array array[new.home_player_id, new.away_player_id] loop
        if v_id is not null and not exists (
            select 1 from public.players p
             where p.id = v_id and p.owner_id = new.owner_id
        ) then
            raise exception 'player % belongs to another coach', v_id;
        end if;
    end loop;
    return new;
end;
$$;

drop trigger if exists score_logs_players_owner on public.score_logs;
create trigger score_logs_players_owner
    before insert or update of home_player_id, away_player_id, owner_id on public.score_logs
    for each row execute function public.check_match_players_owner();

drop trigger if exists videos_players_owner on public.videos;
create trigger videos_players_owner
    before insert or update of home_player_id, away_player_id, owner_id on public.videos
    for each row execute function public.check_match_players_owner();

------------------------------------------------------------------
-- 4. Deleting a player keeps the name on every match
------------------------------------------------------------------

-- The matches stay, unassigned, with the name still shown. Id and names change
-- in one UPDATE per side so the profile-or-names check holds at every step.
-- security definer, because the caller's RLS is the caller's: the rows to
-- rewrite are the player's own coach's, and the delete policy has already
-- established that the caller is that coach.
create or replace function public.forget_player_on_delete()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.score_logs
       set home_players = array[old.name], home_player_id = null
     where home_player_id = old.id;
    update public.score_logs
       set away_players = array[old.name], away_player_id = null
     where away_player_id = old.id;
    update public.videos
       set home_players = array[old.name], home_player_id = null
     where home_player_id = old.id;
    update public.videos
       set away_players = array[old.name], away_player_id = null
     where away_player_id = old.id;
    return old;
end;
$$;

drop trigger if exists players_forget_on_delete on public.players;
create trigger players_forget_on_delete
    before delete on public.players
    for each row execute function public.forget_player_on_delete();

------------------------------------------------------------------
-- 5. The owner may edit the new video columns
------------------------------------------------------------------

-- 20260922000000 left the UPDATE grant on videos column by column; these three
-- are the match page's "Who's playing" writes. INSERT has no column grant in
-- either repo, so there is nothing to add for it. The owner_id RLS policy
-- (0002 videos_owner_update) still scopes WHICH rows.
grant update (home_player_id, away_player_id, near_side)
  on public.videos to authenticated;

------------------------------------------------------------------
-- 6. list_match_metadata() returns the assignment and resolved names
------------------------------------------------------------------

-- Dropped and recreated rather than replaced: `create or replace` refuses to
-- change a function's return type.
drop function if exists public.list_match_metadata();

create function public.list_match_metadata()
returns table (
  video_id       uuid,
  title          text,
  description    text,
  home_players   text[],
  away_players   text[],
  format         text,
  home_player_id uuid,
  away_player_id uuid,
  near_side      text,
  home_names     text[],
  away_names     text[]
)
language sql
security definer
set search_path = public
as $$
  -- The exists clause is a structural copy of the using clause on
  -- "clips: select own or shared" (20260506000000_match_shares.sql), which is the
  -- authority on which matches a user can see. It is one of five copies of "own
  -- or shared" (the rally_clips and rally_annotations selects, two storage
  -- selects, and this). If they diverge, a card's title would appear or vanish
  -- independently of the match itself, so change them together.
  --
  -- A recipient cannot read `players`, so the names are resolved here. The
  -- record that speaks for the match is the bound score log when there is one,
  -- otherwise the video; the id columns come back as the video stores them.
  select v.id, v.title, v.description, v.home_players, v.away_players, v.format,
         v.home_player_id, v.away_player_id, v.near_side,
         coalesce(sl.home_names,
                  case when v.home_player_id is not null then array[vh.name] else v.home_players end),
         coalesce(sl.away_names,
                  case when v.away_player_id is not null then array[va.name] else v.away_players end)
    from public.videos v
    left join public.players vh on vh.id = v.home_player_id
    left join public.players va on va.id = v.away_player_id
    left join lateral (
      select case when s.home_player_id is not null then array[sh.name] else s.home_players end as home_names,
             case when s.away_player_id is not null then array[sa.name] else s.away_players end as away_names
        from public.score_logs s
        left join public.players sh on sh.id = s.home_player_id
        left join public.players sa on sa.id = s.away_player_id
       where s.video_id = v.id
       order by s.created_at
       limit 1
    ) sl on true
   where v.owner_id = auth.uid()
      or exists (
        select 1 from public.match_shares ms
         where ms.video_id = v.id
           and ms.shared_with_user_id = auth.uid()
      )
$$;

revoke all   on function public.list_match_metadata() from public;
grant execute on function public.list_match_metadata() to authenticated;
