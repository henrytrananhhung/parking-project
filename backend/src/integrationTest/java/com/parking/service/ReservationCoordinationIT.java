package com.parking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.IntegrationTestSupport;
import com.parking.dto.response.SpaceStatusResponse;
import com.parking.exception.BusinessException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The reservation flow across Redis and PostgreSQL: FIFO handoff per space, pending reservations never counted as
 * reserved, waiters answered without a transaction, and the cache never changing an answer.
 */
class ReservationCoordinationIT extends IntegrationTestSupport {

    @Autowired
    ReservationService reservationService;
    @Autowired
    DataSource dataSource;
    @Autowired
    MeterRegistry meters;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void nextInLineGetsTheSpaceWhenTheHolderCannotPay() throws Exception {
        long spender = newUser("spender", BigDecimal.TEN);
        long spenderCar = newVehicle(spender, "SPEND001");
        long spenderOtherCar = newVehicle(spender, "SPEND002");
        long first = newUser("first", BigDecimal.TEN);
        long firstCar = newVehicle(first, "FIRST001");
        long second = newUser("second", BigDecimal.TEN);
        long secondCar = newVehicle(second, "SECOND01");
        double transactionsBefore = resolutions("transaction");

        List<Future<Object>> outcomes;
        try (Connection side = spendWalletUncommitted(spender)) {
            // The spender's claim on space 12 is inserted, then its debit waits for the wallet row that a concurrent
            // reservation of its other car (space 13) holds: a pending reservation.
            Future<Object> pending = submit(() -> reservationService.reserve(spender, spenderCar, 12));
            waitUntil(() -> debitsWaitingForARowLock() == 1);

            assertThat(dashboardSpace(12).reserved()).as("pending is not reserved").isFalse();
            Future<Object> firstInLine = submit(() -> reservationService.reserve(first, firstCar, 12));
            waitUntil(() -> lockQueueLength(12) == 1);
            Future<Object> secondInLine = submit(() -> reservationService.reserve(second, secondCar, 12));
            waitUntil(() -> lockQueueLength(12) == 2);
            outcomes = List.of(pending, firstInLine, secondInLine);
            // The other reservation commits: the spender's $10 is gone, so its debit for space 12 matches no row.
            commitReservation(side, spender, spenderOtherCar, 13);
        }

        assertThat(outcomes.get(0).get(30, TimeUnit.SECONDS)).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(outcomes.get(1).get(30, TimeUnit.SECONDS)).as("first in line wins").isNotInstanceOf(String.class);
        assertThat(outcomes.get(2).get(30, TimeUnit.SECONDS)).isEqualTo("SPACE_ALREADY_RESERVED");
        assertThat(balanceCents(spender)).isZero();
        assertThat(balanceCents(first)).isZero();
        assertThat(balanceCents(second)).isEqualTo(1000L);
        // The failed claim and the winner ran the transaction; the one behind them did not.
        assertThat(resolutions("transaction") - transactionsBefore).isEqualTo(2);
    }

    @Test
    void waitersBehindASuccessfulReservationAreRejectedWithoutATransaction() throws Exception {
        long holder = newUser("holder", BigDecimal.TEN);
        long holderCar = newVehicle(holder, "HOLDER01");
        List<long[]> waiters = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long userId = newUser("waiter" + i, BigDecimal.TEN);
            waiters.add(new long[] { userId, newVehicle(userId, "WAITER0" + i) });
        }
        double transactionsBefore = resolutions("transaction");

        List<Future<Object>> waiting = new ArrayList<>();
        Future<Object> holding;
        try (Connection side = holdWalletRow(holder)) {
            holding = submit(() -> reservationService.reserve(holder, holderCar, 30));
            waitUntil(() -> debitsWaitingForARowLock() == 1);
            for (long[] w : waiters) {
                waiting.add(submit(() -> reservationService.reserve(w[0], w[1], 30)));
                int queued = waiting.size();
                waitUntil(() -> lockQueueLength(30) == queued);
            }
            side.rollback();
        }

