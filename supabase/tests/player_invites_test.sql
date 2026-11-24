-- Coach-to-player invites, linked player RLS, write guards, cloud pose RPC,
-- linked score logs RPC, and synced drill session logs.
-- See docs/superpowers/specs/2026-11-23-player-invites-design.md
\set ON_ERROR_STOP on
begin;

-- Ensure buckets exist for storage policy assertions.
insert into storage.buckets (id, name) values
  ('videos', 'videos'),
  ('clips', 'clips'),
  ('thumbnails', 'thumbnails'),
  ('results', 'results')
on conflict (id) do nothing;

-- Coach A (legacy JWT without app_metadata), Coach B, Player Marco (account_role='player'),
-- and Stranger.
insert into auth.users (id, email, raw_app_meta_data) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'coach@test.local',    '{}'::jsonb),
  ('aaaaaaaa-0000-0000-0000-000000000002', 'coach2@test.local',   '{}'::jsonb),
  ('aaaaaaaa-0000-0000-0000-000000000003', 'marco@test.local',    '{"account_role":"player"}'::jsonb),
  ('aaaaaaaa-0000-0000-0000-000000000004', 'stranger@test.local', '{}'::jsonb);

-- 1. Verify seed_annotation_labels skipped player signup and seeded coach signup.
do $$
begin
    if not exists (
        select 1 from public.annotation_labels
         where owner_id = 'aaaaaaaa-0000-0000-0000-000000000001'
    ) then
        raise exception 'coach did not receive seeded annotation labels';
    end if;
    if exists (
        select 1 from public.annotation_labels
         where owner_id = 'aaaaaaaa-0000-0000-0000-000000000003'
    ) then
        raise exception 'player account should not receive default coach annotation labels';
    end if;
end $$;

create function pg_temp.act_as(uid text, role_name text default null) returns void
language sql as $$
    select set_config('request.jwt.claim.sub', uid, true);
    select set_config(
        'request.jwt.claims',
        case
            when role_name is null then json_build_object('sub', uid)::text
            else json_build_object('sub', uid, 'app_metadata', json_build_object('account_role', role_name))::text
        end,
        true
    );
$$;

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
-- 2. Coach creates two players (Marco and Tom) and matches
------------------------------------------------------------------

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
set local role authenticated;

insert into public.players (id, name, racket_arm) values
  ('bbbbbbbb-0000-0000-0000-000000000001', 'Marco', 'right'),
  ('bbbbbbbb-0000-0000-0000-000000000002', 'Tom',   'left');

-- Video-only match (Marco vs Tom) with cloud pose artifact
insert into public.videos (id, owner_id, filename, size, storage_path, status,
                           home_player_id, away_player_id, near_side,
                           results_meta, manual_court_keypoints)
values (
  '11111111-0000-0000-0000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000001',
  'match1.mp4', 100,
  'aaaaaaaa-0000-0000-0000-000000000001/match1.mp4',
  'completed',
  'bbbbbbbb-0000-0000-0000-000000000001',
  'bbbbbbbb-0000-0000-0000-000000000002',
  'home',
  '{"poses_artifact_path":"aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/poses.raw","poses_frame_count":1200}'::jsonb,
  '{"corners":[[0,0],[1,0],[1,1],[0,1]]}'::jsonb
);

-- Unassigned / Tom-only video match (Marco must NOT see this)
insert into public.videos (id, owner_id, filename, size, storage_path, status, away_player_id)
values (
  '11111111-0000-0000-0000-000000000002',
  'aaaaaaaa-0000-0000-0000-000000000001',
  'tom.mp4', 100,
  'aaaaaaaa-0000-0000-0000-000000000001/tom.mp4',
  'completed',
  'bbbbbbbb-0000-0000-0000-000000000002'
);

