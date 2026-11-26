package com.parking.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.IntegrationTestSupport;
import com.parking.repository.UserRepository;

/** Simulates several Cloud Run instances starting at once and seeding the same database. */
class DemoDataSeederIT extends IntegrationTestSupport {

    @Autowired
    UserRepository users;
    @Autowired
    TransactionTemplate tx;

    private DemoDataSeeder newSeeder() {
        return new DemoDataSeeder(authService, vehicleService, walletService, users, tx, "TestDemoPass1");
    }

    @Test
    void concurrentSeedingCreatesEachDemoAccountExactlyOnceAndCompletely() throws Exception {
        List<Callable<Integer>> instances = List.of(
                () -> newSeeder().seed(), () -> newSeeder().seed(), () -> newSeeder().seed());

        List<Outcome<Integer>> outcomes = runConcurrently(instances);

        assertThat(outcomes).allMatch(Outcome::success);
        assertThat(outcomes.stream().mapToInt(Outcome::value).sum()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM wallets WHERE balance_cents = 10000")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM vehicles")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'DEPOSIT'")).isEqualTo(3);
    }

    @Test
    void seedingAgainIsANoOp() {
        assertThat(newSeeder().seed()).isEqualTo(3);
        assertThat(newSeeder().seed()).isZero();
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(3);
    }

    @Test
    void demoAccountsCanLogIn() {
        newSeeder().seed();

        assertThat(authService.login("demo1", "TestDemoPass1").token()).isNotBlank();
    }
}
