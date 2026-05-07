-- Shareable matches.
-- See docs/plans/2026-05-06-shareable-matches-design.md

------------------------------------------------------------------
-- 1. match_shares table
------------------------------------------------------------------

create table if not exists public.match_shares (
    video_id            uuid        not null,
    shared_with_user_id uuid        not null references auth.users(id) on delete cascade,
    granted_by          uuid        not null references auth.users(id) on delete cascade,
    created_at          timestamptz not null default now(),
    primary key (video_id, shared_with_user_id),
    constraint no_self_share check (shared_with_user_id <> granted_by)
);

create index if not exists match_shares_recipient_idx
    on public.match_shares (shared_with_user_id);

alter table public.match_shares enable row level security;

drop policy if exists "shares: select own or received" on public.match_shares;
create policy "shares: select own or received"
    on public.match_shares for select to authenticated
    using (granted_by = auth.uid() or shared_with_user_id = auth.uid());

-- Intentionally no INSERT/UPDATE/DELETE policies: writes go through
-- SECURITY DEFINER RPCs only.

------------------------------------------------------------------
-- 2. Extend SELECT policies on rally_clips and rally_annotations
------------------------------------------------------------------

-- Replace the existing owner-only SELECT policy with the owner-or-shared one.
-- The owner-only INSERT/UPDATE/DELETE policies on rally_clips remain untouched.
drop policy if exists "clips_owner_select"            on public.rally_clips;
drop policy if exists "clips: select own or shared"   on public.rally_clips;

create policy "clips: select own or shared"
    on public.rally_clips for select to authenticated
    using (
        owner_id = auth.uid()
        or exists (
            select 1 from public.match_shares ms
            where ms.video_id = public.rally_clips.video_id
              and ms.shared_with_user_id = auth.uid()
        )
    );

-- Replace the existing owner-only SELECT policy with the owner-or-shared one.
-- The annotations_owner_insert/update/delete policies remain untouched, so only
-- the match owner can write — shared recipients are read-only.
drop policy if exists "annotations_owner_select"            on public.rally_annotations;
drop policy if exists "annotations: select own or shared"   on public.rally_annotations;

create policy "annotations: select own or shared"
    on public.rally_annotations for select to authenticated
    using (
        exists (
            select 1 from public.rally_clips rc
            where rc.id = public.rally_annotations.clip_id
              and (
                  rc.owner_id = auth.uid()
                  or exists (
                      select 1 from public.match_shares ms
                      where ms.video_id = rc.video_id
                        and ms.shared_with_user_id = auth.uid()
                  )
              )
        )
    );

------------------------------------------------------------------
-- 3. Storage policies for shared viewers
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
        )
    );

------------------------------------------------------------------
-- 4. RPCs: share_match, unshare_match, list_match_shares
------------------------------------------------------------------

create or replace function public.share_match(p_video_id uuid, p_email text)
returns uuid
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_user uuid;
begin
  if auth.uid() is null then
    raise exception 'unauthenticated' using errcode = 'P0001';
  end if;
  if not exists (
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

revoke all   on function public.share_match(uuid, text) from public;
grant execute on function public.share_match(uuid, text) to authenticated;

create or replace function public.unshare_match(p_video_id uuid, p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'unauthenticated' using errcode = 'P0001';
  end if;
  delete from public.match_shares
    where video_id = p_video_id
      and shared_with_user_id = p_user_id
      and granted_by = auth.uid();
end $$;

revoke all   on function public.unshare_match(uuid, uuid) from public;
grant execute on function public.unshare_match(uuid, uuid) to authenticated;

create or replace function public.list_match_shares(p_video_id uuid)
returns table (shared_with_user_id uuid, email text, created_at timestamptz)
language sql
security definer
set search_path = public, auth
as $$
  select ms.shared_with_user_id, u.email, ms.created_at
    from public.match_shares ms
    join auth.users u on u.id = ms.shared_with_user_id
   where ms.video_id = p_video_id
     and ms.granted_by = auth.uid()
   order by ms.created_at desc
$$;

revoke all   on function public.list_match_shares(uuid) from public;
grant execute on function public.list_match_shares(uuid) to authenticated;
