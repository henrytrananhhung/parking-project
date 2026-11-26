package com.parking.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.auth.AuthService;
import com.parking.auth.UserRepository;
import com.parking.vehicle.VehicleService;
import com.parking.wallet.WalletService;

/**
 * Creates a few synthetic demo accounts ($100 balance + one vehicle each) through the normal services.
 *
 * <p>Safe when several Cloud Run instances start at the same time: everything runs in ONE transaction that
 * first takes a PostgreSQL transaction-level advisory lock, so instances seed one after another, and each
 * account is either fully created (user + wallet + vehicle + deposit) or not at all.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    static final long ADVISORY_LOCK_KEY = 7_300_421L;
    static final BigDecimal STARTING_BALANCE = BigDecimal.valueOf(100);

    record DemoAccount(String username, String plate) {
    }

    static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("demo1", "DEMO001"),
            new DemoAccount("demo2", "DEMO002"),
            new DemoAccount("demo3", "DEMO003"));

    private final AuthService authService;
    private final VehicleService vehicleService;
    private final WalletService walletService;
    private final UserRepository users;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String password;

    public DemoDataSeeder(AuthService authService, VehicleService vehicleService, WalletService walletService,
            UserRepository users, JdbcClient jdbc, TransactionTemplate tx,
            @Value("${parking.demo.password}") String password) {
        this.authService = authService;
        this.vehicleService = vehicleService;
        this.walletService = walletService;
        this.users = users;
        this.jdbc = jdbc;
        this.tx = tx;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("Demo seeding complete: {} account(s) created", seed());
        } catch (RuntimeException e) {
            // Seeding is a convenience: never block the application from serving.
            log.warn("Demo seeding failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return number of accounts created by this call. */
    public int seed() {
        Integer created = tx.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", ADVISORY_LOCK_KEY)
                    .query((rs, n) -> 1).list();
            int count = 0;
            for (DemoAccount account : ACCOUNTS) {
                if (users.existsByUsername(account.username())) {
                    continue;
                }
                long userId = authService.createAccount(account.username(), password);
                vehicleService.register(userId, account.plate());
                walletService.deposit(userId, STARTING_BALANCE, startingDepositKey(account));
                count++;
            }
            return count;
        });
        return created == null ? 0 : created;
    }

    /** Deterministic per account: the starting deposit can never be applied twice. */
    static UUID startingDepositKey(DemoAccount account) {
        return UUID.nameUUIDFromBytes(("demo-starting-deposit:" + account.username()).getBytes(StandardCharsets.UTF_8));
    }
}
