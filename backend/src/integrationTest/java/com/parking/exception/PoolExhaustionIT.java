package com.parking.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.CannotCreateTransactionException;

import com.parking.IntegrationTestSupport;
import com.parking.dto.response.DashboardResponse;
import com.parking.service.ReservationService;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The 8 PM peak can exhaust the connection pool. A reservation that needs the database must fail with an
 * exception GlobalExceptionHandler maps to 503 + Retry-After, and one that doesn't (window closed) must not wait.
 */
class PoolExhaustionIT extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;
    @Autowired
    ReservationService reservationService;

    @Test
    void reservationWhileThePoolIsExhaustedFailsBeforeTouchingAnything() throws Exception {
        long userId = newUser("peak", BigDecimal.TEN);
        long vehicleId = newVehicle(userId, "PEAK001");

        Throwable failure = whileEveryConnectionIsHeld(() -> reservationService.reserve(userId, vehicleId, 1));

        // The connection is taken when the transaction begins, so the pool timeout surfaces there.
        assertThat(failure).isInstanceOf(CannotCreateTransactionException.class);
        assertThat(count("SELECT count(*) FROM reservations")).isZero();
        assertThat(balanceCents(userId)).isEqualTo(1000L);
    }

    @Test
    void closedWindowIsAnsweredWithoutWaitingForAConnection() throws Exception {
        long userId = newUser("eager", BigDecimal.TEN);
        long vehicleId = newVehicle(userId, "EAGER01");
        setBusinessTime(businessToday().atTime(19, 59, 50));

        Throwable failure = whileEveryConnectionIsHeld(() -> reservationService.reserve(userId, vehicleId, 1));

        // Had it asked the pool for a connection, this would be a CannotCreateTransactionException.
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.RESERVATION_CLOSED));
    }

    @Test
    void cachedDashboardAnswersWithoutTakingAConnection() throws Exception {
        reservationService.dashboard(0L); // caches the date (one reload from PostgreSQL)
        AtomicReference<DashboardResponse> dashboard = new AtomicReference<>();

        Throwable failure = whileEveryConnectionIsHeld(() -> dashboard.set(reservationService.dashboard(0L)));

        // Had it opened a transaction (or run any query), it would have failed waiting for a connection.
        assertThat(failure).isNull();
        assertThat(dashboard.get().spaces()).hasSize(80);
    }

    private Throwable whileEveryConnectionIsHeld(ThrowingCallable call) throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                held.add(pool.getConnection());
            }
            return catchThrowable(call);
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }
}
