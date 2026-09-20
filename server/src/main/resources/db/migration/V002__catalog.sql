-- Venues, events and the seat hierarchy.
--
-- Naming note: the table holding a row of seats is `seat_rows`, not `rows`. `rows` is a
-- keyword in enough SQL dialects and tools that unquoted use is a trap, and quoting it
-- everywhere is worse than picking a different name.

create table venues (
    id         uuid primary key     default gen_random_uuid(),
    name       text        not null,
    timezone   text        not null default 'UTC',
    created_at timestamptz not null default now()
);

create table events (
    id                  uuid primary key     default gen_random_uuid(),
    venue_id            uuid        not null references venues (id),
    name                text        not null,
    starts_at           timestamptz not null,
    sales_open_at       timestamptz not null,

    -- Capacity is the number of sellable seats. A constraint trigger is deliberately not used
    -- to keep it in step with the seats table: the seat generator sets it once at creation,
    -- and scripts/verify-invariants.sh asserts the two agree. An assertion that can fail is
    -- worth more here than a constraint that makes the failure impossible to observe.
    capacity            integer     not null check (capacity >= 0),

    -- The hold TTL is per event because the right value depends on the checkout the buyer
    -- faces. The default is a starting point, not a finding; see the hold-TTL ADR.
    hold_ttl_seconds    integer     not null default 120
        check (hold_ttl_seconds between 15 and 3600),

    max_seats_per_order integer     not null default 8
        check (max_seats_per_order between 1 and 50),

    status              text        not null default 'DRAFT'
        check (status in ('DRAFT', 'ON_SALE', 'PAUSED', 'CLOSED')),

    -- Monotonic per-event sequence for the real-time protocol. Only the outbox relay writes
    -- it, under an advisory lock, so the hot reservation path never contends on this row.
    last_sequence       bigint      not null default 0,

    created_at          timestamptz not null default now()
);

create index events_by_status on events (status, sales_open_at);

create table price_tiers (
    id           uuid primary key     default gen_random_uuid(),
    event_id     uuid        not null references events (id) on delete cascade,
    name         text        not null,
    amount_cents integer     not null check (amount_cents >= 0),
    currency     char(3)     not null default 'USD',
    created_at   timestamptz not null default now(),
    unique (event_id, name)
);

create table sections (
    id            uuid primary key  default gen_random_uuid(),
    event_id      uuid     not null references events (id) on delete cascade,
    name          text     not null,
    display_order integer  not null,
    unique (event_id, name)
);

create table seat_rows (
    id            uuid primary key default gen_random_uuid(),
    section_id    uuid    not null references sections (id) on delete cascade,
    event_id      uuid    not null references events (id) on delete cascade,
    label         text    not null,
    display_order integer not null,
    seat_count    integer not null check (seat_count >= 0),
    unique (section_id, label)
);

create index seat_rows_by_event on seat_rows (event_id, display_order);

create table seats (
    id            uuid primary key     default gen_random_uuid(),
    event_id      uuid        not null references events (id) on delete cascade,
    row_id        uuid        not null references seat_rows (id) on delete cascade,
    price_tier_id uuid references price_tiers (id),

    -- Position within the row, 1-based and contiguous. The contiguous-seat allocator indexes
    -- directly by this, so a gap here is a correctness bug rather than a cosmetic one.
    seat_number   integer     not null check (seat_number >= 1),
    label         text        not null,

    -- The seat's status is the single source of truth for whether it can be acquired, and it
    -- is only ever written while the row is locked. RELEASED is not a status: a released seat
    -- is indistinguishable from an available one to a buyer, so it is a hold outcome instead.
    status        text        not null default 'AVAILABLE'
        check (status in ('AVAILABLE', 'HELD', 'SOLD', 'BLOCKED')),

    -- Incremented on every status change. The real-time protocol carries it so a client can
    -- tell a stale delta from a fresh one without trusting arrival order.
    version       bigint      not null default 0,

    updated_at    timestamptz not null default now(),

    unique (row_id, seat_number)
);

-- The seat map query and the "best available" scan both filter on (event_id, status); the
-- row/number ordering is what the contiguous allocator walks.
create index seats_by_event_status on seats (event_id, status);
create index seats_by_row_position on seats (row_id, seat_number);
