-- Who may write a rally annotation, and onto whose clip.
--
-- A is the owner of a match, B is somebody A shared it with, C is a stranger.
-- match_shares made B's and C's SELECT depend on who owns the CLIP, and left the
-- write policies checking only who owns the ANNOTATION. So anyone who could name
-- a clip id could write onto it, and A could not delete what they wrote.
\set ON_ERROR_STOP on
begin;

-- Only what every version of auth.users has: the image creates the table, and
-- gotrue's later columns are not there.
insert into auth.users (id, email) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'a@test.local'),
  ('bbbbbbbb-0000-0000-0000-000000000002', 'b@test.local'),
  ('cccccccc-0000-0000-0000-000000000003', 'c@test.local');

insert into public.videos (id, owner_id, filename, size, storage_path, status)
values ('11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
        'a.mp4', 1, 'a/a.mp4', 'completed');
insert into public.rally_clips (id, video_id, owner_id, rally_index, start_timestamp,
                                end_timestamp, duration_seconds, clip_storage_path)
values ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001',
        'aaaaaaaa-0000-0000-0000-000000000001', 1, 0, 5, 5, 'a/v/rally_1.mp4');
insert into public.match_shares (video_id, shared_with_user_id, granted_by)
values ('11111111-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002',
        'aaaaaaaa-0000-0000-0000-000000000001');
-- Something a recipient wrote before the policy was tightened.
insert into public.rally_annotations (id, clip_id, owner_id, timestamp_seconds, body)
values ('33333333-0000-0000-0000-00000000000b', '22222222-0000-0000-0000-000000000001',
        'bbbbbbbb-0000-0000-0000-000000000002', 1, 'left behind by B');

create function pg_temp.expect_denied(who uuid, label text, stmt text) returns void
language plpgsql as $$
begin
    -- Both spellings: the bare image's auth.uid() reads the legacy per-claim
    -- setting, a hosted project's reads the JSON one.
    perform set_config('request.jwt.claim.sub', who::text, true);
    perform set_config('request.jwt.claims', json_build_object('sub', who)::text, true);
    set local role authenticated;
    begin
        execute stmt;
    exception when insufficient_privilege or check_violation then
        reset role;
        return;
    end;
    reset role;
    raise exception 'NOT DENIED: %', label;
end $$;

create function pg_temp.run_as(who uuid, stmt text) returns bigint
language plpgsql as $$
declare n bigint;
begin
    -- Both spellings: the bare image's auth.uid() reads the legacy per-claim
    -- setting, a hosted project's reads the JSON one.
    perform set_config('request.jwt.claim.sub', who::text, true);
    perform set_config('request.jwt.claims', json_build_object('sub', who)::text, true);
    set local role authenticated;
    execute stmt;
    get diagnostics n = row_count;
    reset role;
    return n;
end $$;

-- The owner writes on their own clip, and owner_id fills itself in: the app
-- sends no owner_id and has always relied on the column default.
do $$
begin
    if pg_temp.run_as('aaaaaaaa-0000-0000-0000-000000000001',
        $q$insert into public.rally_annotations (clip_id, timestamp_seconds, body)
           values ('22222222-0000-0000-0000-000000000001', 2, 'mine')$q$) <> 1 then
        raise exception 'owner could not annotate their own clip';
    end if;
    if not exists (select 1 from public.rally_annotations
                   where body = 'mine' and owner_id = 'aaaaaaaa-0000-0000-0000-000000000001') then
        raise exception 'owner_id was not defaulted to the caller';
    end if;
end $$;

-- A recipient is read-only, which is what match_shares says it made them.
select pg_temp.expect_denied('bbbbbbbb-0000-0000-0000-000000000002', 'recipient insert',
    $q$insert into public.rally_annotations (clip_id, timestamp_seconds, body)
       values ('22222222-0000-0000-0000-000000000001', 3, 'from B')$q$);

-- A stranger who has learned a clip id is nobody at all.
select pg_temp.expect_denied('cccccccc-0000-0000-0000-000000000003', 'stranger insert',
    $q$insert into public.rally_annotations (clip_id, timestamp_seconds, body)
       values ('22222222-0000-0000-0000-000000000001', 3, 'from C')$q$);

-- Nobody can write in another account's name.
select pg_temp.expect_denied('aaaaaaaa-0000-0000-0000-000000000001', 'insert as someone else',
    $q$insert into public.rally_annotations (clip_id, owner_id, timestamp_seconds, body)
       values ('22222222-0000-0000-0000-000000000001',
               'bbbbbbbb-0000-0000-0000-000000000002', 3, 'forged')$q$);

-- The owner can clear anything off their own clip, including what B left.
do $$
begin
    if pg_temp.run_as('aaaaaaaa-0000-0000-0000-000000000001',
        $q$delete from public.rally_annotations
           where id = '33333333-0000-0000-0000-00000000000b'$q$) <> 1 then
        raise exception 'owner could not delete a foreign annotation on their own clip';
    end if;
end $$;

-- A note cannot be unbounded, and a label on a note obeys the label rules.
select pg_temp.expect_denied('aaaaaaaa-0000-0000-0000-000000000001', 'oversized body',
    $q$insert into public.rally_annotations (clip_id, timestamp_seconds, body)
       values ('22222222-0000-0000-0000-000000000001', 4, repeat('x', 2001))$q$);
select pg_temp.expect_denied('aaaaaaaa-0000-0000-0000-000000000001', 'oversized label',
    $q$insert into public.rally_annotations (clip_id, timestamp_seconds, label_name, label_color)
       values ('22222222-0000-0000-0000-000000000001', 4, repeat('x', 25), 'green')$q$);
select pg_temp.expect_denied('aaaaaaaa-0000-0000-0000-000000000001', 'unknown label colour',
    $q$insert into public.rally_annotations (clip_id, timestamp_seconds, label_name, label_color)
       values ('22222222-0000-0000-0000-000000000001', 4, 'Smash', 'not-a-colour')$q$);

rollback;
