-- A match that was only ever filmed, edited from the match page: the owner can
-- change its name, description, players and format after upload and read them
-- back through list_match_metadata; nobody else can, and no other column on the
-- row has become writable along the way.
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

create function pg_temp.expect_denied(label text, stmt text) returns void
language plpgsql as $$
begin
    begin
        execute stmt;
    exception when insufficient_privilege then
        return;
    end;
    raise exception 'expected the grant to deny: %', label;
end $$;

create function pg_temp.act_as(uid text) returns void
language sql as $$
    select set_config('request.jwt.claim.sub', uid, true);
    select set_config('request.jwt.claims', json_build_object('sub', uid)::text, true);
$$;

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
set local role authenticated;

-- As uploaded: a name typed at upload, nothing else.
insert into public.videos (id, owner_id, filename, size, storage_path, status, title)
values ('11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
        'a.mp4', 1, 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001.mp4',
        'completed', 'cloudia');

-- As edited from the match page afterwards, every granted column at once.
update public.videos
   set title = 'Club final', description = null, format = 'mixed_doubles',
       home_players = '{Coen,Ana}', away_players = '{Marco,Li}'
 where id = '11111111-0000-0000-0000-000000000001';

do $$
declare row record;
begin
    select * into row from public.list_match_metadata()
     where video_id = '11111111-0000-0000-0000-000000000001';
    if row is null then
        raise exception 'the owner cannot see their own match through the RPC';
    end if;
    if row.title <> 'Club final' or row.format <> 'mixed_doubles'
       or row.home_players <> '{Coen,Ana}' or row.away_players <> '{Marco,Li}' then
        raise exception 'the edited details did not come back: %', row;
    end if;
end $$;

-- The same checks as on score_logs, so the two editors obey one rule.
select pg_temp.expect_refused('a format the app does not know',
    $$update public.videos set format = 'triples' where id = '11111111-0000-0000-0000-000000000001'$$);
select pg_temp.expect_refused('three players on a side',
    $$update public.videos set home_players = '{A,B,C}' where id = '11111111-0000-0000-0000-000000000001'$$);
select pg_temp.expect_refused('a null inside the names',
    $$update public.videos set away_players = array['A', null] where id = '11111111-0000-0000-0000-000000000001'$$);
select pg_temp.expect_refused('no names column at all',
    $$update public.videos set home_players = null where id = '11111111-0000-0000-0000-000000000001'$$);
select pg_temp.expect_refused('an empty name',
    $$update public.videos set title = '' where id = '11111111-0000-0000-0000-000000000001'$$);

-- The grant is column by column: the one added here must not have widened it.
select pg_temp.expect_denied('the storage path',
    $$update public.videos set storage_path = 'x' where id = '11111111-0000-0000-0000-000000000001'$$);
select pg_temp.expect_denied('the owner',
    $$update public.videos set owner_id = 'aaaaaaaa-0000-0000-0000-000000000002' where id = '11111111-0000-0000-0000-000000000001'$$);

-- Somebody else: the row is invisible to their UPDATE and absent from their RPC.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000002');
do $$
declare n int;
begin
    update public.videos set title = 'hijacked' where id = '11111111-0000-0000-0000-000000000001';
    get diagnostics n = row_count;
    if n <> 0 then
        raise exception 'a non-owner renamed the match';
    end if;
    if exists (select 1 from public.list_match_metadata()
                where video_id = '11111111-0000-0000-0000-000000000001') then
        raise exception 'a non-owner can read an unshared match''s details';
    end if;
end $$;

-- The owner still sees their own name, untouched.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
do $$
begin
    if (select title from public.list_match_metadata()
         where video_id = '11111111-0000-0000-0000-000000000001') <> 'Club final' then
        raise exception 'the name did not survive the non-owner''s attempt';
    end if;
end $$;

rollback;