-- Score-only match (Marco vs Tom, no video)
insert into public.score_logs (id, title, rules, setup, home_player_id, away_player_id)
values (
  '44444444-0000-0000-0000-000000000001',
  'Club Night', '{}', '{}',
  'bbbbbbbb-0000-0000-0000-000000000001',
  'bbbbbbbb-0000-0000-0000-000000000002'
);

reset role;

insert into public.rally_clips (id, video_id, owner_id, rally_index, start_timestamp, end_timestamp, duration_seconds, clip_storage_path, thumbnail_storage_path)
values
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001',
   'aaaaaaaa-0000-0000-0000-000000000001', 1, 0, 10, 10,
   'aaaaaaaa-0000-0000-0000-000000000001/c1.mp4', 'aaaaaaaa-0000-0000-0000-000000000001/t1.jpg'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000002',
   'aaaaaaaa-0000-0000-0000-000000000001', 1, 0, 10, 10,
   'aaaaaaaa-0000-0000-0000-000000000001/c2.mp4', 'aaaaaaaa-0000-0000-0000-000000000001/t2.jpg');

insert into public.rally_annotations (id, clip_id, owner_id, timestamp_seconds, body)
values ('33333333-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000001',
        'aaaaaaaa-0000-0000-0000-000000000001', 3.5, 'Recover faster');

insert into storage.objects (bucket_id, name, owner) values
  ('videos',     'aaaaaaaa-0000-0000-0000-000000000001/match1.mp4', 'aaaaaaaa-0000-0000-0000-000000000001'),
  ('clips',      'aaaaaaaa-0000-0000-0000-000000000001/c1.mp4',     'aaaaaaaa-0000-0000-0000-000000000001'),
  ('thumbnails', 'aaaaaaaa-0000-0000-0000-000000000001/t1.jpg',     'aaaaaaaa-0000-0000-0000-000000000001'),
  ('results',    'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/poses.raw', 'aaaaaaaa-0000-0000-0000-000000000001');

------------------------------------------------------------------
-- 3. Invite creation, regeneration, stranger denial, and redemption
------------------------------------------------------------------

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000004');
set local role authenticated;
select pg_temp.expect_sqlstate(
    'stranger cannot create invite for coach player',
    $$select * from public.create_player_invite('bbbbbbbb-0000-0000-0000-000000000001')$$,
    'P0002'
);

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
do $$
declare
    r1 record;
    r2 record;
    st record;
begin
    select * into r1 from public.create_player_invite('bbbbbbbb-0000-0000-0000-000000000001');
    if r1.code !~ '^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$' then
        raise exception 'bad invite code format: %', r1.code;
    end if;

    -- Regenerating revokes first code and issues a new active one.
    select * into r2 from public.create_player_invite('bbbbbbbb-0000-0000-0000-000000000001');
    if r2.invite_id = r1.invite_id then
        raise exception 'regenerate did not issue a new invite row';
    end if;

    select * into st from public.list_player_access_states()
     where player_id = 'bbbbbbbb-0000-0000-0000-000000000001';
    if st.status <> 'pending' or st.pending_code <> r2.code then
        raise exception 'expected pending status with new code, got %', st;
    end if;

    -- Coach cannot redeem own invite.
    begin
        perform * from public.redeem_player_invite_authenticated(r2.code);
        raise exception 'coach should not redeem own invite';
    exception when sqlstate 'P0004' then
        null;
    end;

    -- Store active code in temp config for Marco's redemption below.
    perform set_config('app.test_code', r2.code, true);
    perform set_config('app.old_code',  r1.code, true);
end $$;

-- Marco redeems the active code; revoked old code is rejected.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000003', 'player');

select pg_temp.expect_sqlstate(
    'revoked invite code rejected',
    $$select * from public.redeem_player_invite_authenticated(current_setting('app.old_code'))$$,
    'P0006'
);

do $$
declare
    res record;
