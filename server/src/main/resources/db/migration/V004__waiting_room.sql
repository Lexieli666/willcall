-- The waiting room is per event, because most events do not need one and a queue in front of an
-- event that never fills is pure friction.
alter table events
    add column waiting_room_enabled boolean not null default false;

-- The measured admission rate for this event, in admissions per second.
--
-- It is stored rather than configured globally because it is a *measurement* of what the
-- reservation core survives for this event's shape, and that differs: an event whose buyers ask
-- for four adjacent seats costs more per admission than one selling singles. Null means "use the
-- service-wide default", which is what a brand-new event has before anything has been measured.
alter table events
    add column admission_rate_per_second numeric(8, 2);

-- A record of who was admitted and when, kept in PostgreSQL as well as Redis.
--
-- Redis is the live queue and is allowed to disappear; this table is the audit trail that
-- survives it, and the evidence behind the published FIFO-inversion rate. Without it, a claim
-- about admission order would rest on data that is deleted as it is used.
create table admissions (
    id            bigserial primary key,
    event_id      uuid        not null references events (id) on delete cascade,
    user_ref      text        not null,
    joined_at     timestamptz not null,
    admitted_at   timestamptz not null default now(),
    position_on_join integer,
    unique (event_id, user_ref)
);

create index admissions_by_event_time on admissions (event_id, admitted_at);
create index admissions_by_join_order on admissions (event_id, joined_at);
