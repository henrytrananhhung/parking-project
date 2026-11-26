package com.parking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;

import com.parking.IntegrationTestSupport;
import com.parking.exception.BusinessException;

import jakarta.persistence.EntityManagerFactory;

/**
 * SQL statements per use case, counted with Hibernate statistics (enabled in the test profile only): no N+1
 * queries, no statement that is not needed, and read counts that do not grow with the amount of data.
 *
 * <p>Measurements are isolated: all setup runs first, statistics are cleared right before the measured call and
 * read right after it. {@code @Isolated} keeps other test classes from running in parallel, and the test profile
 * has no background database work (no demo seeding, no schedulers).
 */
@Isolated
class QueryCountIT extends IntegrationTestSupport {

    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    ReservationService reservationService;

    private Statistics statistics;

    @BeforeEach
    void statistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
    }

    @Test
    void dashboardIsOneQueryWhenNotCachedAndNoneOnceCached() {
        long owner = newUser("fleet", new BigDecimal("800"));
        long viewer = newUser("viewer", BigDecimal.ZERO);
        // Nothing cached for the date yet: one reload of its reservations from PostgreSQL.
        assertThat(statements(() -> reservationService.dashboard(owner))).as("not cached").isEqualTo(1);
        assertThat(statements(() -> reservationService.dashboard(owner))).as("cached, no reservation").isZero();

        for (int space = 1; space <= 80; space++) {
            reservationService.reserve(owner, newVehicle(owner, "FLEET%03d".formatted(space)), space);
            if (space == 1) {
                assertThat(statements(() -> reservationService.dashboard(owner))).as("cached, 1 reservation").isZero();
            }
        }

        assertThat(statements(() -> reservationService.dashboard(owner))).as("sold out").isZero();
        assertThat(statements(() -> reservationService.dashboard(viewer))).as("sold out, other user").isZero();
        assertThat(reservationService.dashboard(owner).spaces()).hasSize(80).allMatch(s -> s.reserved() && s.mine());
        assertThat(reservationService.dashboard(viewer).spaces()).noneMatch(s -> s.mine());
    }

    @Test
    void myReservationsIsOneQueryWhateverTheNumberOfReservations() {
        long userId = newUser("collector", new BigDecimal("50"));
        reservationService.reserve(userId, newVehicle(userId, "MINE0001"), 1);
        assertThat(statements(() -> reservationService.myReservations(userId))).as("1 reservation").isEqualTo(1);

        for (int space = 2; space <= 5; space++) {
            reservationService.reserve(userId, newVehicle(userId, "MINE000" + space), space);
        }

        assertThat(statements(() -> reservationService.myReservations(userId))).as("5 reservations").isEqualTo(1);
        assertThat(reservationService.myReservations(userId)).hasSize(5);
    }

    @Test
    void reservationIssuesOnlyTheStatementsItNeeds() {
        long userId = newUser("driver", new BigDecimal("20"));
        long vehicleId = newVehicle(userId, "DRIVER01");
        long rival = newUser("rival", new BigDecimal("20"));
        long rivalVehicle = newVehicle(rival, "RIVAL001");
        long broke = newUser("broke", BigDecimal.ZERO);
        long brokeVehicle = newVehicle(broke, "BROKE001");

        // Competing for a free space: vehicle SELECT, INSERT ... ON CONFLICT, conditional UPDATE, ledger INSERT
        assertThat(statements(() -> reservationService.reserve(userId, vehicleId, 1))).as("new").isEqualTo(4);
        // Retry of that success: answered from the cached (committed) reservation
        assertThat(statements(() -> reservationService.reserve(userId, vehicleId, 1))).as("retry").isZero();
        // The same vehicle on another space: VEHICLE_ALREADY_RESERVED from the cached reservation
        assertThat(statementsOfRejected(() -> reservationService.reserve(userId, vehicleId, 3)))
                .as("vehicle already reserved").isZero();
        // Space known taken, rival's vehicle not known yet: one read (owner + its reservation), no claim
        assertThat(statementsOfRejected(() -> reservationService.reserve(rival, rivalVehicle, 1)))
                .as("space taken").isEqualTo(1);
        // Free space: vehicle SELECT, INSERT, UPDATE matching no row; then the transaction rolls back
        assertThat(statementsOfRejected(() -> reservationService.reserve(broke, brokeVehicle, 2)))
                .as("insufficient balance").isEqualTo(3);
    }

    @Test
    void soldOutDateAnswersEveryReservationRequestWithoutTheDatabase() {
        long owner = newUser("fleet", new BigDecimal("800"));
        long firstVehicle = newVehicle(owner, "FLEET001");
        reservationService.reserve(owner, firstVehicle, 1);
        for (int space = 2; space <= 80; space++) {
            reservationService.reserve(owner, newVehicle(owner, "FLEET%03d".formatted(space)), space);
        }
        long late = newUser("late", BigDecimal.TEN);
        long lateVehicle = newVehicle(late, "LATE0001");

        // A vehicle this instance has never seen: one read to learn its owner (vehicles never change owner).
        assertThat(statementsOfRejected(() -> reservationService.reserve(late, lateVehicle, 5)))
                .as("late, first request").isEqualTo(1);
        assertThat(statementsOfRejected(() -> reservationService.reserve(late, lateVehicle, 6)))
                .as("late, any later request").isZero();
        assertThat(statements(() -> reservationService.reserve(owner, firstVehicle, 1))).as("retry").isZero();
        assertThat(statementsOfRejected(() -> reservationService.reserve(owner, firstVehicle, 2)))
                .as("vehicle already reserved").isZero();

        // Instances preload vehicle owners at startup: then even a vehicle's first request needs no read.
        long later = newUser("later", BigDecimal.TEN);
        long laterVehicle = newVehicle(later, "LATE0002");
        reservationCache.preloadOwners();
        assertThat(statementsOfRejected(() -> reservationService.reserve(later, laterVehicle, 7)))
                .as("preloaded owner, first request").isZero();
    }

    @Test
    void depositIsThreeStatementsAndSoIsItsReplay() {
        long userId = newUser("saver", BigDecimal.ZERO);
        UUID key = UUID.randomUUID();

        // INSERT ... ON CONFLICT, credit UPDATE, balance SELECT
        assertThat(statements(() -> walletService.deposit(userId, BigDecimal.TEN, key))).as("new").isEqualTo(3);
        // conflicting INSERT, recorded amount SELECT, balance SELECT
        assertThat(statements(() -> walletService.deposit(userId, BigDecimal.TEN, key))).as("replay").isEqualTo(3);
        assertThat(statements(() -> walletService.balanceCents(userId))).as("balance").isEqualTo(1);
    }

    @Test
    void accountsAndVehiclesIssueNoExtraQueries() {
        // username check, user INSERT, wallet INSERT (persisted, not merged)
        assertThat(statements(() -> authService.register("newcomer", "password123"))).as("register").isEqualTo(3);
        assertThat(statements(() -> authService.login("newcomer", "password123"))).as("login").isEqualTo(1);

        long owner = newUser("owner", BigDecimal.ZERO);
        // plate check, vehicle INSERT (the owner is a reference, not loaded)
        assertThat(statements(() -> vehicleService.register(owner, "OWNER001"))).as("add vehicle").isEqualTo(2);
        vehicleService.register(owner, "OWNER002");
        vehicleService.register(owner, "OWNER003");
        assertThat(statements(() -> vehicleService.list(owner))).as("3 vehicles").isEqualTo(1);
    }

    private long statements(Runnable measured) {
        statistics.clear();
        measured.run();
        return statistics.getPrepareStatementCount();
    }

    private long statementsOfRejected(ThrowingCallable measured) {
        statistics.clear();
        assertThatThrownBy(measured).isInstanceOf(BusinessException.class);
        return statistics.getPrepareStatementCount();
    }
}
