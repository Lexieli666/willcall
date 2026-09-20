-- Holds, orders, idempotency and the outbox: everything whose loss would be a correctness
-- bug. The partial unique index on holds is the last line of defence for the central
-- invariant, and it is the one that survives an application bug.

create table hold_groups (
    id          uuid primary key,
    event_id    uuid        not null references events (id) on delete cascade,
    user_ref    text        not null,
    status      text        not null
        check (status in ('ACTIVE', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    expires_at  timestamptz not null,
    seat_count  integer     not null check (seat_count >= 1),
    created_at  timestamptz not null default now(),
    resolved_at timestamptz
);

create index hold_groups_by_user on hold_groups (event_id, user_ref, status);

-- One row per held seat. A multi-seat request produces one hold_group and N holds, which is
-- what makes "at most one active allocation per seat" expressible as a single index.
create table holds (
    id            uuid primary key     default gen_random_uuid(),
    hold_group_id uuid        not null references hold_groups (id) on delete cascade,
    event_id      uuid        not null references events (id) on delete cascade,
    seat_id       uuid        not null references seats (id) on delete cascade,
    user_ref      text        not null,
    status        text        not null
        check (status in ('ACTIVE', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    expires_at    timestamptz not null,
    created_at    timestamptz not null default now(),
    resolved_at   timestamptz
);

-- The invariant, enforced by the database. If the application ever tries to hand the same
-- seat to two buyers, this index raises a unique violation and the second transaction rolls
-- back. Everything else about the reservation path is an optimisation on top of this line.
create unique index holds_single_active_per_seat on holds (seat_id) where status = 'ACTIVE';

-- Drives the sweeper. Partial, because only ACTIVE holds can expire and the table keeps
-- resolved rows forever as an audit trail.
create index holds_active_by_expiry on holds (expires_at) where status = 'ACTIVE';

create index holds_by_group on holds (hold_group_id);
create index holds_by_user on holds (event_id, user_ref, status);

create table orders (
    id                uuid primary key,
    event_id          uuid        not null references events (id) on delete cascade,
    hold_group_id     uuid references hold_groups (id),
    user_ref          text        not null,
    status            text        not null
        check (status in ('PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    total_cents       integer     not null check (total_cents >= 0),
    currency          char(3)     not null default 'USD',
    payment_reference text,
    failure_code      text,
    created_at        timestamptz not null default now(),
    confirmed_at      timestamptz
);

create index orders_by_user on orders (event_id, user_ref, status);

-- Order lines are written only when an order is confirmed. That is what lets a plain unique
-- index say "a seat is sold at most once": a failed payment leaves an order row for the
-- audit trail but no line, so the seat is free for the next buyer with no cleanup step.
create table order_lines (
    id          uuid primary key default gen_random_uuid(),
    order_id    uuid    not null references orders (id) on delete cascade,
    seat_id     uuid    not null references seats (id),
    price_cents integer not null check (price_cents >= 0)
);

create unique index order_lines_one_per_seat on order_lines (seat_id);
create index order_lines_by_order on order_lines (order_id);

-- Idempotency. The fingerprint is what turns "same key" into "same request": a client that
-- reuses a key for a different body gets 422 rather than somebody else's answer.
create table idempotency_records (
    user_ref            text        not null,
    endpoint            text        not null,
    idempotency_key     text        not null,
    request_fingerprint text        not null,
    status              text        not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    response_status     integer,
    response_body       jsonb,
    resource_id         uuid,
    created_at          timestamptz not null default now(),
    completed_at        timestamptz,
    expires_at          timestamptz not null,
    primary key (user_ref, endpoint, idempotency_key)
);

create index idempotency_by_expiry on idempotency_records (expires_at);

-- Transactional outbox. A seat change and the announcement of that change commit together,
-- so a delta can never describe a state the database does not hold.
create table outbox (
    id             bigserial primary key,
    event_id       uuid        not null references events (id) on delete cascade,
    aggregate_type text        not null,
    aggregate_id   uuid        not null,
    type           text        not null,
    payload        jsonb       not null,
    created_at     timestamptz not null default now(),

    -- Assigned by the relay, not by the writer: a per-event counter written on the hot path
    -- would serialise every hold for an event onto one row, which is the opposite of what a
    -- flash sale needs.
    sequence_no    bigint,
    published_at   timestamptz
);

create index outbox_unpublished on outbox (event_id, id) where published_at is null;
create index outbox_by_sequence on outbox (event_id, sequence_no) where sequence_no is not null;
