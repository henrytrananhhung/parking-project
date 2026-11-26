package com.parking.repository;

import java.util.Optional;
import java.util.UUID;

/** Deposit ledger writes that Spring Data cannot derive: {@code INSERT ... ON CONFLICT DO NOTHING}. */
public interface DepositLedgerRepository {

    /**
     * Records a deposit unless this user already recorded one under {@code idempotencyKey}.
     *
     * <p>Like a plain INSERT, this waits for an in-flight transaction using the same key; if that transaction
     * rolls back, this insert proceeds. So an empty result always means a <em>committed</em> deposit holds the
     * key, and {@link #findDepositAmountCents} in the same (READ COMMITTED) transaction can see it.
     *
     * @return the new ledger entry id, or empty if the key was already used by this user
     */
    Optional<Long> insertDepositIfNew(long userId, long amountCents, UUID idempotencyKey);

    /** @return the amount of the deposit this user recorded under {@code idempotencyKey}, if any */
    Optional<Long> findDepositAmountCents(long userId, UUID idempotencyKey);
}
