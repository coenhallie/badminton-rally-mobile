-- Player profiles: a coach's own players, who is on each side of a match, which
-- end the camera saw them at, and the names the list reads back. Players are
-- owner-only; a side is a profile or typed names, never both; a profile sits on
-- a side only in singles and only when it is the same coach's; deleting a player
-- leaves the name on every match; a share recipient reads names they cannot
-- read from `players`.
-- See docs/plans/2026-10-31-player-profiles-design.md
\set ON_ERROR_STOP on
begin;

insert into auth.users (id, email) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'owner@test.local'),
  ('aaaaaaaa-0000-0000-0000-000000000002', 'other@test.local');

create function pg_temp.expect_refused(label text, stmt text) returns void
language plpgsql as $$
begin
    begin
        execute stmt;
    exception when check_violation or not_null_violation then
        return;
    end;
    raise exception 'expected the database to refuse: %', label;
end $$;

-- The triggers refuse with a plain RAISE EXCEPTION (P0001), not a constraint.
create function pg_temp.expect_raised(label text, stmt text) returns void
language plpgsql as $$
begin
    begin
        execute stmt;
    exception when raise_exception then
        return;
    end;
    raise exception 'expected a trigger to refuse: %', label;
end $$;

-- A row-level security refusal on INSERT is SQLSTATE 42501, like a missing grant.
create function pg_temp.expect_denied(label text, stmt text) returns void
language plpgsql as $$
begin
    begin
        execute stmt;
    exception when insufficient_privilege then
        return;
    end;
    raise exception 'expected the policy to deny: %', label;
end $$;

create function pg_temp.act_as(uid text) returns void
language sql as $$
    select set_config('request.jwt.claim.sub', uid, true);
    select set_config('request.jwt.claims', json_build_object('sub', uid)::text, true);
$$;

-- Statement builders, so a case reads as what it changes and not as the
-- columns every insert has to carry. `cols` and `vals` are appended as they
-- are; the callers pass literals.
create function pg_temp.log_sql(id text, cols text default '', vals text default '') returns text
language sql as $$
    select format('insert into public.score_logs (id, title, rules, setup%s) values (%L, ''m'', ''{}'', ''{}''%s)',
                  cols, id, vals);
$$;

create function pg_temp.video_sql(id text, owner text, cols text default '', vals text default '') returns text
language sql as $$
    select format('insert into public.videos (id, owner_id, filename, size, storage_path, status%s) values (%L, %L, ''a.mp4'', 1, ''p/'' || %L || ''.mp4'', ''completed''%s)',
                  cols, id, owner, id, vals);
$$;

create function pg_temp.run(stmt text) returns void
language plpgsql as $$
begin
    execute stmt;
end $$;

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
set local role authenticated;

------------------------------------------------------------------
-- 1. players are owner-only
------------------------------------------------------------------

-- No owner_id given: the default fills it with the caller.
insert into public.players (id, name) values ('bbbbbbbb-0000-0000-0000-000000000001', 'Marco');

do $$
begin
    if (select owner_id from public.players where id = 'bbbbbbbb-0000-0000-0000-000000000001')
       is distinct from 'aaaaaaaa-0000-0000-0000-000000000001'::uuid then
        raise exception 'players are owner-only: the owner default did not fill the caller';
    end if;
end $$;

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000002');
do $$
declare n int;
begin
    if exists (select 1 from public.players) then
        raise exception 'players are owner-only: a stranger can read a coach''s player';
    end if;
    update public.players set name = 'hijacked' where id = 'bbbbbbbb-0000-0000-0000-000000000001';
    get diagnostics n = row_count;
    if n <> 0 then
        raise exception 'players are owner-only: a stranger renamed a player';
    end if;
    delete from public.players where id = 'bbbbbbbb-0000-0000-0000-000000000001';
    get diagnostics n = row_count;
    if n <> 0 then
        raise exception 'players are owner-only: a stranger deleted a player';
    end if;
end $$;

