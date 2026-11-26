package com.parking.entity;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Balance is only ever changed through atomic conditional UPDATEs in WalletRepository.
 * {@code @Immutable} enforces that: Hibernate never writes an UPDATE for a loaded Wallet, so a stale
 * entity saved later cannot overwrite concurrent debits (lost update). Inserts still work.
 */
@Entity
@Immutable
@Table(name = "wallets")
public class WalletEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletEntity() {
    }

    public WalletEntity(Long userId) {
        this.userId = userId;
        this.balanceCents = 0;
        this.updatedAt = Instant.now();
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalanceCents() {
        return balanceCents;
    }
}
