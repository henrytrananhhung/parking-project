package com.parking.wallet;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation, picked up by its {@code Impl} suffix. */
class DepositLedgerRepositoryImpl implements DepositLedgerRepository {

    private final JdbcClient jdbc;

    DepositLedgerRepositoryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> insertDepositIfNew(long userId, long amountCents, UUID idempotencyKey) {
        return jdbc.sql("""
                INSERT INTO wallet_transactions (user_id, type, amount_cents, idempotency_key)
                VALUES (:userId, 'DEPOSIT', :amountCents, :idempotencyKey)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                RETURNING id
                """)
                .param("userId", userId)
                .param("amountCents", amountCents)
                .param("idempotencyKey", idempotencyKey)
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<Long> findDepositAmountCents(long userId, UUID idempotencyKey) {
        return jdbc.sql("""
                SELECT amount_cents FROM wallet_transactions
                WHERE user_id = :userId AND idempotency_key = :idempotencyKey AND type = 'DEPOSIT'
                """)
                .param("userId", userId)
                .param("idempotencyKey", idempotencyKey)
                .query(Long.class)
                .optional();
    }
}
