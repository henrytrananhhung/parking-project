-- Removes smoke-test data for EXPLICITLY listed test accounts only.
--
-- How to use (Cloud SQL Studio -> database "parking", or psql locally):
--   1. Paste the usernames from infra/smoke-accounts.log into the VALUES list below.
--   2. Run the statement. It is a single atomic statement and prints how many users were removed.
--
-- Safety: a row is deleted only if its username is in the list AND matches the strict smoke-test
-- pattern smoke-yyyyMMdd-HHmmss-xxxx. Demo accounts and teammate accounts can never match.

WITH requested(username) AS (
    VALUES
        ('smoke-20260101-000000-abcd')   -- <- replace / add one line per username
),
targets AS (
    SELECT u.id
    FROM users u
    JOIN requested r ON r.username = u.username
    WHERE u.username ~ '^smoke-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$'
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
       (SELECT count(*) FROM del_ledger)       AS ledger_rows_removed;
