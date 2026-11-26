-- Clears ALL reservations on the GCP database (every user, every date), as if none had ever been made: each
-- reservation's charge is deleted and refunded, so every balance still equals its ledger. Users, vehicles, wallets
-- and deposits stay untouched.
--
-- Run it as parking_app (the owner of the tables): Cloud SQL Studio > parking-db > database parking > user parking_app.
-- Afterwards, outside SQL: delete the Redis reservation cache of the dates it returns (step 2, "reservation_dates"),
-- otherwise the dashboard keeps showing those spaces as taken.

-- 1. Before (read-only): reservations per date.
SELECT reservation_date, count(*) AS reservations
FROM reservations GROUP BY reservation_date ORDER BY reservation_date;

-- 2. Clear. One atomic statement: reservations, their charges and the refunds together, or nothing.
WITH res AS (
    SELECT id, reservation_date FROM reservations
),
charges AS (  -- the RESERVATION ledger rows (amount = -price)
    DELETE FROM wallet_transactions
    WHERE type = 'RESERVATION' AND reservation_id IN (SELECT id FROM res)
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
       (SELECT count(*) FROM charges)          AS charges_refunded,
       (SELECT count(*) FROM refunds)          AS wallets_refunded,
       (SELECT coalesce(string_agg(DISTINCT reservation_date::text, ','), '') FROM res) AS reservation_dates;

-- 3. After: no reservation left, and every balance still matches its ledger (both must be 0).
SELECT (SELECT count(*) FROM reservations) AS reservations_left,
       (SELECT count(*) FROM (SELECT w.user_id
                              FROM wallets w LEFT JOIN wallet_transactions t ON t.user_id = w.user_id
                              GROUP BY w.user_id, w.balance_cents
                              HAVING w.balance_cents <> COALESCE(SUM(t.amount_cents), 0)) v) AS balances_not_matching_ledger;
