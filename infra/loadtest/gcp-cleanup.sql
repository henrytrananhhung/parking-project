-- Removes ALL GCP load-test data, and nothing else: end of a test session. Run by lt-gcp.ps1 cleanup (psql through the
-- bastion), which then also clears the Redis cache of the returned dates and replaces the Cloud Run instances
-- (README.md, section 4). Between benchmarks, gcp-reset.sql is enough.
--
-- Safety: only users whose username matches the strict seed-gcp.js pattern gcplt-yyyyMMdd-HHmmss-nnnnn are removed,
-- with their own vehicles, wallets, ledger rows and reservations. Demo (demo1..3), teammate and smoke-test accounts can
-- never match. A single atomic statement: either everything below is deleted, or nothing is.

WITH targets AS (
    SELECT id FROM users WHERE username ~ '^gcplt-[0-9]{8}-[0-9]{6}-[0-9]{5}$'
),
dates AS (  -- read before the deletes (same snapshot): the Redis cache keys to clear afterwards
    SELECT DISTINCT reservation_date FROM reservations WHERE user_id IN (SELECT id FROM targets)
),
del_ledger AS (
    DELETE FROM wallet_transactions WHERE user_id IN (SELECT id FROM targets) RETURNING 1
),
del_reservations AS (
    DELETE FROM reservations WHERE user_id IN (SELECT id FROM targets) RETURNING 1
),
del_vehicles AS (
    DELETE FROM vehicles WHERE user_id IN (SELECT id FROM targets) RETURNING 1
),
del_wallets AS (
    DELETE FROM wallets WHERE user_id IN (SELECT id FROM targets) RETURNING 1
),
del_users AS (
    DELETE FROM users WHERE id IN (SELECT id FROM targets) RETURNING 1
)
SELECT (SELECT count(*) FROM del_users)        AS users_removed,
       (SELECT count(*) FROM del_reservations) AS reservations_removed,
       (SELECT count(*) FROM del_vehicles)     AS vehicles_removed,
       (SELECT count(*) FROM del_wallets)      AS wallets_removed,
       (SELECT count(*) FROM del_ledger)       AS ledger_rows_removed,
       (SELECT coalesce(string_agg(reservation_date::text, ',' ORDER BY reservation_date), '') FROM dates)
                                               AS reservation_dates;
