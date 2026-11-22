-- Security hardening and performance indexes:
-- 1. Enforce score_logs.video_id ownership on insert/update.
-- 2. Scope list_match_metadata() score_logs join by owner and mask player IDs
--    from share recipients.
-- 3. Enforce owner UID prefix and granter match on shared-viewer storage SELECT
--    policies.
-- 4. Verify video ownership in share_match() and add match_shares.video_id FK.
-- 5. Pin search_path on trigger functions.
-- 6. Add missing performance indexes for sharing, score_logs, players, and
--    storage policy lookups.

------------------------------------------------------------------
-- 1. Cross-tenant score_logs.video_id binding guard
------------------------------------------------------------------

-- A foreign key alone would accept any coach's video_id. security definer,
-- because the caller's RLS hides other coaches' videos and the check must not
-- distinguish "someone else's" from "does not exist": both fail with the same
-- message, matching check_match_players_owner().
create or replace function public.check_score_log_video_owner()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if new.video_id is not null and not exists (
        select 1 from public.videos v
         where v.id = new.video_id and v.owner_id = new.owner_id
    ) then
        raise exception 'video % belongs to another coach', new.video_id;
    end if;
    return new;
end;
$$;

drop trigger if exists score_logs_video_owner on public.score_logs;
create trigger score_logs_video_owner
    before insert or update of video_id, owner_id on public.score_logs
    for each row execute function public.check_score_log_video_owner();

------------------------------------------------------------------
-- 2. list_match_metadata(): owner-scoped score_logs & ID masking
------------------------------------------------------------------

create or replace function public.list_match_metadata()
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
set search_path = pg_catalog, public
as $$
  -- Scope the lateral score_logs lookup by v.owner_id so a foreign score_logs
  -- row cannot poison another coach's resolved names, and mask player profile
  -- UUIDs from share recipients who cannot read the owner's players table.
  select v.id, v.title, v.description, v.home_players, v.away_players, v.format,
         case when v.owner_id = auth.uid() then v.home_player_id end,
         case when v.owner_id = auth.uid() then v.away_player_id end,
         v.near_side,
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
         and s.owner_id = v.owner_id
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

revoke all    on function public.list_match_metadata() from public;
grant execute on function public.list_match_metadata() to authenticated;

------------------------------------------------------------------
-- 3. Shared-viewer storage SELECT policies: UID-prefix & granter
------------------------------------------------------------------

drop policy if exists "storage clips: shared viewers"      on storage.objects;
drop policy if exists "storage thumbnails: shared viewers" on storage.objects;

create policy "storage clips: shared viewers"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'clips'
        and exists (
            select 1
              from public.rally_clips rc
              join public.match_shares ms on ms.video_id = rc.video_id
             where rc.clip_storage_path = storage.objects.name
               and ms.shared_with_user_id = auth.uid()
               and (storage.foldername(storage.objects.name))[1] = rc.owner_id::text
               and ms.granted_by = rc.owner_id
        )
    );

create policy "storage thumbnails: shared viewers"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'thumbnails'
        and exists (
            select 1
              from public.rally_clips rc
              join public.match_shares ms on ms.video_id = rc.video_id
             where rc.thumbnail_storage_path = storage.objects.name
               and ms.shared_with_user_id = auth.uid()
               and (storage.foldername(storage.objects.name))[1] = rc.owner_id::text
               and ms.granted_by = rc.owner_id
        )
    );

------------------------------------------------------------------
-- 4. share_match() video ownership check & match_shares FK
------------------------------------------------------------------

create or replace function public.share_match(p_video_id uuid, p_email text)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  v_user uuid;
begin
  if auth.uid() is null then
    raise exception 'unauthenticated' using errcode = 'P0001';
  end if;
  if not exists (
    select 1 from public.videos
    where id = p_video_id and owner_id = auth.uid()
  ) or not exists (
    select 1 from public.rally_clips
    where video_id = p_video_id and owner_id = auth.uid()
  ) then
    raise exception 'not_owner' using errcode = 'P0002';
  end if;
  select id into v_user from auth.users where lower(email) = lower(trim(p_email));
  if v_user is null then
    raise exception 'no_such_user' using errcode = 'P0003';
  end if;
  if v_user = auth.uid() then
    raise exception 'cannot_share_with_self' using errcode = 'P0004';
  end if;
  insert into public.match_shares (video_id, shared_with_user_id, granted_by)
    values (p_video_id, v_user, auth.uid())
    on conflict (video_id, shared_with_user_id) do nothing;
  return v_user;
end $$;

revoke all    on function public.share_match(uuid, text) from public;
grant execute on function public.share_match(uuid, text) to authenticated;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.match_shares'::regclass
          and contype = 'f'
          and confrelid = 'public.videos'::regclass
    ) then
        alter table public.match_shares
            add constraint match_shares_video_id_fkey
            foreign key (video_id) references public.videos(id) on delete cascade
            not valid;
    end if;
end $$;

------------------------------------------------------------------
-- 5. Trigger search_path hardening
------------------------------------------------------------------

alter function public.touch_players_updated_at()
    set search_path = pg_catalog, public;
alter function public.unbind_score_log_on_video_delete()
    set search_path = pg_catalog, public;
alter function public.touch_score_logs_updated_at()
    set search_path = pg_catalog, public;

------------------------------------------------------------------
-- 6. Performance indexes
------------------------------------------------------------------

create index if not exists match_shares_granted_by_idx
    on public.match_shares (granted_by);

create index if not exists match_shares_recipient_video_idx
    on public.match_shares (shared_with_user_id, video_id);

create index if not exists score_logs_owner_video_created_idx
    on public.score_logs (owner_id, video_id, created_at)
    where video_id is not null;

create index if not exists players_owner_created_id_idx
    on public.players (owner_id, created_at, id);

create index if not exists rally_clips_clip_path_idx
    on public.rally_clips (clip_storage_path);

create index if not exists rally_clips_thumb_path_idx
    on public.rally_clips (thumbnail_storage_path)
    where thumbnail_storage_path is not null;
