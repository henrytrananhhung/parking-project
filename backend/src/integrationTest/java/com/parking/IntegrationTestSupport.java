package com.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.parking.service.AuthService;
import com.parking.exception.BusinessException;
import com.parking.service.ReservationWindow;
import com.parking.service.VehicleService;
import com.parking.service.WalletService;

/**
 * Shared Spring context (real HTTP port) + real PostgreSQL (Testcontainers). Before each test the tables are
 * emptied and the clock is set to {@link #DEFAULT_BUSINESS_TIME} today in the business timezone, so the real
 * 20:00 window is open and dates are deterministic. After each test the money/booking rules are audited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, IntegrationTestConfiguration.class })
public abstract class IntegrationTestSupport {

    /** Business-local time every test starts at: after the 20:00 opening, before midnight. */
    protected static final LocalTime DEFAULT_BUSINESS_TIME = LocalTime.of(21, 0);

    @Autowired
    protected AuthService authService;
    @Autowired
    protected VehicleService vehicleService;
    @Autowired
    protected WalletService walletService;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    private ReservationWindow window;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE wallet_transactions, reservations, vehicles, wallets, users RESTART IDENTITY CASCADE").update();
    }

    @BeforeEach
    void resetClock() {
        setBusinessTime(LocalDate.now(window.zone()).atTime(DEFAULT_BUSINESS_TIME));
    }

    /** Moves the clock to this wall-clock time in the business timezone. */
    protected void setBusinessTime(LocalDateTime businessLocalTime) {
        clock.set(businessLocalTime.atZone(window.zone()).toInstant());
    }

    /** Today's date in the business timezone, according to the test clock. */
    protected LocalDate businessToday() {
        return LocalDate.ofInstant(clock.instant(), window.zone());
    }

    /**
     * Audits money and bookings after every integration test, whatever the test did.
     * Each query selects the offending ids, so a failure names them.
     */
    @AfterEach
    void moneyAndBookingRulesHold() {
        assertThat(ids("""
                SELECT w.user_id FROM wallets w
                LEFT JOIN wallet_transactions t ON t.user_id = w.user_id
                GROUP BY w.user_id, w.balance_cents
                HAVING w.balance_cents <> COALESCE(SUM(t.amount_cents), 0)
                """)).as("users whose balance differs from their ledger total").isEmpty();
        assertThat(ids("""
                SELECT r.id FROM reservations r
                LEFT JOIN wallet_transactions t ON t.reservation_id = r.id AND t.type = 'RESERVATION'
                    AND t.user_id = r.user_id AND t.amount_cents = -r.price_cents
                GROUP BY r.id
                HAVING count(t.id) <> 1
                """)).as("reservations not charged exactly once at their price").isEmpty();
        assertThat(ids("""
                SELECT id FROM wallet_transactions WHERE type = 'RESERVATION' AND reservation_id IS NULL
                """)).as("reservation charges without a reservation").isEmpty();
        assertThat(ids("""
                SELECT space_id FROM reservations GROUP BY space_id, reservation_date HAVING count(*) > 1
                """)).as("spaces booked twice on the same date").isEmpty();
        assertThat(ids("""
                SELECT vehicle_id FROM reservations GROUP BY vehicle_id, reservation_date HAVING count(*) > 1
                """)).as("vehicles holding more than one space on the same date").isEmpty();
    }

    private List<Long> ids(String sql) {
        return jdbc.sql(sql).query(Long.class).list();
    }

    protected long newUser(String username, BigDecimal deposit) {
        long userId = authService.createAccount(username, "password123");
        if (deposit.signum() > 0) {
            walletService.deposit(userId, deposit, UUID.randomUUID());
        }
        return userId;
    }

    protected long newVehicle(long userId, String plate) {
        return vehicleService.register(userId, plate).id();
    }

    protected long balanceCents(long userId) {
        return walletService.balanceCents(userId);
    }

    protected long count(String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Long.class).single();
    }

    /** Outcome of one concurrent call: the result, or the business error code. */
    public record Outcome<T>(T value, String errorCode) {
        public boolean success() {
            return errorCode == null;
        }
    }

    /** Releases all tasks at the same instant to maximise contention, then collects the outcomes. */
    protected static <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (BusinessException e) {
                        return new Outcome<T>(null, e.code().name());
                    }
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
