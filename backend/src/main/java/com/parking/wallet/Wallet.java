package com.parking.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Balance is only ever changed through atomic conditional UPDATEs in WalletRepository. */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
    }

    public Wallet(Long userId) {
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
