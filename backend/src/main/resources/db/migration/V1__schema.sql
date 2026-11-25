-- Phase 1 schema. PostgreSQL is the source of truth for reservation and balance correctness.

CREATE TABLE users (
    id              BIGSERIAL    PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL,
    -- Cloud KMS ciphertext (base64) of the BCrypt hash. Never a plain hash or password.
    password_cipher TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE vehicles (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    -- Normalized plate (upper case, no spaces/dashes).
    plate      VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_vehicles_plate UNIQUE (plate)
);
CREATE INDEX idx_vehicles_user_id ON vehicles (user_id);

CREATE TABLE wallets (
    user_id       BIGINT      PRIMARY KEY REFERENCES users (id),
    balance_cents BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wallets_balance_non_negative CHECK (balance_cents >= 0)
);

CREATE TABLE parking_spaces (
    id INT PRIMARY KEY
);

CREATE TABLE reservations (
    id               BIGSERIAL   PRIMARY KEY,
    space_id         INT         NOT NULL REFERENCES parking_spaces (id),
    vehicle_id       BIGINT      NOT NULL REFERENCES vehicles (id),
    user_id          BIGINT      NOT NULL REFERENCES users (id),
    reservation_date DATE        NOT NULL,
    price_cents      BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One vehicle per space per day, and one space per vehicle per day.
    CONSTRAINT uk_reservations_space_date   UNIQUE (space_id, reservation_date),
    CONSTRAINT uk_reservations_vehicle_date UNIQUE (vehicle_id, reservation_date)
);
CREATE INDEX idx_reservations_user_id ON reservations (user_id);

-- Append-only money ledger: +amount for deposits, -amount for reservations.
CREATE TABLE wallet_transactions (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id),
    type           VARCHAR(20) NOT NULL,
    amount_cents   BIGINT      NOT NULL,
    reservation_id BIGINT      REFERENCES reservations (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wallet_transactions_type CHECK (type IN ('DEPOSIT', 'RESERVATION'))
);
CREATE INDEX idx_wallet_transactions_user_id ON wallet_transactions (user_id);
