-- GCP load-test data, and a fingerprint of everything else. READ-ONLY.
-- Run by lt-gcp.ps1 (psql through the bastion) before seeding, after every reset and after cleanup; also fine in
-- Cloud SQL Studio. "tomorrow" is the reservation target date (business timezone Asia/Ho_Chi_Minh).
--
-- Load-test users are the usernames written by seed-gcp.js: gcplt-yyyyMMdd-HHmmss-nnnnn.
-- The "other ..." lines describe the demo, teammate and smoke-test data: a load test and its cleanup must leave them
-- exactly as they were (they only change if somebody uses the demo meanwhile).

WITH lt AS (
    SELECT id FROM users WHERE username ~ '^gcplt-[0-9]{8}-[0-9]{6}-[0-9]{5}$'
)
SELECT 'load-test users' AS item, count(*)::text AS value FROM lt
UNION ALL
SELECT 'load-test vehicles', count(*)::text FROM vehicles WHERE user_id IN (SELECT id FROM lt)
UNION ALL
SELECT 'load-test ledger rows', count(*)::text FROM wallet_transactions WHERE user_id IN (SELECT id FROM lt)
UNION ALL
SELECT 'load-test reservations', coalesce(
    (SELECT string_agg(reservation_date || '=' || n, ', ' ORDER BY reservation_date)
     FROM (SELECT reservation_date, count(*) AS n FROM reservations
           WHERE user_id IN (SELECT id FROM lt) GROUP BY reservation_date) d), 'none')
UNION ALL
SELECT 'all reservations for tomorrow (' || ((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 1) || ')', count(*)::text
FROM reservations WHERE reservation_date = (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 1
UNION ALL
SELECT 'other users', count(*)::text FROM users WHERE id NOT IN (SELECT id FROM lt)
UNION ALL
SELECT 'other vehicles', count(*)::text FROM vehicles WHERE user_id NOT IN (SELECT id FROM lt)
UNION ALL
SELECT 'other reservations', count(*)::text FROM reservations WHERE user_id NOT IN (SELECT id FROM lt)
UNION ALL
SELECT 'other ledger rows', count(*)::text FROM wallet_transactions WHERE user_id NOT IN (SELECT id FROM lt)
UNION ALL
SELECT 'other balances (cents)', coalesce(sum(balance_cents), 0)::text FROM wallets WHERE user_id NOT IN (SELECT id FROM lt);
