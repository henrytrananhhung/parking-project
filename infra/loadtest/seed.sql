-- Load-test accounts for m3.js (disposable database only). Run on an EMPTY schema (after lt.sh reset):
--   psql -v funded=5000 -v broke=300 -v balance=10000 -f seed.sql
-- Users 1..funded: $<balance> (ledger-consistent deposit) and one vehicle each (vehicle id = user id).
-- Users funded+1..funded+broke: $0, one vehicle: their reservations fail with INSUFFICIENT_BALANCE.
-- Not login accounts (no password): m3.js signs their JWTs with the test JWT secret.

INSERT INTO users (id, username, password_cipher)
SELECT g, 'lt' || g, 'load-test-account-no-login' FROM generate_series(1, :funded + :broke) g;
SELECT setval(pg_get_serial_sequence('users', 'id'), :funded + :broke);

INSERT INTO wallets (user_id, balance_cents)
SELECT g, CASE WHEN g <= :funded THEN :balance ELSE 0 END FROM generate_series(1, :funded + :broke) g;

INSERT INTO vehicles (id, user_id, plate)
SELECT g, g, 'LT' || lpad(g::text, 7, '0') FROM generate_series(1, :funded + :broke) g;
SELECT setval(pg_get_serial_sequence('vehicles', 'id'), :funded + :broke);

INSERT INTO wallet_transactions (user_id, type, amount_cents, idempotency_key)
SELECT g, 'DEPOSIT', :balance, gen_random_uuid() FROM generate_series(1, :funded) g;

ANALYZE;
