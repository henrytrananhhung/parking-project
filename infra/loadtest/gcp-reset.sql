-- Before every GCP benchmark (lt-gcp.ps1 reset): removes the reservations of the load-test users, deletes the charge of
-- each one and refunds it, so the next opening race starts with the lot empty again and every balance still equals its
-- ledger (verify.sql). The load-test users, vehicles, deposits and tokens stay: no re-seeding between benchmarks.
--
-- Safety: only rows of users matching the strict seed-gcp.js pattern gcplt-yyyyMMdd-HHmmss-nnnnn are selected; demo,
-- teammate and smoke-test rows can never match. A single atomic statement. lt-gcp.ps1 runs it only when no load test
-- request can still be in flight (no k6 running, 60 s - the Cloud Run request timeout - after the last one ended).

WITH lt AS (
    SELECT id FROM users WHERE username ~ '^gcplt-[0-9]{8}-[0-9]{6}-[0-9]{5}$'
),
res AS (
    SELECT id, reservation_date FROM reservations WHERE user_id IN (SELECT id FROM lt)
),
charges AS (  -- the RESERVATION ledger rows of those reservations (amount = -price)
    DELETE FROM wallet_transactions
    WHERE type = 'RESERVATION' AND reservation_id IN (SELECT id FROM res) AND user_id IN (SELECT id FROM lt)
    RETURNING user_id, amount_cents
),
refunds AS (  -- same effect as the application's balance updates: amount and version
    UPDATE wallets w
    SET balance_cents = w.balance_cents - c.total, version = w.version + 1
    FROM (SELECT user_id, sum(amount_cents) AS total FROM charges GROUP BY user_id) c
    WHERE w.user_id = c.user_id
    RETURNING w.user_id
),
del_reservations AS (
    DELETE FROM reservations WHERE id IN (SELECT id FROM res) RETURNING 1
)
SELECT (SELECT count(*) FROM del_reservations) AS reservations_removed,
       (SELECT count(*) FROM charges)          AS charges_removed,
       (SELECT count(*) FROM refunds)          AS wallets_refunded,
       (SELECT coalesce(string_agg(DISTINCT reservation_date::text, ','), '') FROM res) AS reservation_dates;
