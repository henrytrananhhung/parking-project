package com.parking.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Append-only money ledger entry: +amount for deposits, -amount for reservations.
 * Reservation charges are saved through JPA. Deposits are inserted with {@code INSERT ... ON CONFLICT} on their
 * idempotency key by {@code WalletTransactionRepository}; reservation charges have no key.
 */
@Entity
@Table(name = "wallet_transactions", uniqueConstraints = @UniqueConstraint(
        name = "uk_wallet_transactions_user_idempotency_key", columnNames = {"user_id", "idempotency_key"}))
public class WalletTransactionEntity extends AbstractEntity {

    public enum Type { DEPOSIT, RESERVATION }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private ReservationEntity reservation;

    @Column(name = "idempotency_key", updatable = false)
    private UUID idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletTransactionEntity() {
    }

    private WalletTransactionEntity(UserEntity user, Type type, long amountCents, ReservationEntity reservation) {
        this.user = user;
        this.type = type;
        this.amountCents = amountCents;
        this.reservation = reservation;
        this.createdAt = Instant.now();
    }

    public static WalletTransactionEntity reservationCharge(UserEntity user, long priceCents,
            ReservationEntity reservation) {
        return new WalletTransactionEntity(user, Type.RESERVATION, -priceCents, reservation);
    }

    public UserEntity getUser() {
        return user;
    }

    public Type getType() {
        return type;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public ReservationEntity getReservation() {
        return reservation;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }
}
