package com.parking.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.parking.entity.WalletEntity;

public interface WalletRepository extends JpaRepository<WalletEntity, Long> {

    @Query(value = "SELECT balance_cents FROM wallets WHERE user_id = :userId", nativeQuery = true)
    Optional<Long> findBalanceCents(@Param("userId") long userId);

    /** Atomic credit. @return rows updated (0 = wallet missing). */
    @Modifying
    @Query(value = """
            UPDATE wallets SET balance_cents = balance_cents + :amount, updated_at = now()
            WHERE user_id = :userId
            """, nativeQuery = true)
    int credit(@Param("userId") long userId, @Param("amount") long amountCents);

    /**
     * Atomic conditional debit: never lets the balance go negative, even under concurrency
     * (the row lock serialises concurrent debits; the WHERE clause is re-checked after the lock).
     * @return rows updated (0 = insufficient balance)
     */
    @Modifying
    @Query(value = """
            UPDATE wallets SET balance_cents = balance_cents - :amount, updated_at = now()
            WHERE user_id = :userId AND balance_cents >= :amount
            """, nativeQuery = true)
    int debitIfSufficient(@Param("userId") long userId, @Param("amount") long amountCents);
}
