-- The supabase/postgres image ships the auth schema but not storage's tables:
-- those are created by the storage-api service's own migrations. The policies
-- under test only need the two tables and the one helper to exist.
create schema if not exists storage;
create table if not exists storage.buckets (
    id text primary key, name text not null, public boolean default false
);
create table if not exists storage.objects (
    id uuid primary key default gen_random_uuid(),
    bucket_id text references storage.buckets(id),
    name text, owner uuid, created_at timestamptz default now()
);
alter table storage.objects enable row level security;
create or replace function storage.foldername(name text) returns text[]
language sql immutable as $$ select (string_to_array(name, '/'))[1:array_length(string_to_array(name, '/'), 1) - 1] $$;
grant usage on schema storage to anon, authenticated, service_role;
grant all on storage.objects, storage.buckets to authenticated, service_role;
