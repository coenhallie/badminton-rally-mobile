-- Match removal: owner delete + recipient leave.
-- See docs/plans/2026-07-18-video-removal-design.md

------------------------------------------------------------------
-- 1. RPC: delete_match — owner permanently deletes a match's rows.
--    Storage objects are deleted by the client BEFORE calling this
--    (the storage policies below check these rows).
------------------------------------------------------------------

create or replace function public.delete_match(p_video_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'unauthenticated' using errcode = 'P0001';
  end if;
  if not exists (
    select 1 from public.videos
    where id = p_video_id and owner_id = auth.uid()
  ) then
    raise exception 'not_owner' using errcode = 'P0002';
  end if;
  delete from public.rally_annotations
    where clip_id in (select id from public.rally_clips where video_id = p_video_id);
  delete from public.rally_clips  where video_id = p_video_id;
  delete from public.match_shares where video_id = p_video_id;
  delete from public.videos       where id = p_video_id;
end $$;

revoke all    on function public.delete_match(uuid) from public;
grant execute on function public.delete_match(uuid) to authenticated;

------------------------------------------------------------------
-- 2. RPC: leave_shared_match — recipient removes a received share.
--    Recipient-side mirror of unshare_match.
------------------------------------------------------------------

create or replace function public.leave_shared_match(p_video_id uuid)
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
      and shared_with_user_id = auth.uid();
end $$;

revoke all    on function public.leave_shared_match(uuid) from public;
grant execute on function public.leave_shared_match(uuid) to authenticated;

------------------------------------------------------------------
-- 3. Storage: owners may delete their own objects. Required for the
--    client-side cleanup that precedes delete_match.
------------------------------------------------------------------

drop policy if exists "storage videos: owner delete" on storage.objects;
create policy "storage videos: owner delete"
    on storage.objects for delete to authenticated
    using (
        bucket_id = 'videos'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "storage clips: owner delete" on storage.objects;
create policy "storage clips: owner delete"
    on storage.objects for delete to authenticated
    using (
        bucket_id = 'clips'
        and exists (
            select 1 from public.rally_clips rc
            where rc.clip_storage_path = storage.objects.name
              and rc.owner_id = auth.uid()
        )
    );

drop policy if exists "storage thumbnails: owner delete" on storage.objects;
create policy "storage thumbnails: owner delete"
    on storage.objects for delete to authenticated
    using (
        bucket_id = 'thumbnails'
        and exists (
            select 1 from public.rally_clips rc
            where rc.thumbnail_storage_path = storage.objects.name
              and rc.owner_id = auth.uid()
        )
    );
