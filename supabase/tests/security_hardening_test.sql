-- Security hardening checks (20261121000000_security_hardening_and_indexes.sql):
-- 1. Cross-tenant score_logs.video_id binding is refused on INSERT and UPDATE.
-- 2. list_match_metadata() masks home_player_id/away_player_id for share
--    recipients while keeping them for the owner, and ignores foreign score_logs.
-- 3. Shared-viewer storage SELECT policies reject fabricated rally_clips paths
--    outside rc.owner_id's folder prefix and shares with mismatched granted_by.
-- 4. share_match() verifies ownership on public.videos as well as rally_clips,
--    and match_shares.video_id enforces FK integrity on new rows.
\set ON_ERROR_STOP on
begin;

insert into auth.users (id, email) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'owner@test.local'),
  ('bbbbbbbb-0000-0000-0000-000000000002', 'recipient@test.local'),
  ('cccccccc-0000-0000-0000-000000000003', 'attacker@test.local');

insert into storage.buckets (id, name) values
  ('clips', 'clips'),
  ('thumbnails', 'thumbnails')
on conflict (id) do nothing;

create function pg_temp.act_as(uid text) returns void
language sql as $$
    select set_config('request.jwt.claim.sub', uid, true);
    select set_config('request.jwt.claims', json_build_object('sub', uid)::text, true);
$$;

create function pg_temp.expect_raised(label text, stmt text, expected_substr text default null) returns void
language plpgsql as $$
declare
    v_msg text;
begin
    begin
        execute stmt;
    exception when raise_exception then
        get stacked diagnostics v_msg = message_text;
        if expected_substr is not null and position(expected_substr in v_msg) = 0 then
            raise exception 'unexpected error for %: % (wanted %)', label, v_msg, expected_substr;
        end if;
        return;
    end;
    raise exception 'expected raise_exception for: %', label;
end $$;

create function pg_temp.expect_sqlstate(label text, stmt text, expected_state text) returns void
language plpgsql as $$
declare
    v_state text;
begin
    begin
        execute stmt;
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate;
        if v_state <> expected_state then
            raise exception 'unexpected sqlstate for %: got %, expected %', label, v_state, expected_state;
        end if;
        return;
    end;
    raise exception 'expected sqlstate % for: %', expected_state, label;
end $$;

------------------------------------------------------------------
-- 1. Cross-tenant score_logs.video_id binding rejected
------------------------------------------------------------------

insert into public.videos (id, owner_id, filename, size, storage_path, status)
values ('11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
        'owner.mp4', 1, 'aaaaaaaa-0000-0000-0000-000000000001/owner.mp4', 'completed');

-- Owner can bind and unbind their own score_logs row.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
set local role authenticated;

insert into public.score_logs (id, video_id, status, title, rules, setup)
values ('44444444-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001',
        'bound', 'Owner match', '{}', '{}');
update public.score_logs set video_id = null, status = 'unbound'
 where id = '44444444-0000-0000-0000-000000000001';
update public.score_logs set video_id = '11111111-0000-0000-0000-000000000001', status = 'bound'
 where id = '44444444-0000-0000-0000-000000000001';

-- Attacker cannot bind to owner's video on INSERT or UPDATE, and a nonexistent
-- video_id is refused with the same message so existence is not leaked.
select pg_temp.act_as('cccccccc-0000-0000-0000-000000000003');

select pg_temp.expect_raised(
    'cross-tenant score_logs video_id on insert',
    $$insert into public.score_logs (id, video_id, status, title, rules, setup)
      values ('44444444-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000001',
              'bound', 'Hijack', '{}', '{}')$$,
    'belongs to another coach'
);

select pg_temp.expect_raised(
    'nonexistent score_logs video_id on insert',
    $$insert into public.score_logs (id, video_id, status, title, rules, setup)
      values ('44444444-0000-0000-0000-0000000000a2', '11111111-0000-0000-0000-0000000000ee',
              'bound', 'Missing', '{}', '{}')$$,
    'belongs to another coach'
);

insert into public.score_logs (id, title, rules, setup)
values ('44444444-0000-0000-0000-0000000000a3', 'Attacker unbound', '{}', '{}');