begin
    select * into res from public.redeem_player_invite_authenticated(
        substr(current_setting('app.test_code'), 1, 4) || '-' || substr(current_setting('app.test_code'), 5, 4)
    );
    if res.player_id <> 'bbbbbbbb-0000-0000-0000-000000000001'::uuid or res.player_name <> 'Marco' then
        raise exception 'unexpected redemption output: %', res;
    end if;
end $$;

-- Re-using the redeemed code fails.
select pg_temp.expect_sqlstate(
    'reused invite code rejected',
    $$select * from public.redeem_player_invite_authenticated(current_setting('app.test_code'))$$,
    'P0006'
);

------------------------------------------------------------------
-- 4. Linked player read access, privacy masking & server-side write guards
------------------------------------------------------------------

do $$
declare
    m   record;
    sl  record;
    pa  record;
    cnt int;
begin
    -- Own player profile visible; Tom's hidden.
    select count(*) into cnt from public.players;
    if cnt <> 1 then
        raise exception 'linked player should see only own profile, saw %', cnt;
    end if;

    -- Raw public.videos and public.score_logs stay strictly owner-only.
    if exists (select 1 from public.videos) then
        raise exception 'linked player must not directly select public.videos';
    end if;
    if exists (select 1 from public.score_logs) then
        raise exception 'linked player must not directly select public.score_logs';
    end if;

    -- list_match_metadata returns Marco's video with has_cloud_pose=true,
    -- Marco's own player_id visible, Tom's player_id masked, Tom's name resolved.
    select * into m from public.list_match_metadata()
     where video_id = '11111111-0000-0000-0000-000000000001';
    if not found or not m.has_cloud_pose then
        raise exception 'expected match metadata with has_cloud_pose=true, got %', m;
    end if;
    if m.home_player_id <> 'bbbbbbbb-0000-0000-0000-000000000001'::uuid
       or m.away_player_id is not null
       or m.home_names <> '{Marco}'
       or m.away_names <> '{Tom}' then
        raise exception 'metadata masking/resolution wrong: %', m;
    end if;
    if exists (select 1 from public.list_match_metadata()
                where video_id = '11111111-0000-0000-0000-000000000002') then
        raise exception 'linked player saw Tom-only video';
    end if;

    -- list_linked_score_logs returns score-only match with Tom's name resolved and UUID masked.
    select * into sl from public.list_linked_score_logs()
     where id = '44444444-0000-0000-0000-000000000001';
    if not found
       or sl.home_player_id <> 'bbbbbbbb-0000-0000-0000-000000000001'::uuid
       or sl.away_player_id is not null
       or sl.away_players <> '{Tom}' then
        raise exception 'linked score log masking/resolution wrong: %', sl;
    end if;

    -- get_cloud_pose_artifact works for Marco's match and returns nothing for Tom's match.
    select * into pa from public.get_cloud_pose_artifact('11111111-0000-0000-0000-000000000001');
    if not found or pa.poses_frame_count <> 1200 then
        raise exception 'linked player could not read pose artifact metadata: %', pa;
    end if;
    if exists (select 1 from public.get_cloud_pose_artifact('11111111-0000-0000-0000-000000000002')) then
        raise exception 'linked player read Tom pose artifact';
    end if;

    -- Clips, annotations, and storage objects (clips, thumbnails, results) readable; videos bucket NOT readable.
    select count(*) into cnt from public.rally_clips;
    if cnt <> 1 then raise exception 'expected 1 visible clip, got %', cnt; end if;
    select count(*) into cnt from public.rally_annotations;
    if cnt <> 1 then raise exception 'expected 1 visible annotation, got %', cnt; end if;
    if not exists (select 1 from storage.objects where bucket_id = 'clips' and name = 'aaaaaaaa-0000-0000-0000-000000000001/c1.mp4') then
        raise exception 'linked player cannot read clip storage object';
    end if;
    if not exists (select 1 from storage.objects where bucket_id = 'results' and name = 'aaaaaaaa-0000-0000-0000-000000000001/11111111-0000-0000-0000-000000000001/poses.raw') then
        raise exception 'linked player cannot read results storage object';
    end if;
    if exists (select 1 from storage.objects where bucket_id = 'videos') then
        raise exception 'linked player must not read raw videos storage bucket';
    end if;
