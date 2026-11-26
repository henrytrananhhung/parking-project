package com.parking.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.parking.entity.WalletTransactionEntity;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, Long> {

    /**
     * Records a deposit unless this user already recorded one under {@code idempotencyKey}.
     *
     * <p>Native SQL because JPQL has no {@code ON CONFLICT} (see {@link ReservationRepository#insertIfFree}).
     * Like a plain INSERT, this waits for an in-flight transaction using the same key; if that transaction rolls
     * back, this insert proceeds. So 0 always means a <em>committed</em> deposit holds the key, and
     * {@link #findAmountCents} in the same (READ COMMITTED) transaction can see it.
     *
     * @return 1 if the deposit was recorded, 0 if the key was already used by this user
     */
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            INSERT INTO wallet_transactions (user_id, type, amount_cents, idempotency_key)
            VALUES (:userId, 'DEPOSIT', :amountCents, :idempotencyKey)
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertDepositIfNew(@Param("userId") long userId, @Param("amountCents") long amountCents,
            @Param("idempotencyKey") UUID idempotencyKey);

    /** @return the amount of the entry of this type the user recorded under {@code idempotencyKey}, if any */
    @Query("""
            SELECT t.amountCents FROM WalletTransactionEntity t
            WHERE t.user.id = :userId AND t.idempotencyKey = :idempotencyKey AND t.type = :type
            """)
    Optional<Long> findAmountCents(@Param("userId") long userId, @Param("idempotencyKey") UUID idempotencyKey,
            @Param("type") WalletTransactionEntity.Type type);
}