select pg_temp.expect_raised(
    'cross-tenant score_logs video_id on update',
    $$update public.score_logs
         set video_id = '11111111-0000-0000-0000-000000000001', status = 'bound'
       where id = '44444444-0000-0000-0000-0000000000a3'$$,
    'belongs to another coach'
);

reset role;

------------------------------------------------------------------
-- 2. list_match_metadata() privacy masking & foreign score_logs
------------------------------------------------------------------

insert into public.players (id, owner_id, name) values
  ('55555555-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', 'Marco'),
  ('55555555-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001', 'Lin');

insert into public.videos (id, owner_id, filename, size, storage_path, status,
                           home_player_id, away_player_id, near_side)
values ('11111111-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
        'shared.mp4', 1, 'aaaaaaaa-0000-0000-0000-000000000001/shared.mp4', 'completed',
        '55555555-0000-0000-0000-000000000001', '55555555-0000-0000-0000-000000000002', 'home');

insert into public.match_shares (video_id, shared_with_user_id, granted_by)
values ('11111111-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000002',
        'aaaaaaaa-0000-0000-0000-000000000001');

-- Simulate a pre-existing foreign score_logs row bound to owner's video with an
-- earlier created_at so an unfiltered LATERAL join would have selected it first.
alter table public.score_logs disable trigger score_logs_video_owner;
insert into public.score_logs (id, owner_id, video_id, status, title,
                               home_players, away_players, rules, setup, created_at)
values ('44444444-0000-0000-0000-0000000000f0', 'cccccccc-0000-0000-0000-000000000003',
        '11111111-0000-0000-0000-000000000002', 'bound', 'Poisoned',
        '{PoisonHome}', '{PoisonAway}', '{}', '{}', now() - interval '1 day');
alter table public.score_logs enable trigger score_logs_video_owner;

-- Owner keeps home_player_id/away_player_id and sees real names (not poisoned).
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
set local role authenticated;
do $$
declare r record;
begin
    select * into r from public.list_match_metadata()
     where video_id = '11111111-0000-0000-0000-000000000002';
    if r is null then
        raise exception 'owner did not see shared video in list_match_metadata()';
    end if;
    if r.home_player_id is distinct from '55555555-0000-0000-0000-000000000001'::uuid
       or r.away_player_id is distinct from '55555555-0000-0000-0000-000000000002'::uuid then
        raise exception 'owner player IDs were not preserved: %', r;
    end if;
    if r.home_names <> '{Marco}' or r.away_names <> '{Lin}' then
        raise exception 'owner names were poisoned by foreign score_logs row: %', r;
    end if;
end $$;

-- Recipient gets resolved names (ignoring foreign score_logs), but player IDs are masked.
select pg_temp.act_as('bbbbbbbb-0000-0000-0000-000000000002');
do $$
declare r record;
begin
    select * into r from public.list_match_metadata()
     where video_id = '11111111-0000-0000-0000-000000000002';
    if r is null then
        raise exception 'recipient did not see shared video in list_match_metadata()';
    end if;
    if r.home_player_id is not null or r.away_player_id is not null then
        raise exception 'recipient saw owner player IDs: %', r;
    end if;
    if r.home_names <> '{Marco}' or r.away_names <> '{Lin}' then
        raise exception 'recipient names were poisoned or missing: %', r;
    end if;
    if r.near_side is distinct from 'home' then
        raise exception 'recipient lost near_side: %', r;
    end if;
end $$;