-- Nor can a stranger write a row in somebody else's name.
select pg_temp.expect_denied('players are owner-only: a player written as somebody else',
    $$insert into public.players (name, owner_id) values ('Forged', 'aaaaaaaa-0000-0000-0000-000000000001')$$);

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
do $$
begin
    if (select name from public.players where id = 'bbbbbbbb-0000-0000-0000-000000000001') <> 'Marco' then
        raise exception 'players are owner-only: the player did not survive the stranger''s attempts';
    end if;
end $$;

------------------------------------------------------------------
-- 2. refuses an empty or long name
------------------------------------------------------------------

select pg_temp.expect_refused('an empty name',
    $$insert into public.players (name) values ('')$$);
select pg_temp.expect_refused('a name of spaces',
    $$insert into public.players (name) values ('   ')$$);
select pg_temp.expect_refused('a 41-character name',
    $$insert into public.players (name) values (repeat('x', 41))$$);
select pg_temp.expect_refused('a rename to nothing',
    $$update public.players set name = '  ' where id = 'bbbbbbbb-0000-0000-0000-000000000001'$$);
-- The cap is inclusive.
insert into public.players (id, name) values ('bbbbbbbb-0000-0000-0000-0000000000f0', repeat('x', 40));

------------------------------------------------------------------
-- 3. refuses an unknown hand
------------------------------------------------------------------

select pg_temp.expect_refused('a hand the app does not know',
    $$insert into public.players (name, racket_arm) values ('Both', 'both')$$);
insert into public.players (name, racket_arm) values ('Lefty', 'left');
insert into public.players (name, racket_arm) values ('Righty', 'right');
insert into public.players (name, racket_arm) values ('Unsaid', null);

------------------------------------------------------------------
-- 4. refuses names and a profile on one side
------------------------------------------------------------------

select pg_temp.expect_refused('a score log home with a profile and a name',
    pg_temp.log_sql('44444444-0000-0000-0000-0000000000a1',
        ', home_player_id, home_players',
        ', ''bbbbbbbb-0000-0000-0000-000000000001'', ''{Marco}'''));
select pg_temp.expect_refused('a score log away with a profile and a name',
    pg_temp.log_sql('44444444-0000-0000-0000-0000000000a2',
        ', away_player_id, away_players',
        ', ''bbbbbbbb-0000-0000-0000-000000000001'', ''{Marco}'''));
select pg_temp.expect_refused('a video home with a profile and a name',
    pg_temp.video_sql('11111111-0000-0000-0000-0000000000a1', 'aaaaaaaa-0000-0000-0000-000000000001',
        ', home_player_id, home_players',
        ', ''bbbbbbbb-0000-0000-0000-000000000001'', ''{Marco}'''));
select pg_temp.expect_refused('a video away with a profile and a name',
    pg_temp.video_sql('11111111-0000-0000-0000-0000000000a2', 'aaaaaaaa-0000-0000-0000-000000000001',
        ', away_player_id, away_players',
        ', ''bbbbbbbb-0000-0000-0000-000000000001'', ''{Marco}'''));

-- One side a profile, the other typed, is the normal shape.
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-0000000000a3',
    ', home_player_id, away_players',
    ', ''bbbbbbbb-0000-0000-0000-000000000001'', ''{Jip}'''));

------------------------------------------------------------------
-- 5. refuses doubles with a profile
------------------------------------------------------------------

select pg_temp.expect_refused('a doubles score log with a profile',
    pg_temp.log_sql('44444444-0000-0000-0000-0000000000b1',
        ', format, away_player_id',
        ', ''doubles'', ''bbbbbbbb-0000-0000-0000-000000000001'''));
select pg_temp.expect_refused('a mixed doubles score log with a profile',
    pg_temp.log_sql('44444444-0000-0000-0000-0000000000b2',
        ', format, home_player_id',
        ', ''mixed_doubles'', ''bbbbbbbb-0000-0000-0000-000000000001'''));
select pg_temp.expect_refused('a doubles video with a profile',
    pg_temp.video_sql('11111111-0000-0000-0000-0000000000b1', 'aaaaaaaa-0000-0000-0000-000000000001',
        ', format, away_player_id',
        ', ''doubles'', ''bbbbbbbb-0000-0000-0000-000000000001'''));
