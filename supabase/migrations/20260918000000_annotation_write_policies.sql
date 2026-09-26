-- Who may write a rally annotation, and what one may hold.
-- Tested by supabase/tests/annotation_writes_test.sql (supabase/tests/run.sh).
--
-- 20260506000000_match_shares made SELECT on rally_annotations depend on who owns
-- the CLIP, so a share recipient can read a match's notes. Its comment says the
-- write policies "remain untouched, so only the match owner can write". They were
-- untouched, but they never said that: the base policies check who owns the
-- ANNOTATION and nothing about the clip. Any signed-in account that could name a
-- clip id, which every recipient can, could write a note onto it under its own
-- owner_id. The note showed up for the owner and every other recipient, bumped
-- annotation_count through the security definer trigger, survived an unshare, and
-- could not be deleted by the owner, whose delete matched zero rows.

-- 1. owner_id fills itself in. The app has never sent it and relies on this
--    default, which exists on the production database and in no migration: a
--    database rebuilt from these files rejected every annotation insert.
alter table public.rally_annotations
    alter column owner_id set default auth.uid();

-- 2. Writes belong to the owner of the clip.
drop policy if exists "annotations_owner_insert"            on public.rally_annotations;
drop policy if exists "annotations: insert on own clip"     on public.rally_annotations;
create policy "annotations: insert on own clip"
    on public.rally_annotations for insert to authenticated
    with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.rally_clips rc
            where rc.id = clip_id and rc.owner_id = auth.uid()
        )
    );

drop policy if exists "annotations_owner_update"            on public.rally_annotations;
drop policy if exists "annotations: update own on own clip" on public.rally_annotations;
create policy "annotations: update own on own clip"
    on public.rally_annotations for update to authenticated
    using (owner_id = auth.uid())
    with check (
        owner_id = auth.uid()
        and exists (
            select 1 from public.rally_clips rc
            where rc.id = clip_id and rc.owner_id = auth.uid()
        )
    );

-- Delete is wider than insert on purpose: the clip's owner can clear anything off
-- their own clip, which is the only way out for a note somebody else wrote there
-- before this migration.
drop policy if exists "annotations_owner_delete"            on public.rally_annotations;
drop policy if exists "annotations: delete own or on own clip" on public.rally_annotations;
create policy "annotations: delete own or on own clip"
    on public.rally_annotations for delete to authenticated
    using (
        owner_id = auth.uid()
        or exists (
            select 1 from public.rally_clips rc
            where rc.id = clip_id and rc.owner_id = auth.uid()
        )
    );

-- 3. Bounds. 20260505000000 dropped the base table's 1..2000 check on body to make
--    body optional and nothing put the upper bound back; label_name and
--    label_color arrived in 20260824000000 with no bound at all, though the labels
--    they snapshot are capped at 24 characters and ten colour keys.
--
--    NOT VALID: enforced for every write from here on, without failing this
--    migration on a row that already breaks the rule. The owner can fix or delete
--    such a row; refusing to deploy over it helps nobody.
alter table public.rally_annotations
    drop constraint if exists rally_annotations_body_length_check,
    drop constraint if exists rally_annotations_label_name_length_check,
    drop constraint if exists rally_annotations_label_color_check;

alter table public.rally_annotations
    add constraint rally_annotations_body_length_check
        check (body is null or length(body) <= 2000) not valid,
    add constraint rally_annotations_label_name_length_check
        check (label_name is null or length(trim(label_name)) between 1 and 24) not valid,
    add constraint rally_annotations_label_color_check
        check (label_color is null or label_color in (
            'green','teal','blue','indigo','purple',
            'pink','red','orange','amber','slate')) not valid;
