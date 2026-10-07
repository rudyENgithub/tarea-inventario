-- V1: inventory schema (products, reservations, alert outbox).
-- Already-applied migrations must never be edited: any change goes into a new V2__*.sql file.
--
-- Business time (expires_at, next_attempt_at, ...) always comes from the application Clock, never from now().
-- created_at / updated_at are informational and are never read by the domain.

CREATE TABLE products (
    sku         varchar(64)  PRIMARY KEY,
    category    varchar(32)  NOT NULL,
    on_hand     integer      NOT NULL CHECK (on_hand >= 0),
    alert_armed boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    order_id      varchar(64) PRIMARY KEY,
    sku           varchar(64) NOT NULL REFERENCES products (sku),
    quantity      integer     NOT NULL CHECK (quantity > 0),
    status        varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED')),
    expires_at    timestamptz NOT NULL,
    confirmed_at  timestamptz NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_reservations_active ON reservations (sku, expires_at) WHERE status = 'ACTIVE';

CREATE TABLE alert_outbox (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku             varchar(64) NOT NULL,
    available_units integer     NOT NULL CHECK (available_units >= 0),
    occurred_at     timestamptz NOT NULL,
    status          varchar(16) NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DELIVERED', 'FAILED')),
    attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    lease_until     timestamptz NULL,
    last_error      text        NULL,
    delivered_at    timestamptz NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_outbox_pending ON alert_outbox (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_in_progress ON alert_outbox (lease_until, id) WHERE status = 'IN_PROGRESS';
