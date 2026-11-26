package com.parking.repository;

import static java.math.BigDecimal.ZERO;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.IntegrationTestSupport;

/**
 * Wallet balance updates: single conditional statements, each successful one incrementing the version exactly
 * once. Versions are compared with the value read before the change, never with an absolute number (wallets
 * migrated by V4 start at 0 whatever their history).
 */
class WalletRepositoryIT extends IntegrationTestSupport {

    @Autowired
    WalletRepository wallets;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void creditAndSuccessfulDebitIncrementTheVersionAFailedDebitDoesNot() {
        long userId = newUser("direct", ZERO);
        long before = walletVersion(userId);

        // Direct repository updates write no ledger entry, so they run in a rolled-back transaction
        // (the audit after each test checks balance = ledger). Reads inside it see the uncommitted updates.
        inRolledBackTransaction(() -> {
            assertThat(wallets.credit(userId, 5000)).isEqualTo(1);
            assertThat(walletVersion(userId)).isEqualTo(before + 1);
            assertThat(wallets.debitIfSufficient(userId, 1000)).isEqualTo(1);
            assertThat(walletVersion(userId)).isEqualTo(before + 2);
            assertThat(wallets.debitIfSufficient(userId, 4001)).as("more than the balance").isZero();
            assertThat(walletVersion(userId)).isEqualTo(before + 2);
            assertThat(wallets.findBalanceCents(userId)).contains(4000L);
            return null;
        });

        assertThat(walletVersion(userId)).isEqualTo(before);
        assertThat(balanceCents(userId)).isZero();
    }

    @Test
    void aRolledBackDebitLeavesBalanceAndVersionUnchanged() {
        long userId = newUser("undo", BigDecimal.TEN);
        long before = walletVersion(userId);

        inRolledBackTransaction(() -> wallets.debitIfSufficient(userId, 1000));

        assertThat(balanceCents(userId)).isEqualTo(1000L);
        assertThat(walletVersion(userId)).isEqualTo(before);
    }

    @Test
    void aDepositIncrementsTheVersionOnceAndItsReplayDoesNot() {
        long userId = newUser("saver", ZERO);
        long before = walletVersion(userId);
        UUID key = UUID.randomUUID();

        walletService.deposit(userId, BigDecimal.TEN, key);
        assertThat(walletVersion(userId)).isEqualTo(before + 1);
        walletService.deposit(userId, BigDecimal.TEN, key);
        assertThat(walletVersion(userId)).as("replayed deposit").isEqualTo(before + 1);
        assertThat(balanceCents(userId)).isEqualTo(1000L);
    }

    @Test
    void aWalletCreatedAndCreditedInOneTransactionReadsAndCommitsTheCreditedBalance() {
        // The persistence context still holds the new wallet (balance 0, version 0) when the bulk UPDATE credits
        // it: the balance must be read from the database, and the stale entity must not be written back.
        Long balanceInTransaction = new TransactionTemplate(transactionManager).execute(status -> {
            long userId = authService.createAccount("onestop", "password123");
            assertThat(walletService.deposit(userId, new BigDecimal("15.50"), UUID.randomUUID())).isEqualTo(1550L);
            return wallets.findBalanceCents(userId).orElseThrow();
        });

        long userId = count("SELECT id FROM users WHERE username = 'onestop'");
        assertThat(balanceInTransaction).isEqualTo(1550L);
        assertThat(balanceCents(userId)).isEqualTo(1550L);
        assertThat(walletVersion(userId)).as("created at 0, credited once").isEqualTo(1L);
    }

    private <T> T inRolledBackTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            status.setRollbackOnly();
            return work.get();
        });
    }
}
