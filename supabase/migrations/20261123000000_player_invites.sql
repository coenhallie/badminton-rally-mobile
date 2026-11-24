-- Coach-to-player invites, linked player profiles, unified video read access,
-- server-side player write guards, and cloud-synced drill session logs.
-- See docs/superpowers/specs/2026-11-23-player-invites-design.md

------------------------------------------------------------------
-- 1. Account role & access helpers
------------------------------------------------------------------

-- Existing/legacy coach JWTs omit app_metadata.account_role and evaluate to true.
-- Player accounts created via redeem-player-invite carry account_role = 'player'.
create or replace function public.is_coach_account()
returns boolean
language sql
stable
set search_path = pg_catalog, public
as $$
    select coalesce(auth.jwt() -> 'app_metadata' ->> 'account_role', '') <> 'player';
$$;

revoke all    on function public.is_coach_account() from public;
grant execute on function public.is_coach_account() to authenticated;

------------------------------------------------------------------
-- 2. player_logins (many profiles to one login; at most one login per profile)
------------------------------------------------------------------

create table if not exists public.player_logins (
    player_id uuid primary key references public.players(id) on delete cascade,
    user_id   uuid not null references auth.users(id) on delete cascade,
    linked_at timestamptz not null default now()
);

create index if not exists player_logins_user_idx
    on public.player_logins (user_id);

alter table public.player_logins enable row level security;

