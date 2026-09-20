-- An index for the contiguous-seat query, added because the plan at scale said so.
--
-- The SQL fallback for "N seats together" runs a window function partitioned by row_id and ordered
-- by seat_number over one event's available seats. With only seats_by_event_status to work with,
-- the planner has two bad choices: filter by event and then sort 40,000 rows, or walk
-- seats_row_id_seat_number_key in the order the window wants and filter out every other event's
-- seats. On the seeded dataset - 100,000 seats across 50,008 events - it chose the second and read
-- 32,241 buffers to answer one question, discarding 60,000 rows on the way.
--
-- This index gives it both at once: restricted to one event, already in (row_id, seat_number)
-- order, and partial so it holds only the seats that are still available. Same query, same data:
-- 2,306 buffers and 11.6 ms instead of 32,241 and 26.8 ms. The plans for both are committed under
-- load/results/*/query-plans/.
--
-- The cost is one more index to maintain on every seat status change. It is a partial index over
-- AVAILABLE seats, so it shrinks as an event sells - it is largest exactly when the contiguous
-- search is most useful, and smallest when the sale is nearly over.
--
-- CONCURRENTLY, and therefore outside a transaction. A plain CREATE INDEX takes ACCESS EXCLUSIVE
-- on seats for the length of the build, which is precisely the fault injected in the game day on
-- 2026-09-20: it blocked every reservation transaction, filled the connection pool, and the edge
-- ejected all three replicas. A migration that causes that outage on the way to fixing a query is
-- not a fix. Flyway 11 runs this outside a transaction on request.
-- flyway:executeInTransaction=false

create index concurrently if not exists seats_available_by_row
  on seats (event_id, row_id, seat_number)
  where status = 'AVAILABLE';

-- seats_by_row_position duplicates the unique constraint seats_row_id_seat_number_key exactly:
-- same columns, same order, same table. Two identical btrees cost two index writes on every seat
-- insert and every status change and answer the same questions. Noticed while reading the plans
-- above, which name seats_row_id_seat_number_key and never the other one.
drop index if exists seats_by_row_position;
