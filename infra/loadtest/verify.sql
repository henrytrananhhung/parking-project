-- Money and booking audit after a load test (Cloud SQL Studio or psql). Read-only.
-- Same rules as the @AfterEach check in backend/src/integrationTest/.../IntegrationTestSupport.java.
-- Every violation count must be 0.

SELECT 'users whose balance differs from their ledger total' AS rule, count(*) AS violations
FROM (SELECT w.user_id
      FROM wallets w
      LEFT JOIN wallet_transactions t ON t.user_id = w.user_id
      GROUP BY w.user_id, w.balance_cents
      HAVING w.balance_cents <> COALESCE(SUM(t.amount_cents), 0)) v
UNION ALL
SELECT 'reservations not charged exactly once at their price', count(*)
FROM (SELECT r.id
      FROM reservations r
      LEFT JOIN wallet_transactions t ON t.reservation_id = r.id AND t.type = 'RESERVATION'
          AND t.user_id = r.user_id AND t.amount_cents = -r.price_cents
      GROUP BY r.id
      HAVING count(t.id) <> 1) v
UNION ALL
SELECT 'reservation charges without a reservation', count(*)
FROM wallet_transactions
WHERE type = 'RESERVATION' AND reservation_id IS NULL
UNION ALL
SELECT 'spaces booked twice on the same date', count(*)
FROM (SELECT 1 FROM reservations GROUP BY space_id, reservation_date HAVING count(*) > 1) v
UNION ALL
SELECT 'vehicles holding more than one space on the same date', count(*)
FROM (SELECT 1 FROM reservations GROUP BY vehicle_id, reservation_date HAVING count(*) > 1) v;