-- Security-definer ownership/link probes break circular RLS dependencies between
-- players, player_logins, videos, and storage.objects.
create or replace function public.owns_player(p_player_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select exists (
        select 1 from public.players p
         where p.id = p_player_id and p.owner_id = auth.uid()
    );
$$;

revoke all    on function public.owns_player(uuid) from public;
grant execute on function public.owns_player(uuid) to authenticated;

create or replace function public.is_linked_to_player(p_player_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select exists (
        select 1 from public.player_logins pl
         where pl.player_id = p_player_id and pl.user_id = auth.uid()
    );
$$;

revoke all    on function public.is_linked_to_player(uuid) from public;
grant execute on function public.is_linked_to_player(uuid) to authenticated;

drop policy if exists player_logins_select_own_or_coach on public.player_logins;
create policy player_logins_select_own_or_coach on public.player_logins
    for select to authenticated
    using (
        user_id = auth.uid()
        or public.owns_player(player_id)
    );

-- Intentionally no INSERT/UPDATE/DELETE policies: writes go through
-- SECURITY DEFINER RPCs or the service_role Edge Function only.

------------------------------------------------------------------
-- 3. player_invites (single-use 8-char Crockford codes, 7-day expiry)
------------------------------------------------------------------

create table if not exists public.player_invites (
    id          uuid primary key default gen_random_uuid(),
    player_id   uuid not null references public.players(id) on delete cascade,
    coach_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
    code        text not null unique
                check (code ~ '^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$'),
    expires_at  timestamptz not null default (now() + interval '7 days'),
    redeemed_at timestamptz null,
    redeemed_by uuid null references auth.users(id) on delete set null,
    revoked_at  timestamptz null,
    created_at  timestamptz not null default now()
);

-- At most one active (unredeemed, unrevoked) invite per player at a time.
create unique index if not exists player_invites_one_active_per_player_idx
    on public.player_invites (player_id)
    where redeemed_at is null and revoked_at is null;

create index if not exists player_invites_coach_idx
    on public.player_invites (coach_id, player_id);

alter table public.player_invites enable row level security;

drop policy if exists player_invites_select_coach on public.player_invites;
create policy player_invites_select_coach on public.player_invites
    for select to authenticated
    using (coach_id = auth.uid());

-- Intentionally no INSERT/UPDATE/DELETE policies: mutations go through RPCs / service_role.

------------------------------------------------------------------
-- 4. invite_rate_limits (service_role only, survives Edge Function cold starts)
------------------------------------------------------------------

create table if not exists public.invite_rate_limits (
    bucket_key    text primary key,
    window_start  timestamptz not null,
    attempt_count int not null
);

alter table public.invite_rate_limits enable row level security;
-- Zero policies for anon/authenticated: service_role only.

create or replace function public.check_invite_rate_limit(
    p_bucket_key     text,
    p_max_attempts   int default 20,
    p_window_seconds int default 900
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_now   timestamptz := now();
    v_count int;
begin
    insert into public.invite_rate_limits (bucket_key, window_start, attempt_count)
    values (p_bucket_key, v_now, 1)
    on conflict (bucket_key) do update
       set window_start  = case
                               when public.invite_rate_limits.window_start
                                    + make_interval(secs => p_window_seconds) <= v_now
                               then v_now
                               else public.invite_rate_limits.window_start
                           end,
           attempt_count = case
                               when public.invite_rate_limits.window_start
                                    + make_interval(secs => p_window_seconds) <= v_now
                               then 1
                               else public.invite_rate_limits.attempt_count + 1
                           end
    returning attempt_count into v_count;

    return v_count <= p_max_attempts;
end;
$$;

revoke all    on function public.check_invite_rate_limit(text, int, int) from public;
grant execute on function public.check_invite_rate_limit(text, int, int) to service_role;

------------------------------------------------------------------
-- 5. Unified player & video access predicates
------------------------------------------------------------------

create or replace function public.can_access_player(p_player_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select exists (
        select 1 from public.players p
         where p.id = p_player_id and p.owner_id = auth.uid()
    ) or exists (
        select 1 from public.player_logins pl
         where pl.player_id = p_player_id and pl.user_id = auth.uid()
    );
$$;

revoke all    on function public.can_access_player(uuid) from public;
grant execute on function public.can_access_player(uuid) to authenticated;

-- True when the caller is a linked player on the authoritative record for
-- p_video_id: the oldest owner-scoped bound score log if one exists, else the
-- video itself.
create or replace function public.is_linked_player_on_video(p_video_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
declare
    v_owner_id uuid;
    v_home_id  uuid;
    v_away_id  uuid;
    s_home_id  uuid;
    s_away_id  uuid;
    v_has_log  boolean := false;
begin
    if auth.uid() is null then
        return false;
    end if;

    select v.owner_id, v.home_player_id, v.away_player_id
      into v_owner_id, v_home_id, v_away_id
      from public.videos v
     where v.id = p_video_id;

    if not found then
        return false;
    end if;

    select s.home_player_id, s.away_player_id, true
      into s_home_id, s_away_id, v_has_log
      from public.score_logs s
     where s.video_id = p_video_id
       and s.owner_id = v_owner_id
     order by s.created_at
     limit 1;

    if v_has_log then
        v_home_id := s_home_id;
        v_away_id := s_away_id;
    end if;

    if v_home_id is null and v_away_id is null then
        return false;
    end if;

    return exists (
        select 1 from public.player_logins pl
         where pl.user_id = auth.uid()
           and pl.player_id in (v_home_id, v_away_id)
    );
end;
$$;

revoke all    on function public.is_linked_player_on_video(uuid) from public;
grant execute on function public.is_linked_player_on_video(uuid) to authenticated;

-- Single authority for reading a video's clips, annotations, thumbnails, and metadata:
-- owner, explicit match_shares recipient (granted by owner), or linked player.
create or replace function public.can_read_video(p_video_id uuid)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select exists (
        select 1 from public.videos v
         where v.id = p_video_id
           and (
               v.owner_id = auth.uid()
               or exists (
                   select 1 from public.match_shares ms
                    where ms.video_id = v.id
                      and ms.shared_with_user_id = auth.uid()
                      and ms.granted_by = v.owner_id
               )
               or public.is_linked_player_on_video(v.id)
           )
    );
$$;

revoke all    on function public.can_read_video(uuid) from public;
grant execute on function public.can_read_video(uuid) to authenticated;

------------------------------------------------------------------
-- 6. Server-side player write guards (restrictive policies)
------------------------------------------------------------------

-- Restrictive policies AND with every existing permissive policy regardless of
-- base policy naming, ensuring invited player accounts stay read-only across
-- mobile, web, and API (outside drill_session_logs).
do $$
declare
    t text;
begin
    foreach t in array array[
        'players', 'videos', 'score_logs',
        'annotation_labels', 'rally_clips', 'rally_annotations'
    ] loop
        execute format('drop policy if exists %I on public.%I', t || '_coaches_only_insert', t);
        execute format(
            'create policy %I on public.%I as restrictive for insert to authenticated with check (public.is_coach_account())',
            t || '_coaches_only_insert', t
        );
        execute format('drop policy if exists %I on public.%I', t || '_coaches_only_update', t);
        execute format(
            'create policy %I on public.%I as restrictive for update to authenticated using (public.is_coach_account()) with check (public.is_coach_account())',
            t || '_coaches_only_update', t
        );
        execute format('drop policy if exists %I on public.%I', t || '_coaches_only_delete', t);
        execute format(
            'create policy %I on public.%I as restrictive for delete to authenticated using (public.is_coach_account())',
            t || '_coaches_only_delete', t
        );
    end loop;
end $$;

drop policy if exists "storage: coaches only insert" on storage.objects;
create policy "storage: coaches only insert"
    on storage.objects as restrictive for insert to authenticated
    with check (public.is_coach_account());

drop policy if exists "storage: coaches only update" on storage.objects;
create policy "storage: coaches only update"
    on storage.objects as restrictive for update to authenticated
    using (public.is_coach_account())
    with check (public.is_coach_account());

drop policy if exists "storage: coaches only delete" on storage.objects;
create policy "storage: coaches only delete"
    on storage.objects as restrictive for delete to authenticated
    using (public.is_coach_account());

------------------------------------------------------------------
-- 7. Widened read RLS & storage policies using can_read_video()
------------------------------------------------------------------

-- players: owner or linked login may read; writes remain owner + coach only.
drop policy if exists players_select_own on public.players;
drop policy if exists players_select_own_or_linked on public.players;
create policy players_select_own_or_linked on public.players
    for select to authenticated
    using (
        owner_id = auth.uid()
        or public.is_linked_to_player(id)
    );

-- rally_clips: consolidated on can_read_video().
drop policy if exists "clips: select own or shared" on public.rally_clips;
create policy "clips: select own or shared"
    on public.rally_clips for select to authenticated
    using (
        owner_id = auth.uid()
        or public.can_read_video(video_id)
    );

-- rally_annotations: consolidated on can_read_video().
drop policy if exists "annotations: select own or shared" on public.rally_annotations;
create policy "annotations: select own or shared"
    on public.rally_annotations for select to authenticated
    using (
        exists (
            select 1 from public.rally_clips rc
             where rc.id = public.rally_annotations.clip_id
               and (rc.owner_id = auth.uid() or public.can_read_video(rc.video_id))
        )
    );

-- Storage helpers run as SECURITY DEFINER so storage.objects policies can check
-- public.videos ownership & results_meta while raw public.videos SELECT stays owner-only.
create or replace function public.can_read_clip_object(p_bucket text, p_object_name text)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, storage
as $$
    select exists (
        select 1
          from public.rally_clips rc
          join public.videos v on v.id = rc.video_id and v.owner_id = rc.owner_id
         where (
                   (p_bucket = 'clips' and rc.clip_storage_path = p_object_name)
                or (p_bucket = 'thumbnails' and rc.thumbnail_storage_path = p_object_name)
               )
           and (storage.foldername(p_object_name))[1] = rc.owner_id::text
           and public.can_read_video(rc.video_id)
    );
$$;

revoke all    on function public.can_read_clip_object(text, text) from public;
grant execute on function public.can_read_clip_object(text, text) to authenticated;

create or replace function public.can_read_result_object(p_object_name text)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, storage
as $$
    select exists (
        select 1
          from public.videos v
         where v.results_meta ->> 'poses_artifact_path' = p_object_name
           and (storage.foldername(p_object_name))[1] = v.owner_id::text
           and public.is_linked_player_on_video(v.id)
    );
$$;

revoke all    on function public.can_read_result_object(text) from public;
grant execute on function public.can_read_result_object(text) to authenticated;

-- Storage: clips & thumbnails readable by shared viewers AND linked players,
-- preserving owner UID folder prefix verification.
drop policy if exists "storage clips: shared viewers" on storage.objects;
create policy "storage clips: shared viewers"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'clips'
        and public.can_read_clip_object('clips', storage.objects.name)
    );

drop policy if exists "storage thumbnails: shared viewers" on storage.objects;
create policy "storage thumbnails: shared viewers"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'thumbnails'
        and public.can_read_clip_object('thumbnails', storage.objects.name)
    );

-- Storage: results bucket (poses.raw) readable by linked players ONLY (not ad-hoc shares).
drop policy if exists "storage results: linked players" on storage.objects;
create policy "storage results: linked players"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'results'
        and public.can_read_result_object(storage.objects.name)
    );

------------------------------------------------------------------
-- 8. drill_session_logs (cloud-synced workouts for coach & player)
------------------------------------------------------------------

create table if not exists public.drill_session_logs (
    id                uuid primary key default gen_random_uuid(),
    player_id         uuid not null references public.players(id) on delete cascade,
    logged_by_user_id uuid not null default auth.uid()
                      references auth.users(id) on delete cascade,
    exercise_id       text not null check (char_length(btrim(exercise_id)) between 1 and 80),
    match_id          uuid null,
    completed_at      timestamptz not null,
    sets_completed    int not null check (sets_completed >= 0),
    sets_target       int not null check (sets_target >= 1),
    duration_seconds  int not null check (duration_seconds >= 0),
    effort            text not null check (effort in ('easy', 'solid', 'hard')),
    note              text not null default '' check (char_length(note) <= 500),
    created_at        timestamptz not null default now()
);

create index if not exists drill_session_logs_player_completed_idx
    on public.drill_session_logs (player_id, completed_at desc, id);

alter table public.drill_session_logs enable row level security;

drop policy if exists drill_logs_select_accessible on public.drill_session_logs;
create policy drill_logs_select_accessible on public.drill_session_logs
    for select to authenticated
    using (public.can_access_player(player_id));

drop policy if exists drill_logs_insert_accessible on public.drill_session_logs;
create policy drill_logs_insert_accessible on public.drill_session_logs
    for insert to authenticated
    with check (
        public.can_access_player(player_id)
        and logged_by_user_id = auth.uid()
    );

drop policy if exists drill_logs_delete_own_or_coach on public.drill_session_logs;
create policy drill_logs_delete_own_or_coach on public.drill_session_logs
    for delete to authenticated
    using (
        logged_by_user_id = auth.uid()
        or public.owns_player(player_id)
    );

------------------------------------------------------------------
-- 9. Skip default coach annotation labels on player signups
------------------------------------------------------------------

create or replace function public.seed_annotation_labels()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if coalesce(new.raw_app_meta_data ->> 'account_role', '') = 'player' then
        return new;
    end if;
    insert into public.annotation_labels (owner_id, name, color_key) values
        (new.id, 'Good shot',      'green'),
        (new.id, 'Forced error',   'amber'),
        (new.id, 'Unforced error', 'red')
    on conflict do nothing;
    return new;
end;
$$;

------------------------------------------------------------------
-- 10. Updated list_match_metadata() + narrow read RPCs
------------------------------------------------------------------

-- Dropped and recreated because has_cloud_pose is added to the return columns.
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
    away_names     text[],
    has_cloud_pose boolean
)
language sql
security definer
set search_path = pg_catalog, public
as $$
    select v.id,
           v.title,
           v.description,
           v.home_players,
           v.away_players,
           v.format,
           -- Owner sees both profile UUIDs. A linked player sees ONLY their own
           -- profile UUID (from the authoritative record: bound score log else video),
           -- never an opponent's profile UUID. Ad-hoc share recipients see neither.
           case
               when v.owner_id = auth.uid() then v.home_player_id
               when (case when sl.has_sl then sl.home_player_id else v.home_player_id end) is not null
                    and exists (
                        select 1 from public.player_logins pl
                         where pl.user_id = auth.uid()
                           and pl.player_id = (case when sl.has_sl then sl.home_player_id else v.home_player_id end)
                    )
               then (case when sl.has_sl then sl.home_player_id else v.home_player_id end)
               else null
           end,
           case
               when v.owner_id = auth.uid() then v.away_player_id
               when (case when sl.has_sl then sl.away_player_id else v.away_player_id end) is not null
                    and exists (
                        select 1 from public.player_logins pl
                         where pl.user_id = auth.uid()
                           and pl.player_id = (case when sl.has_sl then sl.away_player_id else v.away_player_id end)
                    )
               then (case when sl.has_sl then sl.away_player_id else v.away_player_id end)
               else null
           end,
           v.near_side,
           coalesce(
               sl.home_names,
               case when v.home_player_id is not null then array[vh.name] else v.home_players end
           ),
           coalesce(
               sl.away_names,
               case when v.away_player_id is not null then array[va.name] else v.away_players end
           ),
           case
               when (v.owner_id = auth.uid() or public.is_linked_player_on_video(v.id))
                    and coalesce(v.results_meta ->> 'poses_artifact_path', '') <> ''
                    and v.manual_court_keypoints is not null
               then true
               else false
           end
      from public.videos v
      left join public.players vh on vh.id = v.home_player_id
      left join public.players va on va.id = v.away_player_id
      left join lateral (
          select true as has_sl,
                 s.home_player_id,
                 s.away_player_id,
                 case when s.home_player_id is not null then array[sh.name] else s.home_players end as home_names,
                 case when s.away_player_id is not null then array[sa.name] else s.away_players end as away_names
            from public.score_logs s
            left join public.players sh on sh.id = s.home_player_id
            left join public.players sa on sa.id = s.away_player_id
           where s.video_id = v.id
             and s.owner_id = v.owner_id
           order by s.created_at
           limit 1
      ) sl on true
     where public.can_read_video(v.id);
$$;

revoke all    on function public.list_match_metadata() from public;
grant execute on function public.list_match_metadata() to authenticated;

-- Narrow read RPC for cloud pose artifacts: keeps raw public.videos SELECT owner-only
-- so linked players never read opponent player_ids, storage_path, or player_labels.
create or replace function public.get_cloud_pose_artifact(p_video_id uuid)
returns table (
    poses_artifact_path    text,
    poses_frame_count      int,
    manual_court_keypoints jsonb
)
language sql
security definer
set search_path = pg_catalog, public
as $$
    select v.results_meta ->> 'poses_artifact_path',
           coalesce(nullif(v.results_meta ->> 'poses_frame_count', '')::int, 0),
           v.manual_court_keypoints
      from public.videos v
     where v.id = p_video_id
       and (v.owner_id = auth.uid() or public.is_linked_player_on_video(v.id))
       and coalesce(v.results_meta ->> 'poses_artifact_path', '') <> ''
       and v.manual_court_keypoints is not null;
$$;

revoke all    on function public.get_cloud_pose_artifact(uuid) from public;
grant execute on function public.get_cloud_pose_artifact(uuid) to authenticated;

-- Narrow read RPC for linked players' score logs: resolves any opponent profile to
-- its display name in home_players/away_players and masks opponent player_id to null.
create or replace function public.list_linked_score_logs()
returns table (
    id             uuid,
    owner_id       uuid,
    video_id       uuid,
    title          text,
    home_players   text[],
    away_players   text[],
    home_player_id uuid,
    away_player_id uuid,
    format         text,
    rules          jsonb,
    setup          jsonb,
    events         jsonb,
    status         text,
    created_at     timestamptz,
    updated_at     timestamptz
)
language sql
security definer
set search_path = pg_catalog, public
as $$
    select s.id,
           s.owner_id,
           s.video_id,
           s.title,
           case
               when s.home_player_id is not null and lh.player_id is null
               then array[ph.name]
               else s.home_players
           end,
           case
               when s.away_player_id is not null and la.player_id is null
               then array[pa.name]
               else s.away_players
           end,
           case when lh.player_id is not null then s.home_player_id else null end,
           case when la.player_id is not null then s.away_player_id else null end,
           s.format,
           s.rules,
           s.setup,
           s.events,
           s.status,
           s.created_at,
           s.updated_at
      from public.score_logs s
      left join public.player_logins lh
        on lh.player_id = s.home_player_id and lh.user_id = auth.uid()
      left join public.player_logins la
        on la.player_id = s.away_player_id and la.user_id = auth.uid()
      left join public.players ph on ph.id = s.home_player_id
      left join public.players pa on pa.id = s.away_player_id
     where s.owner_id <> auth.uid()
       and (lh.player_id is not null or la.player_id is not null)
     order by s.created_at desc, s.id asc;
$$;

revoke all    on function public.list_linked_score_logs() from public;
grant execute on function public.list_linked_score_logs() to authenticated;

------------------------------------------------------------------
-- 11. Invite management & redemption RPCs
------------------------------------------------------------------

-- Generates an unbiased 8-character Crockford-alphabet code using core
-- pg_catalog.gen_random_uuid() (no pgcrypto dependency required).
create or replace function public.generate_invite_code()
returns text
language plpgsql
volatile
set search_path = pg_catalog, public
as $$
declare
    v_alphabet constant text := '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
    v_bytes    bytea;
    v_code     text := '';
    v_idx      int;
    v_byte     int;
begin
    while char_length(v_code) < 8 loop
        v_bytes := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
        for v_idx in 0..15 loop
            exit when char_length(v_code) >= 8;
            v_byte := get_byte(v_bytes, v_idx);
            -- Reject >= 248 (= 8 * 31) to keep modulo 31 uniform.
            if v_byte < 248 then
                v_code := v_code || substr(v_alphabet, (v_byte % 31) + 1, 1);
            end if;
        end loop;
    end loop;
    return v_code;
end;
$$;

revoke all on function public.generate_invite_code() from public;

create or replace function public.create_player_invite(p_player_id uuid)
returns table (
    invite_id  uuid,
    code       text,
    expires_at timestamptz
)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_code       text;
    v_invite_id  uuid;
    v_expires_at timestamptz;
    v_attempts   int := 0;
begin
    if auth.uid() is null then
        raise exception 'unauthenticated' using errcode = 'P0001';
    end if;
    if not public.is_coach_account() then
        raise exception 'not_owner' using errcode = 'P0002';
    end if;
    if not exists (
        select 1 from public.players p
         where p.id = p_player_id and p.owner_id = auth.uid()
    ) then
        raise exception 'not_owner' using errcode = 'P0002';
    end if;
    if exists (
        select 1 from public.player_logins pl
         where pl.player_id = p_player_id
    ) then
        raise exception 'already_linked' using errcode = 'P0005';
    end if;

    -- Revoke any existing active invite so regenerating is atomic.
    update public.player_invites
       set revoked_at = now()
     where player_id = p_player_id
       and redeemed_at is null
       and revoked_at is null;

    loop
        v_attempts := v_attempts + 1;
        v_code := public.generate_invite_code();
        begin
            insert into public.player_invites (player_id, coach_id, code)
            values (p_player_id, auth.uid(), v_code)
            returning id, public.player_invites.expires_at
                 into v_invite_id, v_expires_at;
            exit;
        exception when unique_violation then
            if v_attempts >= 10 then
                raise;
            end if;
        end;
    end loop;

    return query select v_invite_id, v_code, v_expires_at;
end;
$$;

revoke all    on function public.create_player_invite(uuid) from public;
grant execute on function public.create_player_invite(uuid) to authenticated;

create or replace function public.revoke_player_invite(p_player_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if auth.uid() is null then
        raise exception 'unauthenticated' using errcode = 'P0001';
    end if;
    if not exists (
        select 1 from public.players p
         where p.id = p_player_id and p.owner_id = auth.uid()
    ) then
        raise exception 'not_owner' using errcode = 'P0002';
    end if;

    update public.player_invites
       set revoked_at = now()
     where player_id = p_player_id
       and coach_id = auth.uid()
       and redeemed_at is null
       and revoked_at is null;
end;
$$;

revoke all    on function public.revoke_player_invite(uuid) from public;
grant execute on function public.revoke_player_invite(uuid) to authenticated;

create or replace function public.unlink_player_login(p_player_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if auth.uid() is null then
        raise exception 'unauthenticated' using errcode = 'P0001';
    end if;
    if not exists (
        select 1 from public.players p
         where p.id = p_player_id and p.owner_id = auth.uid()
    ) then
        raise exception 'not_owner' using errcode = 'P0002';
    end if;

    delete from public.player_logins
     where player_id = p_player_id;

    update public.player_invites
       set revoked_at = now()
     where player_id = p_player_id
       and redeemed_at is null
       and revoked_at is null;
end;
$$;

revoke all    on function public.unlink_player_login(uuid) from public;
grant execute on function public.unlink_player_login(uuid) to authenticated;

create or replace function public.list_player_access_states()
returns table (
    player_id          uuid,
    status             text,
    linked_email       text,
    linked_at          timestamptz,
    pending_code       text,
    pending_expires_at timestamptz
)
language sql
security definer
set search_path = pg_catalog, public, auth
as $$
    select p.id as player_id,
           case
               when pl.player_id is not null then 'linked'
               when pi.id is not null and pi.expires_at > now() then 'pending'
               else 'uninvited'
           end as status,
           u.email as linked_email,
           pl.linked_at,
           case when pl.player_id is null and pi.expires_at > now() then pi.code else null end as pending_code,
           case when pl.player_id is null and pi.expires_at > now() then pi.expires_at else null end as pending_expires_at
      from public.players p
      left join public.player_logins pl on pl.player_id = p.id
      left join auth.users u on u.id = pl.user_id
      left join public.player_invites pi
        on pi.player_id = p.id
       and pi.redeemed_at is null
       and pi.revoked_at is null
     where p.owner_id = auth.uid()
     order by lower(p.name), p.id;
$$;

revoke all    on function public.list_player_access_states() from public;
grant execute on function public.list_player_access_states() to authenticated;

create or replace function public.list_my_player_profiles()
returns table (
    player_id   uuid,
    coach_id    uuid,
    coach_email text,
    name        text,
    racket_arm  text,
    linked_at   timestamptz
)
language sql
security definer
set search_path = pg_catalog, public, auth
as $$
    select p.id as player_id,
           p.owner_id as coach_id,
           coalesce(u.email, '') as coach_email,
           p.name,
           p.racket_arm,
           pl.linked_at
      from public.player_logins pl
      join public.players p on p.id = pl.player_id
      left join auth.users u on u.id = p.owner_id
     where pl.user_id = auth.uid()
     order by pl.linked_at asc, p.id asc;
$$;

revoke all    on function public.list_my_player_profiles() from public;
grant execute on function public.list_my_player_profiles() to authenticated;

-- Redeems a code for an ALREADY signed-in user (joining a 2nd coach or linking
-- an existing Shuttl account).
create or replace function public.redeem_player_invite_authenticated(p_code text)
returns table (
    player_id   uuid,
    player_name text,
    racket_arm  text,
    coach_email text
)
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_norm    text;
    v_invite  record;
    v_player  record;
    v_coach   text;
begin
    if auth.uid() is null then
        raise exception 'unauthenticated' using errcode = 'P0001';
    end if;

    v_norm := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
    if v_norm !~ '^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$' then
        raise exception 'invalid_or_expired' using errcode = 'P0006';
    end if;

    select pi.*
      into v_invite
      from public.player_invites pi
     where pi.code = v_norm
       and pi.redeemed_at is null
       and pi.revoked_at is null
       and pi.expires_at > now()
       for update;

    if not found then
        raise exception 'invalid_or_expired' using errcode = 'P0006';
    end if;

    if v_invite.coach_id = auth.uid() then
        raise exception 'cannot_invite_self' using errcode = 'P0004';
    end if;

    if exists (select 1 from public.player_logins pl where pl.player_id = v_invite.player_id) then
        raise exception 'already_linked' using errcode = 'P0005';
    end if;

    select p.id, p.name, p.racket_arm
      into v_player
      from public.players p
     where p.id = v_invite.player_id;

    select coalesce(u.email, '')
      into v_coach
      from auth.users u
     where u.id = v_invite.coach_id;

    insert into public.player_logins (player_id, user_id)
    values (v_invite.player_id, auth.uid());

    update public.player_invites
       set redeemed_at = now(),
           redeemed_by = auth.uid()
     where id = v_invite.id;

    return query select v_player.id, v_player.name, v_player.racket_arm, v_coach;
end;
$$;

revoke all    on function public.redeem_player_invite_authenticated(text) from public;
grant execute on function public.redeem_player_invite_authenticated(text) to authenticated;

-- Service-role atomic claim used by the redeem-player-invite Edge Function after
-- auth.admin.createUser() succeeds. Locks the invite row FOR UPDATE so two
-- simultaneous requests can never both redeem the same code.
create or replace function public.complete_player_invite_redemption(
    p_invite_id uuid,
    p_user_id   uuid
)
returns table (
    player_id   uuid,
    player_name text,
    coach_email text
)
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    v_invite record;
    v_player record;
    v_coach  text;
begin
    select pi.*
      into v_invite
      from public.player_invites pi
     where pi.id = p_invite_id
       and pi.redeemed_at is null
       and pi.revoked_at is null
       and pi.expires_at > now()
       for update;

    if not found then
        raise exception 'invalid_or_expired' using errcode = 'P0006';
    end if;

    if v_invite.coach_id = p_user_id then
        raise exception 'cannot_invite_self' using errcode = 'P0004';
    end if;

    if exists (select 1 from public.player_logins pl where pl.player_id = v_invite.player_id) then
        raise exception 'already_linked' using errcode = 'P0005';
    end if;

    select p.id, p.name
      into v_player
      from public.players p
     where p.id = v_invite.player_id;

    select coalesce(u.email, '')
      into v_coach
      from auth.users u
     where u.id = v_invite.coach_id;

    insert into public.player_logins (player_id, user_id)
    values (v_invite.player_id, p_user_id);

    update public.player_invites
       set redeemed_at = now(),
           redeemed_by = p_user_id
     where id = v_invite.id;

    return query select v_player.id, v_player.name, v_coach;
end;
$$;

revoke all    on function public.complete_player_invite_redemption(uuid, uuid) from public;
grant execute on function public.complete_player_invite_redemption(uuid, uuid) to service_role;