end $$;

-- Player account_role='player' is denied creating coach resources server-side.
select pg_temp.expect_sqlstate(
    'player account cannot insert into players',
    $$insert into public.players (name) values ('MyOwnPlayer')$$,
    '42501'
);
select pg_temp.expect_sqlstate(
    'player account cannot insert into score_logs',
    $$insert into public.score_logs (title, rules, setup) values ('Own', '{}', '{}')$$,
    '42501'
);
select pg_temp.expect_sqlstate(
    'player account cannot insert into videos',
    $$insert into public.videos (owner_id, filename, size, storage_path, status)
      values ('aaaaaaaa-0000-0000-0000-000000000003', 'x.mp4', 1, 'x/x.mp4', 'uploaded')$$,
    '42501'
);
select pg_temp.expect_sqlstate(
    'player account cannot upload to storage videos bucket',
    $$insert into storage.objects (bucket_id, name, owner)
      values ('videos', 'aaaaaaaa-0000-0000-0000-000000000003/x.mp4', 'aaaaaaaa-0000-0000-0000-000000000003')$$,
    '42501'
);

------------------------------------------------------------------
-- 5. Synced drill_session_logs (player logs -> coach sees; delete rules)
------------------------------------------------------------------

insert into public.drill_session_logs (
    id, player_id, exercise_id, completed_at, sets_completed, sets_target, duration_seconds, effort, note
) values (
    '66666666-0000-0000-0000-000000000001',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'base-mark-6-corner-shadow',
    now(), 4, 4, 240, 'solid', 'Felt sharp'
);

-- Coach logs a session for Marco too and sees Marco's session.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
insert into public.drill_session_logs (
    id, player_id, exercise_id, completed_at, sets_completed, sets_target, duration_seconds, effort
) values (
    '66666666-0000-0000-0000-000000000002',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'split-squat-endurance',
    now(), 3, 3, 180, 'hard'
);

do $$
declare cnt int;
begin
    select count(*) into cnt from public.drill_session_logs
     where player_id = 'bbbbbbbb-0000-0000-0000-000000000001';
    if cnt <> 2 then
        raise exception 'coach should see both drill logs, saw %', cnt;
    end if;
end $$;

-- Player cannot delete coach-logged session, but CAN delete own session.
select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000003', 'player');
do $$
declare n int;
begin
    delete from public.drill_session_logs where id = '66666666-0000-0000-0000-000000000002';
    get diagnostics n = row_count;
    if n <> 0 then
        raise exception 'player deleted coach-logged drill session';
    end if;
    delete from public.drill_session_logs where id = '66666666-0000-0000-0000-000000000001';
    get diagnostics n = row_count;
    if n <> 1 then
        raise exception 'player could not delete own drill session';
    end if;
end $$;

------------------------------------------------------------------
-- 6. Unlinking revokes all access immediately without deleting data
------------------------------------------------------------------

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000001');
select public.unlink_player_login('bbbbbbbb-0000-0000-0000-000000000001');

select pg_temp.act_as('aaaaaaaa-0000-0000-0000-000000000003', 'player');
do $$
begin
    if exists (select 1 from public.list_my_player_profiles()) then
        raise exception 'unlinked player still has profile';
    end if;
    if exists (select 1 from public.list_match_metadata()) then
        raise exception 'unlinked player still sees match metadata';
    end if;
    if exists (select 1 from public.list_linked_score_logs()) then
        raise exception 'unlinked player still sees score logs';
    end if;
    if exists (select 1 from public.drill_session_logs) then
        raise exception 'unlinked player still sees drill logs';
    end if;
end $$;

rollback;
