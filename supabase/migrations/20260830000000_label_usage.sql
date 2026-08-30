-- Where a label may be offered: the courtside board, the clip note pickers, or
-- both. See docs/plans/2026-08-30-scoreboard-label-scope-design.md
--
-- The default is the whole upgrade story: every label that already exists keeps
-- appearing everywhere it appears today, with no backfill statement and no
-- client-side migration. seed_annotation_labels() needs no change either - its
-- insert does not name this column, so newly signed-up accounts get 'both'.

alter table public.annotation_labels
    add column if not exists usage text not null default 'both';

-- Added separately from the column so a re-run over a database that already has
-- it still converges, matching the drop-then-add shape 20260824000000 uses.
alter table public.annotation_labels
    drop constraint if exists annotation_labels_usage_check,
    add constraint annotation_labels_usage_check
        check (usage in ('both','scoreboard','clips'));
