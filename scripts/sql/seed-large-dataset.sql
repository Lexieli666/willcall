-- Seed a large, realistic-shaped dataset.
--
-- Everything is generated inside PostgreSQL. Sending a million rows over the wire from a script
-- would take longer than the measurement it enables, and would measure the script.
--
-- Shape matters more than volume. Orders are skewed: a few events account for most of them, which
-- is what a real catalogue looks like and what makes the planner's choice between an index scan
-- and a sequential scan interesting. A uniform distribution would make every plan look fine.

\set ON_ERROR_STOP on
\timing on

begin;

-- A table for the seeded users. The application has no user table of its own — buyers are opaque
-- references — so this exists purely to give the query-plan work something to join against at the
-- size the plan is being checked at.
create table if not exists seed_users (
    id         bigserial primary key,
    user_ref   text        not null unique,
    created_at timestamptz not null default now()
);

create table if not exists seed_orders (
    id           bigserial primary key,
    user_ref     text        not null,
    event_id     uuid        not null,
    total_cents  integer     not null,
    status       text        not null,
    created_at   timestamptz not null
);

truncate seed_users, seed_orders restart identity;

commit;

-- ---------------------------------------------------------------- users

insert into seed_users (user_ref, created_at)
select 'buyer-' || lpad(n::text, 9, '0'),
       now() - (random() * interval '730 days')
from generate_series(1, :users) as n;

-- ---------------------------------------------------------------- events

-- One venue for the seeded events: the venue is not what any hot query filters on.
insert into venues (id, name, timezone)
values ('00000000-0000-0000-0000-0000000000ff', 'Seeded Venue', 'UTC')
on conflict (id) do nothing;

insert into events (
    id, venue_id, name, starts_at, sales_open_at, capacity,
    hold_ttl_seconds, max_seats_per_order, status, last_sequence
)
select gen_random_uuid(),
       '00000000-0000-0000-0000-0000000000ff',
       'Seeded event ' || n,
       now() + (n % 365) * interval '1 day',
       now() - (n % 30) * interval '1 day',
       0,
       120,
       8,
       case when n % 7 = 0 then 'CLOSED' when n % 5 = 0 then 'DRAFT' else 'ON_SALE' end,
       0
from generate_series(1, :events) as n;

-- ---------------------------------------------------------------- historical orders

-- Skewed on purpose: the top 1% of events take roughly half the orders, as a real catalogue does.
-- A uniform spread would make every index look equally useful and hide the plan that matters.
insert into seed_orders (user_ref, event_id, total_cents, status, created_at)
select
    'buyer-' || lpad(((random() * (:users - 1))::bigint + 1)::text, 9, '0'),
    (
      select id from events
      offset floor(
        case when random() < 0.5
             then random() * greatest(1, (:events / 100))
             else random() * :events
        end
      )::bigint
      limit 1
    ),
    (1000 + (random() * 20000))::integer,
    case when random() < 0.93 then 'CONFIRMED' when random() < 0.97 then 'CANCELLED' else 'FAILED' end,
    now() - (random() * interval '730 days')
from generate_series(1, :orders) as n;

-- ---------------------------------------------------------------- indexes

-- Added after the bulk insert: building an index while inserting a million rows is slower than
-- building it once at the end, and the plans are what this is for.
create index if not exists seed_orders_by_user on seed_orders (user_ref, created_at desc);
create index if not exists seed_orders_by_event on seed_orders (event_id, created_at desc);
create index if not exists seed_orders_by_status on seed_orders (status) where status = 'CONFIRMED';

-- Statistics the planner can actually use. A plan collected against stale statistics is a plan for
-- a database that does not exist.
analyze seed_users;
analyze seed_orders;
analyze events;
analyze seats;
analyze holds;
analyze orders;
analyze order_lines;

select 'seeded users' as what, count(*) from seed_users
union all
select 'seeded events', count(*) from events
union all
select 'seeded orders', count(*) from seed_orders;
