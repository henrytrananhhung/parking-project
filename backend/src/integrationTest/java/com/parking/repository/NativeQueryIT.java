package com.parking.repository;

import static com.parking.entity.WalletTransactionEntity.Type.DEPOSIT;
import static com.parking.entity.WalletTransactionEntity.Type.RESERVATION;
import static java.math.BigDecimal.ZERO;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.IntegrationTestSupport;
import com.parking.entity.ReservationEntity;
import com.parking.exception.Constraints;

/**
 * The native statements kept in the repositories (INSERT ... ON CONFLICT and the advisory lock), run through
 * Spring Data JPA and Hibernate against real PostgreSQL. Every write happens in a transaction that is rolled back,
 * so these bare rows (no ledger entry) never reach the money audit.
 */
class NativeQueryIT extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2030, 1, 1);

    @Autowired
    ReservationRepository reservations;
    @Autowired
    WalletTransactionRepository ledger;
    @Autowired
    WalletRepository wallets;
    @Autowired
    UserRepository users;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void insertIfFreeReturnsTheNewIdThenEmptyWhenTheSpaceOrTheVehicleIsTaken() {
        long alice = newUser("alice", ZERO);
        long aliceCar = newVehicle(alice, "ALICE01");
        long aliceVan = newVehicle(alice, "ALICE02");
        long bob = newUser("bob", ZERO);
        long bobCar = newVehicle(bob, "BOB0001");

        inRolledBackTransaction(() -> {
            Optional<Long> first = reservations.insertIfFree(1, aliceCar, alice, DATE, 1000);
            assertThat(first).isPresent();
            assertThat(reservations.insertIfFree(1, bobCar, bob, DATE, 1000)).as("space taken").isEmpty();
            assertThat(reservations.insertIfFree(2, aliceCar, alice, DATE, 1000)).as("vehicle taken").isEmpty();
            // A conflict does not abort the transaction: it keeps working, which the replay lookup relies on.
            assertThat(reservations.insertIfFree(2, aliceVan, alice, DATE, 1000)).isPresent();
            assertThat(reservations.findByVehicleIdAndReservationDate(aliceCar, DATE))
                    .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(first.get()));
            return null;
        });
        assertThat(count("SELECT count(*) FROM reservations")).isZero();
    }

    @Test
    void unknownSpaceFailsTheForeignKeyAndIsRecognisedByName() {
        long alice = newUser("alice", ZERO);
        long car = newVehicle(alice, "ALICE01");

        assertThatThrownBy(() -> inRolledBackTransaction(() -> reservations.insertIfFree(999, car, alice, DATE, 1000)))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        e -> assertThat(Constraints.violated(e, ReservationEntity.FK_SPACE)).isTrue());
    }

    @Test
    void aSecondClaimWaitsForAnUncommittedFirstOneAndGetsTheSpaceWhenItRollsBack() throws Exception {
        long alice = newUser("alice", ZERO);
        long aliceCar = newVehicle(alice, "ALICE01");
        long bob = newUser("bob", ZERO);
        long bobCar = newVehicle(bob, "BOB0001");

        List<Optional<Long>> claims = secondWaitsForFirst(
                () -> reservations.insertIfFree(3, aliceCar, alice, DATE, 1000),
                () -> reservations.insertIfFree(3, bobCar, bob, DATE, 1000));

        assertThat(claims.get(0)).isPresent();
        assertThat(claims.get(1)).as("free again after the first transaction rolled back").isPresent();
    }

    @Test
    void theAdvisoryLockMakesASecondTransactionWaitUntilTheFirstEnds() throws Exception {
        List<Boolean> locks = secondWaitsForFirst(
                () -> users.acquireTransactionLock(424_242L),
                () -> users.acquireTransactionLock(424_242L));

        assertThat(locks).containsExactly(true, true);
    }

    @Test
    void insertDepositIfNewRecordsAKeyOnlyOnce() {
        long userId = newUser("saver", ZERO);
        UUID key = UUID.randomUUID();

        inRolledBackTransaction(() -> {
            assertThat(ledger.insertDepositIfNew(userId, 1500, key)).isEqualTo(1);
            assertThat(ledger.insertDepositIfNew(userId, 1500, key)).as("same key again").isZero();
            assertThat(ledger.insertDepositIfNew(userId, 2000, key)).as("same key, other amount").isZero();
            assertThat(ledger.findAmountCents(userId, key, DEPOSIT)).contains(1500L);
            assertThat(ledger.findAmountCents(userId, key, RESERVATION)).isEmpty();
            return null;
        });
    }

    @Test
    void writesRefuseToRunOutsideATransaction() {
        long userId = newUser("loner", ZERO);
        long car = newVehicle(userId, "LONER01");

        assertThatThrownBy(() -> reservations.insertIfFree(1, car, userId, DATE, 1000))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> ledger.insertDepositIfNew(userId, 1000, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> wallets.credit(userId, 1000)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> wallets.debitIfSufficient(userId, 1000))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> users.acquireTransactionLock(1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("SELECT count(*) FROM reservations")).isZero();
        assertThat(balanceCents(userId)).isZero();
    }

    private <T> T inRolledBackTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }

    /**
     * Runs {@code first} in a transaction that stays open, checks that {@code second} (in its own transaction)
     * blocks meanwhile, then rolls the first back and lets the second finish (also rolled back).
     *
     * @return the results of first and second
     */
    private <T> List<T> secondWaitsForFirst(Supplier<T> first, Supplier<T> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstResult = pool.submit(() -> inRolledBackTransaction(() -> {
                T result = first.get();
                firstDone.countDown();
                awaitQuietly(releaseFirst);
                return result;
            }));
            assertThat(firstDone.await(10, SECONDS)).isTrue();
            Future<T> secondResult = pool.submit(() -> inRolledBackTransaction(second));

            assertThatThrownBy(() -> secondResult.get(500, MILLISECONDS))
                    .as("second must wait for the first transaction").isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            return List.of(firstResult.get(10, SECONDS), secondResult.get(10, SECONDS));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
