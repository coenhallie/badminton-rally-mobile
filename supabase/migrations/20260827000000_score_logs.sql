-- Live scoring: a match that exists before any video does.
-- See docs/plans/2026-08-26-live-scoring-review-design.md

create table if not exists public.score_logs (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null default auth.uid()
               references auth.users(id) on delete cascade,

    -- Nullable, and ON DELETE SET NULL rather than CASCADE. Both are load bearing.
    -- A match is created before any video exists, and it has to survive that video
    -- being deleted: delete_match cascades rally_annotations away, so once L2
    -- writes the coach's courtside tags onto clips, this row is the only place
    -- they still exist. Cascading here would silently destroy work he did on a
    -- bench before the video was ever recorded.
    video_id   uuid references public.videos(id) on delete set null,

    title      text not null check (length(trim(title)) between 1 and 80),

    -- The competitors. One name for singles, two for doubles; index 1 is the
    -- player who starts the match in the right service court.
    home_players text[] not null check (
                   cardinality(home_players) between 1 and 2
                   and array_position(home_players, null) is null
                 ),
    away_players text[] not null check (
                   cardinality(away_players) between 1 and 2
                   and array_position(away_players, null) is null
                 ),

    -- ScoringRules, MatchSetup and List<ScoreEvent> as the shared module
    -- serializes them. jsonb rather than text so a later spectator page can read a
    -- score in SQL without shipping the fold to the database.
    rules      jsonb not null,
    setup      jsonb not null,
    events     jsonb not null default '[]'::jsonb,

    -- All four states of §7.1 are enumerated now so the vocabulary is fixed in one
    -- place. Only 'live' and 'unbound' are reachable until L2 attaches a video:
    -- nothing in this release can set 'bound' or 'reconciled', and that is
    -- deliberate rather than an oversight.
    status     text not null default 'live'
               check (status in ('live', 'unbound', 'bound', 'reconciled')),

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- The match list sorts on created_at descending and reads only the owner's rows.
create index if not exists score_logs_owner_created_idx
    on public.score_logs (owner_id, created_at desc);

-- L2 looks a match up from its video. Partial, because most rows have no video.
create index if not exists score_logs_video_idx
    on public.score_logs (video_id) where video_id is not null;

alter table public.score_logs enable row level security;

-- Owner only, all four verbs. Sharing a score-only match is not possible:
-- match_shares is keyed (video_id, shared_with_user_id), so it becomes available
-- when a video is attached and not before. See §7.1.
drop policy if exists score_logs_select_own on public.score_logs;
create policy score_logs_select_own on public.score_logs
    for select to authenticated using (owner_id = auth.uid());

drop policy if exists score_logs_insert_own on public.score_logs;
create policy score_logs_insert_own on public.score_logs
    for insert to authenticated with check (owner_id = auth.uid());

drop policy if exists score_logs_update_own on public.score_logs;
create policy score_logs_update_own on public.score_logs
    for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists score_logs_delete_own on public.score_logs;
create policy score_logs_delete_own on public.score_logs
    for delete to authenticated using (owner_id = auth.uid());

-- Detaching a video must leave the match 'unbound', not stranded as 'bound' with
-- no video. The foreign key above nulls the column on delete, which is what keeps
-- the coach's courtside tags alive (see the comment on video_id), but a status
-- left saying 'bound' would then describe a binding that no longer exists - and
-- L2 reads that status to decide whether a match can be re-bound to a new upload.
-- Normalising here rather than in the client because the client is not the one
-- doing the update: the cascade is.
create or replace function public.unbind_score_log_on_video_delete()
returns trigger
language plpgsql
as $$
begin
    if new.video_id is null and old.video_id is not null
       and new.status in ('bound', 'reconciled') then
        new.status = 'unbound';
    end if;
    return new;
end;
$$;

drop trigger if exists score_logs_unbind_on_video_delete on public.score_logs;
create trigger score_logs_unbind_on_video_delete
    before update on public.score_logs
    for each row execute function public.unbind_score_log_on_video_delete();

-- updated_at is a trigger rather than a client write. The client upserts whole
-- rows from a local-first cache, and a client that forgets the field would leave
-- a stale timestamp that a later sync rule might trust.
create or replace function public.touch_score_logs_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists score_logs_touch_updated_at on public.score_logs;
create trigger score_logs_touch_updated_at
    before update on public.score_logs
    for each row execute function public.touch_score_logs_updated_at();
