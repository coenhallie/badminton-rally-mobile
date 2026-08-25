-- Match-level description on videos, plus the read-back RPC the phone uses for
-- both title and description.
-- See docs/plans/2026-08-25-local-video-metadata-design.md

------------------------------------------------------------------
-- 1. videos.description
------------------------------------------------------------------

-- Column and constraint are added independently, each guarded, because the two
-- starting points diverge: on the live project videos.title was added by hand
-- in the SQL Editor and carries no CHECK, while a fresh rebuild has both. Web
-- migration 0008 records the same divergence for title.
alter table public.videos
  add column if not exists description text;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.videos'::regclass
      and conname = 'videos_description_length_check'
  ) then
    alter table public.videos
      add constraint videos_description_length_check
      check (description is null or length(description) between 1 and 500);
  end if;
end $$;

-- Deliberately no `grant update (description) on public.videos to authenticated`,
-- mirroring title: 0002 revokes UPDATE and re-grants column by column, and 0008
-- withheld the title grant so title is insert-only by design. Description follows
-- it, which is what makes the app's edit window (local entries only) a data
-- integrity rule rather than a UI convention. A rename-after-upload flow would
-- make this grant mandatory. Note a service_role smoke test cannot catch its
-- absence, because service_role has BYPASSRLS.

------------------------------------------------------------------
-- 2. list_match_metadata()
------------------------------------------------------------------

-- The phone builds its match list by grouping rally_clips and has never read the
-- videos table. Rather than widen videos_owner_select -- which would also hand
-- share recipients storage_path, results_meta, error, processed_video_path and
-- player_labels -- this security definer function returns exactly the three
-- columns the feature needs. Same shape as list_received_match_shares,
-- list_match_shares and leave_shared_match.
--
-- No arguments: the match list needs metadata for every match it can see, and
-- the payload is three text columns per row.
create or replace function public.list_match_metadata()
returns table (video_id uuid, title text, description text)
language sql
security definer
set search_path = public
as $$
  -- The exists clause is a structural copy of the using clause on
  -- "clips: select own or shared" (20260506000000_match_shares.sql), which is the
  -- authority on which matches a user can see. If the two diverge, a card's title
  -- would appear or vanish independently of the match itself, so change them together.
  select v.id, v.title, v.description
    from public.videos v
   where v.owner_id = auth.uid()
      or exists (
        select 1 from public.match_shares ms
         where ms.video_id = v.id
           and ms.shared_with_user_id = auth.uid()
      )
$$;

revoke all   on function public.list_match_metadata() from public;
grant execute on function public.list_match_metadata() to authenticated;
