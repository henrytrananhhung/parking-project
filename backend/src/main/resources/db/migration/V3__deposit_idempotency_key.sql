-- Deposits become idempotent: the client sends an Idempotency-Key (UUID) per deposit, and a retried deposit
-- with the same key is applied once. Reservation charges keep NULL (they are idempotent by their own natural key).
ALTER TABLE wallet_transactions ADD COLUMN idempotency_key UUID;

-- NULLs are distinct in PostgreSQL, so reservation charges never conflict with each other.
ALTER TABLE wallet_transactions
    ADD CONSTRAINT uk_wallet_transactions_user_idempotency_key UNIQUE (user_id, idempotency_key);

-- Every new deposit must carry a key. NOT VALID: deposits made before this migration have none and are left as is.
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_deposit_has_key
    CHECK (type <> 'DEPOSIT' OR idempotency_key IS NOT NULL) NOT VALID;