        assertThat(holding.get(30, TimeUnit.SECONDS)).isNotInstanceOf(String.class);
        for (Future<Object> w : waiting) {
            assertThat(w.get(30, TimeUnit.SECONDS)).isEqualTo("SPACE_ALREADY_RESERVED");
        }
        assertThat(resolutions("transaction") - transactionsBefore).as("only the holder's transaction").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'RESERVATION'")).isEqualTo(1);
    }

    @Test
    void reservationRefusesToRunInsideACallersTransaction() {
        // It waits for the space lock and the permit: inside a transaction it would hold a connection while waiting.
        long userId = newUser("nested", BigDecimal.TEN);
        long car = newVehicle(userId, "NESTED01");
        TransactionTemplate callersTransaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> callersTransaction.executeWithoutResult(
                status -> reservationService.reserve(userId, car, 40)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("SELECT count(*) FROM reservations")).isZero();
        assertThat(balanceCents(userId)).isEqualTo(1000L);
    }

    @Test
    void lostCacheWriteIsRepairedFromPostgreSql() throws Exception {
        long holder = newUser("holder", BigDecimal.TEN);
        long holderCar = newVehicle(holder, "HOLDER01");
        long rival = newUser("rival", BigDecimal.TEN);
        long rivalCar = newVehicle(rival, "RIVAL001");
        reservationService.reserve(holder, holderCar, 20);
        // As if the cache write after the commit had failed.
        reservations().fastRemove("s:20", "v:" + holderCar);

        assertThat(outcome(() -> reservationService.reserve(rival, rivalCar, 20))).isEqualTo("SPACE_ALREADY_RESERVED");

        // The conflict reloaded the date: the space is known again, so the next rival needs no transaction.
        assertThat(reservationCache.lookup(date(), 20, rivalCar).space()).isNotNull();
        assertThat(balanceCents(rival)).isEqualTo(1000L);
    }

    @Test
    void soldOutDateIsTrustedOnlyWhenItsSnapshotIsComplete() throws Exception {
        long owner = newUser("fleet", new BigDecimal("800"));
        List<Long> fleet = new ArrayList<>();
        for (int space = 1; space <= 80; space++) {
            fleet.add(newVehicle(owner, "FLEET%03d".formatted(space)));
            reservationService.reserve(owner, fleet.getLast(), space);
        }
        long late = newUser("late", BigDecimal.TEN);
        long lateCar = newVehicle(late, "LATE0001");
        assertThat(reservationCache.lookup(date(), 5, lateCar).dayFinal()).as("sold out").isTrue();

        // Another instance (nothing in memory) reading a hash that is marked final but misses one entry.
        reservationCache.clearLocalCaches();
        reservations().fastRemove("v:" + fleet.get(40));

        assertThat(reservationCache.lookup(date(), 5, lateCar).dayFinal()).as("incomplete: not trusted").isFalse();
        assertThat(outcome(() -> reservationService.reserve(late, lateCar, 5))).isEqualTo("SPACE_ALREADY_RESERVED");
        reservationCache.reload(date());
        assertThat(reservationCache.lookup(date(), 5, lateCar).dayFinal()).as("repaired by a reload").isTrue();
    }

    @Test
    void answersAreTheSameWithAnEmptyAWarmAndASoldOutCache() throws Exception {
        long one = newUser("one", new BigDecimal("900"));
        long oneCar = newVehicle(one, "ONE00001");
        reservationService.reserve(one, oneCar, 1);
        long other = newUser("other", new BigDecimal("50"));
        long otherCar = newVehicle(other, "OTHER001");
        List<Callable<?>> requests = List.of(
                () -> reservationService.reserve(one, oneCar, 1), // repeat of a success
                () -> reservationService.reserve(one, oneCar, 3), // vehicle already holds another space
                () -> reservationService.reserve(other, otherCar, 1), // space taken
                () -> reservationService.reserve(other, oneCar, 1), // someone else's vehicle, taken space
                () -> reservationService.reserve(other, oneCar, 3), // someone else's vehicle, free space
                () -> reservationService.reserve(one, oneCar, 999), // reserved vehicle, unknown space
                () -> reservationService.reserve(other, otherCar, 999), // unknown space
                () -> reservationService.reserve(other, 424_242L, 1)); // no such vehicle

        assertSameAnswersWithAndWithoutCache(requests);

        for (int space = 2; space <= 80; space++) {
            reservationService.reserve(one, newVehicle(one, "FLEET%03d".formatted(space)), space);
        }
        // The flushes above lost space 1's entry, so the cache alone cannot prove the date sold out (it stays
        // conservative) until a reload from PostgreSQL, which the dashboard triggers within the refresh interval.
        assertThat(reservationCache.lookup(date(), 1, otherCar).dayFinal()).as("not provable yet").isFalse();
        reservationCache.reload(date());
        assertThat(reservationCache.lookup(date(), 1, otherCar).dayFinal()).as("sold out").isTrue();
        assertSameAnswersWithAndWithoutCache(requests.subList(0, 5));
    }

    /** Each request: once with Redis and memory empty (database path), then twice with what that left cached. */
    private void assertSameAnswersWithAndWithoutCache(List<Callable<?>> requests) throws Exception {
        for (int i = 0; i < requests.size(); i++) {
            redisson.getKeys().flushdb();
            reservationCache.clearLocalCaches();
            dbPermits.initialize();
            Object withoutCache = outcome(requests.get(i));
            assertThat(outcome(requests.get(i))).as("request %d, cached", i).isEqualTo(withoutCache);
            assertThat(outcome(requests.get(i))).as("request %d, cached again", i).isEqualTo(withoutCache);
        }
    }

    private LocalDate date() {
        return businessToday().plusDays(1);
    }

    private org.redisson.api.RMap<String, String> reservations() {
        return redisson.getMap("parking:reservations:" + date(), StringCodec.INSTANCE);
    }

    private SpaceStatusResponse dashboardSpace(int spaceId) {
        return reservationService.dashboard(0L).spaces().get(spaceId - 1);
    }

    private double resolutions(String path) {
        return meters.counter("parking.reservation.resolution", "path", path).count();
    }

    /**
     * Starts a reservation charge of $10 on another connection (as the application's transaction would) and keeps it
     * uncommitted: the user's wallet row stays locked until {@link #commitReservation}.
     */
    private Connection spendWalletUncommitted(long userId) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE wallets SET balance_cents = balance_cents - 1000, version = version + 1"
                    + " WHERE user_id = " + userId);
        }
        return connection;
    }

    /** Completes the charge started by {@link #spendWalletUncommitted}: reservation + ledger row, then commit. */
    private void commitReservation(Connection connection, long userId, long vehicleId, int spaceId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    WITH r AS (
                        INSERT INTO reservations (space_id, vehicle_id, user_id, reservation_date, price_cents)
                        VALUES (%d, %d, %d, DATE '%s', 1000) RETURNING id)
                    INSERT INTO wallet_transactions (user_id, type, amount_cents, reservation_id)
                    SELECT %d, 'RESERVATION', -1000, id FROM r
                    """.formatted(spaceId, vehicleId, userId, date(), userId));
        }
        connection.commit();
    }

    /** Opens a transaction that locks the user's wallet row until the connection is rolled back. */
    private Connection holdWalletRow(long userId) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1 FROM wallets WHERE user_id = " + userId + " FOR UPDATE");
        }
        return connection;
    }

    private long debitsWaitingForARowLock() {
        return count("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query ILIKE 'update wallets%'
                """);
    }

    /** Requests queued in Redisson's fair lock of a space (its internal key naming). */
    private int lockQueueLength(int spaceId) {
        return redisson.getList("redisson_lock_queue:{parking:lock:" + date() + ":" + spaceId + "}",
                StringCodec.INSTANCE).size();
    }

    private Future<Object> submit(Callable<?> request) {
        return threads.submit(() -> outcome(request));
    }

    /** The response, or the business error code. */
    private static Object outcome(Callable<?> request) throws Exception {
        try {
            return request.call();
        } catch (BusinessException e) {
            return e.code().name();
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 15 s");
            }
            Thread.sleep(10);
        }
    }
}
