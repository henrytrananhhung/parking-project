package com.parking;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.parking.auth.AuthService;
import com.parking.common.BusinessException;
import com.parking.vehicle.VehicleService;
import com.parking.wallet.WalletService;

/** Shared Spring context + real PostgreSQL (Testcontainers). Tables are emptied before each test. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestSupport {

    @Autowired
    protected AuthService authService;
    @Autowired
    protected VehicleService vehicleService;
    @Autowired
    protected WalletService walletService;
    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE wallet_transactions, reservations, vehicles, wallets, users RESTART IDENTITY CASCADE").update();
    }

    protected long newUser(String username, BigDecimal deposit) {
        long userId = authService.createAccount(username, "password123");
        if (deposit.signum() > 0) {
            walletService.deposit(userId, deposit);
        }
        return userId;
    }

    protected long newVehicle(long userId, String plate) {
        return vehicleService.register(userId, plate).getId();
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
