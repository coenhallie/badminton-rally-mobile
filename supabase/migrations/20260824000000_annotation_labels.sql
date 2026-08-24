-- Replaces the fixed three-case rally_annotations.kind with user-owned labels.
-- See docs/plans/2026-08-24-custom-annotation-labels-design.md

create table if not exists public.annotation_labels (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null default auth.uid()
               references auth.users(id) on delete cascade,
    name       text not null check (length(trim(name)) between 1 and 24),
    color_key  text not null check (color_key in (
                   'green','teal','blue','indigo','purple',
                   'pink','red','orange','amber','slate')),
    created_at timestamptz not null default now()
);

create unique index if not exists annotation_labels_owner_name_key
    on public.annotation_labels (owner_id, lower(name));

create index if not exists annotation_labels_owner_created_idx
    on public.annotation_labels (owner_id, created_at);

alter table public.annotation_labels enable row level security;

drop policy if exists annotation_labels_select_own on public.annotation_labels;
create policy annotation_labels_select_own on public.annotation_labels
    for select to authenticated using (owner_id = auth.uid());

drop policy if exists annotation_labels_insert_own on public.annotation_labels;
create policy annotation_labels_insert_own on public.annotation_labels
    for insert to authenticated with check (owner_id = auth.uid());

drop policy if exists annotation_labels_update_own on public.annotation_labels;
create policy annotation_labels_update_own on public.annotation_labels
    for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists annotation_labels_delete_own on public.annotation_labels;
create policy annotation_labels_delete_own on public.annotation_labels
    for delete to authenticated using (owner_id = auth.uid());

-- Seeding. owner_id is set from new.id explicitly: inside an auth.users insert
-- there is no authenticated session, so the column default auth.uid() would
-- yield NULL, the not-null would fire, and signup itself would roll back.
-- security definer so the insert is not blocked by the policies above.
create or replace function public.seed_annotation_labels()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.annotation_labels (owner_id, name, color_key) values
        (new.id, 'Good shot',      'green'),
        (new.id, 'Forced error',   'amber'),
        (new.id, 'Unforced error', 'red')
    on conflict do nothing;
    return new;
end;
$$;

drop trigger if exists seed_annotation_labels_on_signup on auth.users;
create trigger seed_annotation_labels_on_signup
    after insert on auth.users
    for each row execute function public.seed_annotation_labels();

-- Backfill accounts that already exist.
insert into public.annotation_labels (owner_id, name, color_key)
select u.id, d.name, d.color_key
from auth.users u
cross join (values
    ('Good shot',      'green'),
    ('Forced error',   'amber'),
    ('Unforced error', 'red')
) as d(name, color_key)
on conflict do nothing;

-- rally_annotations: snapshot the label instead of referencing a kind.
alter table public.rally_annotations
    add column if not exists label_name  text,
    add column if not exists label_color text;

-- Backfill before the column goes. add constraint validates existing rows on
-- the spot, and 20260505000000 dropped body's not-null precisely so a
-- badge-only annotation could exist; such a row would have a null body and a
-- null label_name, violate the new CHECK, and abort this migration. Guarded
-- on kind still existing so a re-run after a prior run already dropped it
-- does not fail with "column kind does not exist".
do $$
begin
    if exists (
        select 1 from information_schema.columns
        where table_schema = 'public'
          and table_name = 'rally_annotations'
          and column_name = 'kind'
    ) then
        execute $backfill$
            update public.rally_annotations set
                label_name  = case kind when 'good_shot'      then 'Good shot'
                                        when 'forced_error'   then 'Forced error'
                                        when 'unforced_error' then 'Unforced error' end,
                label_color = case kind when 'good_shot'      then 'green'
                                        when 'forced_error'   then 'amber'
                                        when 'unforced_error' then 'red' end
            where kind is not null
        $backfill$;
    end if;
end $$;

alter table public.rally_annotations
    drop constraint if exists rally_annotations_body_or_kind_check,
    drop constraint if exists rally_annotations_kind_check,
    drop constraint if exists rally_annotations_body_or_label_check,
    drop column if exists kind;

alter table public.rally_annotations
    add constraint rally_annotations_body_or_label_check
    check (
        (label_name is not null and length(trim(label_name)) > 0)
        or (body is not null and length(trim(body)) > 0)
    );
