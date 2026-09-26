-- A match as the app writes it now. When it is created it names nobody and has no
-- format; the details editor fills those in later. Until 20260920000000 the player
-- columns demanded at least one name each, so the first insert here was refused.
\set ON_ERROR_STOP on
begin;

insert into auth.users (id, email) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'a@test.local');

create function pg_temp.expect_refused(label text, stmt text) returns void
language plpgsql as $$
begin
    begin
        execute stmt;
    exception when check_violation or not_null_violation then
        return;
    end;
    raise exception 'expected the database to refuse: %', label;
end $$;

select set_config('request.jwt.claim.sub', 'aaaaaaaa-0000-0000-0000-000000000001', true);
select set_config('request.jwt.claims',
                  json_build_object('sub', 'aaaaaaaa-0000-0000-0000-000000000001')::text, true);
set local role authenticated;

-- As created: the client sends empty arrays and a null format.
insert into public.score_logs (id, title, home_players, away_players, format, rules, setup, events, status)
values ('44444444-0000-0000-0000-000000000001', 'Sep 20, 14:30 - Amsterdam', '{}', '{}', null,
        '{"points_to_win":11,"win_by":2,"cap":15,"interval_at":6,"games_to_win":1,"change_ends_at":6}',
        '{"first_server":"home"}', '[]', 'live');

-- A client that leaves the columns out altogether gets the same thing.
insert into public.score_logs (id, title, rules, setup)
values ('44444444-0000-0000-0000-000000000002', 'No details sent',
        '{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11}',
        '{"first_server":"home"}');

-- As edited afterwards.
update public.score_logs
   set title = 'Club final', format = 'mixed_doubles',
       home_players = '{Coen,Ana}', away_players = '{Marco,Li}'
 where id = '44444444-0000-0000-0000-000000000001';

do $$
declare row public.score_logs;
begin
    select * into row from public.score_logs where id = '44444444-0000-0000-0000-000000000001';
    if row.format <> 'mixed_doubles' or row.home_players <> '{Coen,Ana}' then
        raise exception 'the edited details did not come back: %', row;
    end if;
    select * into row from public.score_logs where id = '44444444-0000-0000-0000-000000000002';
    if row.home_players <> '{}' or row.away_players <> '{}' or row.format is not null then
        raise exception 'an insert without details did not default to none: %', row;
    end if;
end $$;

select pg_temp.expect_refused('a format the app does not know',
    $$update public.score_logs set format = 'triples' where id = '44444444-0000-0000-0000-000000000001'$$);
select pg_temp.expect_refused('three players on a side',
    $$update public.score_logs set home_players = '{A,B,C}' where id = '44444444-0000-0000-0000-000000000001'$$);
select pg_temp.expect_refused('a null inside the names',
    $$update public.score_logs set away_players = array['A', null] where id = '44444444-0000-0000-0000-000000000001'$$);
select pg_temp.expect_refused('no names column at all',
    $$update public.score_logs set home_players = null where id = '44444444-0000-0000-0000-000000000001'$$);

rollback;
