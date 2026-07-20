-- The analyze retry fix (mobile adfb483) resets a video's status row from the
-- client before every process-video trigger:
--   PATCH videos SET status='uploaded', error=NULL, progress=NULL WHERE id=...
-- The videos table uses column-level UPDATE grants for `authenticated`
-- (filename, manual_court_keypoints, ...), so this PATCH fails with
-- 42501 "permission denied for table videos" and every analyze dies at start.
--
-- Column-level grants are additive; RLS (owner-only update policy) still
-- restricts WHICH rows can be updated — this only widens which columns.

grant update (status, error, progress) on public.videos to authenticated;
