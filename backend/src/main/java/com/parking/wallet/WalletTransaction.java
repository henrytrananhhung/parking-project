package com.parking.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only money ledger entry: +amount for deposits, -amount for reservations.
 * Reservation charges are saved through JPA; deposits are inserted by {@link DepositLedgerRepository}
 * because they need {@code ON CONFLICT} on their idempotency key (column not mapped here).
 */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    public enum Type { DEPOSIT, RESERVATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletTransaction() {
    }

    private WalletTransaction(Long userId, Type type, long amountCents, Long reservationId) {
        this.userId = userId;
        this.type = type;
        this.amountCents = amountCents;
        this.reservationId = reservationId;
        this.createdAt = Instant.now();
    }

    public static WalletTransaction reservation(long userId, long priceCents, long reservationId) {
        return new WalletTransaction(userId, Type.RESERVATION, -priceCents, reservationId);
    }

    public Long getUserId() {
        return userId;
    }

    public Type getType() {
        return type;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Long getReservationId() {
        return reservationId;
    }
}
