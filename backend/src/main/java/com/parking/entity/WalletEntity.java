package com.parking.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One wallet per user. The balance is only ever changed by the atomic conditional UPDATEs in
 * {@code WalletRepository}, which also increment the version. This entity has no mutators, so Hibernate never
 * sees a loaded wallet as dirty and never writes one; if a future change did write a stale wallet,
 * {@code @Version} would make it fail instead of overwriting concurrent debits (lost update).
 */
@Entity
@Table(name = "wallets", uniqueConstraints = @UniqueConstraint(name = "uk_wallets_user_id", columnNames = "user_id"))
public class WalletEntity extends AbstractEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletEntity() {
    }

    public WalletEntity(UserEntity user) {
        this.user = user;
        this.balanceCents = 0;
        this.updatedAt = Instant.now();
    }

    public UserEntity getUser() {
        return user;
    }

    /** Value at load time only: read the current balance with {@code WalletRepository.findBalanceCents}. */
    public long getBalanceCents() {
        return balanceCents;
    }
}