select pg_temp.expect_refused('a mixed doubles video with a profile',
    pg_temp.video_sql('11111111-0000-0000-0000-0000000000b2', 'aaaaaaaa-0000-0000-0000-000000000001',
        ', format, home_player_id',
        ', ''mixed_doubles'', ''bbbbbbbb-0000-0000-0000-000000000001'''));

-- Singles, and a match that never said, may hold a profile.
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-0000000000b3',
    ', format, away_player_id', ', ''singles'', ''bbbbbbbb-0000-0000-0000-000000000001'''));
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-0000000000b4',
    ', format, away_player_id', ', null, ''bbbbbbbb-0000-0000-0000-000000000001'''));
select pg_temp.run(pg_temp.video_sql('11111111-0000-0000-0000-0000000000b3', 'aaaaaaaa-0000-0000-0000-000000000001',
    ', format, away_player_id', ', ''singles'', ''bbbbbbbb-0000-0000-0000-000000000001'''));
select pg_temp.run(pg_temp.video_sql('11111111-0000-0000-0000-0000000000b4', 'aaaaaaaa-0000-0000-0000-000000000001',
    ', format, away_player_id', ', null, ''bbbbbbbb-0000-0000-0000-000000000001'''));
-- Doubles with typed names is untouched.
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-0000000000b5',
    ', format, home_players', ', ''doubles'', ''{Ana,Li}'''));

-- Moving a match with a profile to doubles is refused as well.
select pg_temp.expect_refused('switching a match with a profile to doubles',
    $$update public.score_logs set format = 'doubles' where id = '44444444-0000-0000-0000-0000000000b3'$$);

------------------------------------------------------------------
-- 6. refuses another coach's player
------------------------------------------------------------------

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000002');
insert into public.players (id, name) values ('bbbbbbbb-0000-0000-0000-000000000002', 'Tom');
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');

select pg_temp.expect_raised('a score log naming another coach''s player (insert)',
    pg_temp.log_sql('44444444-0000-0000-0000-0000000000c1',
        ', home_player_id', ', ''bbbbbbbb-0000-0000-0000-000000000002'''));
select pg_temp.expect_raised('a score log naming another coach''s player on away (insert)',
    pg_temp.log_sql('44444444-0000-0000-0000-0000000000c2',
        ', away_player_id', ', ''bbbbbbbb-0000-0000-0000-000000000002'''));
select pg_temp.expect_raised('a video naming another coach''s player (insert)',
    pg_temp.video_sql('11111111-0000-0000-0000-0000000000c1', 'aaaaaaaa-0000-0000-0000-000000000001',
        ', home_player_id', ', ''bbbbbbbb-0000-0000-0000-000000000002'''));
select pg_temp.expect_raised('a video naming another coach''s player on away (insert)',
    pg_temp.video_sql('11111111-0000-0000-0000-0000000000c2', 'aaaaaaaa-0000-0000-0000-000000000001',
        ', away_player_id', ', ''bbbbbbbb-0000-0000-0000-000000000002'''));

-- On update: the rows exist with no assignment, then name Tom.
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-0000000000c3'));
select pg_temp.run(pg_temp.video_sql('11111111-0000-0000-0000-0000000000c3', 'aaaaaaaa-0000-0000-0000-000000000001'));
select pg_temp.expect_raised('a score log naming another coach''s player (update)',
    $$update public.score_logs set home_player_id = 'bbbbbbbb-0000-0000-0000-000000000002'
       where id = '44444444-0000-0000-0000-0000000000c3'$$);
select pg_temp.expect_raised('a video naming another coach''s player (update)',
    $$update public.videos set away_player_id = 'bbbbbbbb-0000-0000-0000-000000000002'
       where id = '11111111-0000-0000-0000-0000000000c3'$$);

-- A player that does not exist is refused the same way, not by a different error.
select pg_temp.expect_raised('a player that does not exist',
    pg_temp.log_sql('44444444-0000-0000-0000-0000000000c4',
        ', home_player_id', ', ''bbbbbbbb-0000-0000-0000-0000000000ee'''));

