-- Plans for the statements the reservation path actually runs, at scale.
--
-- BUFFERS as well as ANALYZE, because "fast" on a warm cache and "fast" on a cold one are
-- different claims and the buffer counts are what distinguish them.
--
-- Each heading carries [hot path] or [analytical]. A hot-path query runs per request or on a
-- timer and must not sequentially scan; an analytical one deliberately reads the whole table
-- (the invariant check is over every event by definition) and a sequential scan there is the
-- correct plan. The summary builder holds only the hot-path queries to the no-seq-scan rule,
-- because a rule that flags a correct plan gets switched off.

\set ON_ERROR_STOP on
\pset pager off

\echo '================================================================'
\echo 'Table sizes these plans were taken against'
\echo '================================================================'
select relname, n_live_tup, pg_size_pretty(pg_total_relation_size(relid)) as size
from pg_stat_user_tables
where n_live_tup > 0
order by n_live_tup desc;

\echo ''
\echo '================================================================'
\echo '1. Claim any available seat  (the hot path of a flash sale)  [hot path]'
\echo '================================================================'
\echo 'Must be an index scan on seats_by_event_status. A sequential scan here is the difference'
\echo 'between a sale and an outage: it runs once per acquisition attempt, ten thousand times in'
\echo 'ten seconds.'
explain (analyze, buffers, costs off)
select id, event_id, row_id, price_tier_id, seat_number, label, status, version, updated_at
from seats
where event_id = (select id from events where status = 'ON_SALE' limit 1)
  and status = 'AVAILABLE'
order by id
limit 4
for update skip locked;

\echo ''
\echo '================================================================'
\echo '2. Find N contiguous free seats  (the SQL fallback for the segment tree)  [hot path]'
\echo '================================================================'
\echo 'The window function is unavoidable; what matters is that the scan it feeds on is restricted'
\echo 'by the partial predicate rather than reading every seat in the database.'
explain (analyze, buffers, costs off)
with numbered as (
  select id, row_id, seat_number,
         seat_number - row_number() over (partition by row_id order by seat_number) as run_key
  from seats
  where event_id = (select id from events where status = 'ON_SALE' limit 1)
    and status = 'AVAILABLE'
),
runs as (
  select row_id, run_key, min(seat_number) as start_number, count(*) as run_length
  from numbered
  group by row_id, run_key
  having count(*) >= 3
)
select * from runs order by run_length, row_id, start_number limit 1;

\echo ''
\echo '================================================================'
\echo '3. The sweeper claiming expired holds  [hot path]'
\echo '================================================================'
\echo 'Must use the partial index holds_active_by_expiry. This runs four times a second on every'
\echo 'replica; a sequential scan over the holds table at that rate would saturate the database on'
\echo 'its own.'
explain (analyze, buffers, costs off)
select id, hold_group_id, event_id, seat_id, user_ref, status, expires_at, created_at, resolved_at
from holds
where status = 'ACTIVE' and expires_at <= now()
order by seat_id
limit 500
for update skip locked;

\echo ''
\echo '================================================================'
\echo '4. Idempotency lookup  [hot path]'
\echo '================================================================'
\echo 'A primary-key lookup. It is here because it runs on every mutating request, so a plan'
\echo 'regression would be felt everywhere at once.'
explain (analyze, buffers, costs off)
select user_ref, endpoint, idempotency_key, request_fingerprint, status
from idempotency_records
where user_ref = 'buyer-000000001' and endpoint = 'POST /api/orders' and idempotency_key = 'x';

\echo ''
\echo '================================================================'
\echo '5. A buyer''s order history  (the skewed join)  [hot path]'
\echo '================================================================'
\echo 'One buyer against a million orders. This is where the skew matters: the planner has to'
\echo 'choose between the user index and the event index, and the right answer depends on'
\echo 'statistics that only exist at this size.'
explain (analyze, buffers, costs off)
select o.id, o.event_id, o.total_cents, o.status, o.created_at
from seed_orders o
where o.user_ref = 'buyer-000000001'
order by o.created_at desc
limit 20;

\echo ''
\echo '================================================================'
\echo '6. Orders for a popular event  (the other side of the skew)  [analytical]'
\echo '================================================================'
explain (analyze, buffers, costs off)
select count(*), sum(o.total_cents)
from seed_orders o
where o.event_id = (select event_id from seed_orders group by event_id order by count(*) desc limit 1)
  and o.status = 'CONFIRMED';

\echo ''
\echo '================================================================'
\echo '7. Outbox relay: unpublished entries for an event  [hot path]'
\echo '================================================================'
\echo 'Runs forty times a second per event. The partial index outbox_unpublished is what keeps it'
\echo 'from reading published history that will never be selected again.'
explain (analyze, buffers, costs off)
select id, event_id, type, payload::text, sequence_no, created_at
from outbox
where event_id = (select id from events where status = 'ON_SALE' limit 1)
  and published_at is null
order by id
limit 500;

\echo ''
\echo '================================================================'
\echo '8. The capacity invariant check  [analytical]'
\echo '================================================================'
\echo 'Runs in CI and after every load test, over every event in the database.'
explain (analyze, buffers, costs off)
select e.id, e.capacity, counts.confirmed, counts.held
from events e
join lateral (
  select count(*) filter (where s.status = 'SOLD') as confirmed,
         count(*) filter (where s.status = 'HELD') as held
  from seats s where s.event_id = e.id
) counts on true
where counts.confirmed + counts.held > e.capacity;