-- When owner binds their own score_logs row (newer than attacker's poisoned row),
-- list_match_metadata() resolves from the owner's row and ignores attacker's.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
insert into public.score_logs (id, video_id, status, title, home_players, away_players, rules, setup)
values ('44444444-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000002',
        'bound', 'Owner bound log', '{OwnerHome}', '{OwnerAway}', '{}', '{}');

select pg_temp.act_as('bbbbbbbb-0000-0000-0000-000000000002');
do $$
declare r record;
begin
    select * into r from public.list_match_metadata()
     where video_id = '11111111-0000-0000-0000-000000000002';
    if r.home_names <> '{OwnerHome}' or r.away_names <> '{OwnerAway}' then
        raise exception 'list_match_metadata() did not prefer owner score_logs over foreign row: %', r;
    end if;
    if r.home_player_id is not null or r.away_player_id is not null then
        raise exception 'recipient saw owner player IDs with bound score log: %', r;
    end if;
end $$;

reset role;

------------------------------------------------------------------
-- 3. Shared-viewer storage SELECT policies: UID-prefix & granter
------------------------------------------------------------------

-- Owner's legitimate clip & thumbnail for the shared match (11111111-...-0002).
insert into public.rally_clips (id, video_id, owner_id, rally_index, start_timestamp,
                                end_timestamp, duration_seconds,
                                clip_storage_path, thumbnail_storage_path)
values ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000002',
        'aaaaaaaa-0000-0000-0000-000000000001', 1, 0, 5, 5,
        'aaaaaaaa-0000-0000-0000-000000000001/v2/rally_1.mp4',
        'aaaaaaaa-0000-0000-0000-000000000001/v2/rally_1.jpg');

-- Attacker's own video, shared with recipient, carrying a fabricated rally_clips
-- row pointing into owner's storage prefix (unshared secret objects).
insert into public.videos (id, owner_id, filename, size, storage_path, status)
values ('11111111-0000-0000-0000-000000000003', 'cccccccc-0000-0000-0000-000000000003',
        'attacker.mp4', 1, 'cccccccc-0000-0000-0000-000000000003/attacker.mp4', 'completed');

insert into public.match_shares (video_id, shared_with_user_id, granted_by)
values ('11111111-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000002',
        'cccccccc-0000-0000-0000-000000000003');

insert into public.rally_clips (id, video_id, owner_id, rally_index, start_timestamp,
                                end_timestamp, duration_seconds,
                                clip_storage_path, thumbnail_storage_path)
values ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000003',
        'cccccccc-0000-0000-0000-000000000003', 1, 0, 5, 5,
        'aaaaaaaa-0000-0000-0000-000000000001/secret/rally_1.mp4',
        'aaaaaaaa-0000-0000-0000-000000000001/secret/rally_1.jpg');

-- Owner's unshared video with a legitimate clip, paired with a mismatched
-- match_shares row where granted_by is attacker (not rc.owner_id).
insert into public.videos (id, owner_id, filename, size, storage_path, status)
values ('11111111-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000001',
        'unshared.mp4', 1, 'aaaaaaaa-0000-0000-0000-000000000001/unshared.mp4', 'completed');

insert into public.rally_clips (id, video_id, owner_id, rally_index, start_timestamp,
                                end_timestamp, duration_seconds,
                                clip_storage_path, thumbnail_storage_path)
values ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000004',
        'aaaaaaaa-0000-0000-0000-000000000001', 1, 0, 5, 5,
        'aaaaaaaa-0000-0000-0000-000000000001/v4/rally_1.mp4',
        'aaaaaaaa-0000-0000-0000-000000000001/v4/rally_1.jpg');

insert into public.match_shares (video_id, shared_with_user_id, granted_by)
values ('11111111-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000002',
        'cccccccc-0000-0000-0000-000000000003');

insert into storage.objects (bucket_id, name, owner) values
  ('clips',      'aaaaaaaa-0000-0000-0000-000000000001/v2/rally_1.mp4',     'aaaaaaaa-0000-0000-0000-000000000001'),
  ('thumbnails', 'aaaaaaaa-0000-0000-0000-000000000001/v2/rally_1.jpg',     'aaaaaaaa-0000-0000-0000-000000000001'),
  ('clips',      'aaaaaaaa-0000-0000-0000-000000000001/secret/rally_1.mp4', 'aaaaaaaa-0000-0000-0000-000000000001'),
  ('thumbnails', 'aaaaaaaa-0000-0000-0000-000000000001/secret/rally_1.jpg', 'aaaaaaaa-0000-0000-0000-000000000001'),
  ('clips',      'aaaaaaaa-0000-0000-0000-000000000001/v4/rally_1.mp4',     'aaaaaaaa-0000-0000-0000-000000000001'),
  ('thumbnails', 'aaaaaaaa-0000-0000-0000-000000000001/v4/rally_1.jpg',     'aaaaaaaa-0000-0000-0000-000000000001');