------------------------------------------------------------------
-- 7. the owner can write the new video columns
------------------------------------------------------------------

select pg_temp.run(pg_temp.video_sql('11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001'));

do $$
declare n int; v record;
begin
    update public.videos
       set home_player_id = 'bbbbbbbb-0000-0000-0000-000000000001', near_side = 'home'
     where id = '11111111-0000-0000-0000-000000000001';
    get diagnostics n = row_count;
    if n <> 1 then
        raise exception 'the owner could not write the new video columns (% rows)', n;
    end if;
    select home_player_id, near_side into v from public.videos
     where id = '11111111-0000-0000-0000-000000000001';
    if v.home_player_id is distinct from 'bbbbbbbb-0000-0000-0000-000000000001'::uuid
       or v.near_side is distinct from 'home' then
        raise exception 'the new video columns did not read back: %', v;
    end if;
end $$;

-- The grant is column by column: the columns added here must not have widened it.
select pg_temp.expect_denied('the owner of a video',
    $$update public.videos set owner_id = 'aaaaaaaa-0000-0000-0000-000000000002' where id = '11111111-0000-0000-0000-000000000001'$$);

------------------------------------------------------------------
-- 8. a stranger's update writes nothing
------------------------------------------------------------------

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000002');
do $$
declare n int;
begin
    update public.videos
       set home_player_id = null, near_side = 'away'
     where id = '11111111-0000-0000-0000-000000000001';
    get diagnostics n = row_count;
    if n <> 0 then
        raise exception 'a stranger wrote to the owner''s video (% rows)', n;
    end if;
end $$;
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
do $$
begin
    if (select near_side from public.videos where id = '11111111-0000-0000-0000-000000000001') <> 'home' then
        raise exception 'the owner''s near_side did not survive the stranger''s attempt';
    end if;
end $$;

------------------------------------------------------------------
-- 9. near_side is checked
------------------------------------------------------------------

select pg_temp.expect_refused('a near_side the app does not know',
    $$update public.videos set near_side = 'left' where id = '11111111-0000-0000-0000-000000000001'$$);
update public.videos set near_side = 'away' where id = '11111111-0000-0000-0000-000000000001';
update public.videos set near_side = null where id = '11111111-0000-0000-0000-000000000001';
update public.videos set near_side = 'home' where id = '11111111-0000-0000-0000-000000000001';

------------------------------------------------------------------
-- 10. deleting a player keeps the name on every side
------------------------------------------------------------------

insert into public.players (id, name) values ('bbbbbbbb-0000-0000-0000-000000000003', 'Ines');
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-0000000000d1',
    ', home_player_id', ', ''bbbbbbbb-0000-0000-0000-000000000003'''));
select pg_temp.run(pg_temp.video_sql('11111111-0000-0000-0000-0000000000d1', 'aaaaaaaa-0000-0000-0000-000000000001',
    ', away_player_id, home_players', ', ''bbbbbbbb-0000-0000-0000-000000000003'', ''{Jip}'''));
-- The same player on both sides of one row is odd but allowed; each side keeps the name.
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-0000000000d2',
    ', home_player_id, away_player_id',
    ', ''bbbbbbbb-0000-0000-0000-000000000003'', ''bbbbbbbb-0000-0000-0000-000000000003'''));

delete from public.players where id = 'bbbbbbbb-0000-0000-0000-000000000003';

do $$
declare l record; v record; b record;
begin
    select home_player_id, home_players into l from public.score_logs
     where id = '44444444-0000-0000-0000-0000000000d1';
    if l.home_player_id is not null or l.home_players <> '{Ines}' then
        raise exception 'deleting a player did not keep the name on a score log: %', l;
    end if;
    select away_player_id, away_players, home_players into v from public.videos
     where id = '11111111-0000-0000-0000-0000000000d1';
    if v.away_player_id is not null or v.away_players <> '{Ines}' or v.home_players <> '{Jip}' then
        raise exception 'deleting a player did not keep the name on a video: %', v;
    end if;
    select home_player_id, away_player_id, home_players, away_players into b from public.score_logs
     where id = '44444444-0000-0000-0000-0000000000d2';
    if b.home_player_id is not null or b.away_player_id is not null
       or b.home_players <> '{Ines}' or b.away_players <> '{Ines}' then
        raise exception 'deleting a player did not keep the name on both sides: %', b;
    end if;
