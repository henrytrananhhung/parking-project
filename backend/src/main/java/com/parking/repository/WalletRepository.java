package com.parking.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.parking.entity.WalletEntity;

/**
 * Balances change only through the two single-statement conditional UPDATEs below, never by loading, modifying
 * and saving a {@link WalletEntity}. Each one increments the version.
 *
 * <p>Bulk updates bypass the persistence context. They flush pending changes first ({@code flushAutomatically}),
 * but do not clear it: clearing would detach every entity the caller still uses in its transaction. A wallet
 * loaded earlier in the same transaction therefore holds a stale balance; read the balance with
 * {@link #findBalanceCents}, which always queries the database.
 */
public interface WalletRepository extends JpaRepository<WalletEntity, Long> {

    @Query("SELECT w.balanceCents FROM WalletEntity w WHERE w.user.id = :userId")
    Optional<Long> findBalanceCents(@Param("userId") long userId);

    /** Atomic credit. @return rows updated (0 = wallet missing) */
    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            UPDATE WalletEntity w
            SET w.balanceCents = w.balanceCents + :amount, w.updatedAt = instant, w.version = w.version + 1
            WHERE w.user.id = :userId
            """)
    int credit(@Param("userId") long userId, @Param("amount") long amountCents);

    /**
     * Atomic conditional debit: never lets the balance go negative, even under concurrency
     * (the row lock serialises concurrent debits; the WHERE clause is re-checked after the lock).
     *
     * @return rows updated (0 = insufficient balance)
     */
    @Modifying(flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            UPDATE WalletEntity w
            SET w.balanceCents = w.balanceCents - :amount, w.updatedAt = instant, w.version = w.version + 1
            WHERE w.user.id = :userId AND w.balanceCents >= :amount
            """)
    int debitIfSufficient(@Param("userId") long userId, @Param("amount") long amountCents);
}