select pg_temp.act_as('bbbbbbbb-0000-0000-0000-000000000002');
set local role authenticated;
do $$
begin
    -- Legitimate shared clip and thumbnail are visible to recipient.
    if not exists (
        select 1 from storage.objects
         where bucket_id = 'clips'
           and name = 'aaaaaaaa-0000-0000-0000-000000000001/v2/rally_1.mp4'
    ) then
        raise exception 'recipient could not read legitimately shared clip';
    end if;
    if not exists (
        select 1 from storage.objects
         where bucket_id = 'thumbnails'
           and name = 'aaaaaaaa-0000-0000-0000-000000000001/v2/rally_1.jpg'
    ) then
        raise exception 'recipient could not read legitimately shared thumbnail';
    end if;

    -- Fabricated paths outside rc.owner_id's folder prefix are rejected.
    if exists (
        select 1 from storage.objects
         where bucket_id = 'clips'
           and name = 'aaaaaaaa-0000-0000-0000-000000000001/secret/rally_1.mp4'
    ) then
        raise exception 'recipient read victim clip via fabricated rally_clips path';
    end if;
    if exists (
        select 1 from storage.objects
         where bucket_id = 'thumbnails'
           and name = 'aaaaaaaa-0000-0000-0000-000000000001/secret/rally_1.jpg'
    ) then
        raise exception 'recipient read victim thumbnail via fabricated rally_clips path';
    end if;

    -- Shares whose granted_by does not match rc.owner_id are rejected.
    if exists (
        select 1 from storage.objects
         where bucket_id = 'clips'
           and name = 'aaaaaaaa-0000-0000-0000-000000000001/v4/rally_1.mp4'
    ) then
        raise exception 'recipient read victim clip via mismatched granted_by share';
    end if;
    if exists (
        select 1 from storage.objects
         where bucket_id = 'thumbnails'
           and name = 'aaaaaaaa-0000-0000-0000-000000000001/v4/rally_1.jpg'
    ) then
        raise exception 'recipient read victim thumbnail via mismatched granted_by share';
    end if;
end $$;

reset role;

------------------------------------------------------------------
-- 4. share_match() video ownership check & match_shares FK
------------------------------------------------------------------

-- Fabricated rally_clips row owned by attacker referencing owner's video 0001.
insert into public.rally_clips (id, video_id, owner_id, rally_index, start_timestamp,
                                end_timestamp, duration_seconds, clip_storage_path)
values ('22222222-0000-0000-0000-0000000000f1', '11111111-0000-0000-0000-000000000001',
        'cccccccc-0000-0000-0000-000000000003', 1, 0, 5, 5,
        'cccccccc-0000-0000-0000-000000000003/v1/rally_1.mp4');

-- Attacker cannot share owner's video despite holding a fabricated rally_clips row.
select pg_temp.act_as('cccccccc-0000-0000-0000-000000000003');
set local role authenticated;
select pg_temp.expect_sqlstate(
    'share_match rejects fabricated rally_clips owner',
    $$select public.share_match('11111111-0000-0000-0000-000000000001', 'recipient@test.local')$$,
    'P0002'
);

-- Owner can share a match they own in both videos and rally_clips.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
do $$
declare v_shared_to uuid;
begin
    v_shared_to := public.share_match('11111111-0000-0000-0000-000000000002', 'attacker@test.local');
    if v_shared_to is distinct from 'cccccccc-0000-0000-0000-000000000003'::uuid then
        raise exception 'share_match returned unexpected recipient id: %', v_shared_to;
    end if;
end $$;

reset role;

-- match_shares.video_id FK rejects nonexistent videos on new inserts.
select pg_temp.expect_sqlstate(
    'match_shares FK rejects nonexistent video_id',
    $$insert into public.match_shares (video_id, shared_with_user_id, granted_by)
      values ('11111111-0000-0000-0000-0000000000ff',
              'bbbbbbbb-0000-0000-0000-000000000002',
              'aaaaaaaa-0000-0000-0000-000000000001')$$,
    '23503'
);

rollback;