end $$;

------------------------------------------------------------------
-- 11. metadata resolves names
------------------------------------------------------------------

select pg_temp.run(pg_temp.video_sql('11111111-0000-0000-0000-000000000011', 'aaaaaaaa-0000-0000-0000-000000000001',
    ', home_player_id, away_players, near_side',
    ', ''bbbbbbbb-0000-0000-0000-000000000001'', ''{Jip}'', ''away'''));

do $$
declare r record;
begin
    select * into r from public.list_match_metadata()
     where video_id = '11111111-0000-0000-0000-000000000011';
    if r is null then
        raise exception 'the owner cannot see their own match through the RPC';
    end if;
    if r.home_names <> '{Marco}' or r.away_names <> '{Jip}' then
        raise exception 'the names did not resolve: %', r;
    end if;
    if r.home_player_id is distinct from 'bbbbbbbb-0000-0000-0000-000000000001'::uuid
       or r.away_player_id is not null or r.near_side is distinct from 'away' then
        raise exception 'the ids and near_side did not come back as stored: %', r;
    end if;
end $$;

-- A rename reaches the list: the side holds the id, not the name.
update public.players set name = 'Marco B' where id = 'bbbbbbbb-0000-0000-0000-000000000001';
do $$
begin
    if (select home_names from public.list_match_metadata()
         where video_id = '11111111-0000-0000-0000-000000000011') <> '{"Marco B"}' then
        raise exception 'a rename did not reach the list';
    end if;
end $$;
update public.players set name = 'Marco' where id = 'bbbbbbbb-0000-0000-0000-000000000001';

------------------------------------------------------------------
-- 12. metadata resolves names for a recipient through the bound score log
------------------------------------------------------------------

-- The video carries no assignment; the score log bound to it speaks for the match.
select pg_temp.run(pg_temp.video_sql('11111111-0000-0000-0000-000000000012', 'aaaaaaaa-0000-0000-0000-000000000001'));
select pg_temp.run(pg_temp.log_sql('44444444-0000-0000-0000-000000000012',
    ', video_id, status, home_player_id, away_players',
    ', ''11111111-0000-0000-0000-000000000012'', ''bound'', ''bbbbbbbb-0000-0000-0000-000000000001'', ''{Jip}'''));

-- match_shares has no insert policy for a coach; the share RPC is the way in
-- from the app, and the test writes the row as the admin the RPC runs as.
reset role;
insert into public.match_shares (video_id, shared_with_user_id, granted_by)
values ('11111111-0000-0000-0000-000000000012', 'aaaaaaaa-0000-0000-0000-000000000002',
        'aaaaaaaa-0000-0000-0000-000000000001');

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000002');
set local role authenticated;
do $$
declare r record;
begin
    select * into r from public.list_match_metadata()
     where video_id = '11111111-0000-0000-0000-000000000012';
    if r is null then
        raise exception 'the recipient cannot see the shared match through the RPC';
    end if;
    if r.home_names <> '{Marco}' or r.away_names <> '{Jip}' then
        raise exception 'the recipient did not get the bound score log''s names: %', r;
    end if;
    -- The video's own columns are as stored: null, the assignment is on the score log.
    if r.home_player_id is not null or r.away_player_id is not null then
        raise exception 'the video''s own assignment was not left alone: %', r;
    end if;
    if exists (select 1 from public.players where id = 'bbbbbbbb-0000-0000-0000-000000000001') then
        raise exception 'the recipient can read the owner''s players';
    end if;
    -- Matches nobody shared with them stay out.
    if exists (select 1 from public.list_match_metadata()
                where video_id = '11111111-0000-0000-0000-000000000011') then
        raise exception 'the recipient can see a match nobody shared';
    end if;
end $$;

rollback;
