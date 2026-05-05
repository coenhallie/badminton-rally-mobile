-- Adds shot-quality badges to rally_annotations.
-- See docs/plans/2026-05-05-annotation-badges-design.md

alter table public.rally_annotations
    add column if not exists kind text;

alter table public.rally_annotations
    drop constraint if exists rally_annotations_kind_check;

alter table public.rally_annotations
    add constraint rally_annotations_kind_check
    check (kind is null or kind in ('good_shot','forced_error','unforced_error'));

-- body becomes optional but at least one of body/kind must be present.
alter table public.rally_annotations
    alter column body drop not null;

alter table public.rally_annotations
    drop constraint if exists rally_annotations_body_or_kind_check;

alter table public.rally_annotations
    add constraint rally_annotations_body_or_kind_check
    check (
        kind is not null
        or (body is not null and length(trim(body)) > 0)
    );
