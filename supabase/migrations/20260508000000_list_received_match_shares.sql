-- Sharer-email lookup for received matches.
-- See docs/plans/2026-05-08-shared-match-sharer-label-design.md

create or replace function public.list_received_match_shares()
returns table (video_id uuid, sharer_email text, shared_at timestamptz)
language sql
security definer
set search_path = public, auth
as $$
  select ms.video_id, u.email, ms.created_at
    from public.match_shares ms
    join auth.users u on u.id = ms.granted_by
   where ms.shared_with_user_id = auth.uid()
$$;

revoke all   on function public.list_received_match_shares() from public;
grant execute on function public.list_received_match_shares() to authenticated;
