package com.parking.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One vehicle on one space for one day. Rows are created by {@code ReservationRepository.insertIfFree}
 * ({@code INSERT ... ON CONFLICT DO NOTHING}): the two unique constraints decide the winner under concurrency.
 */
@Entity
@Table(name = "reservations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reservations_space_date", columnNames = {"space_id", "reservation_date"}),
        @UniqueConstraint(name = "uk_reservations_vehicle_date", columnNames = {"vehicle_id", "reservation_date"})})
public class ReservationEntity extends AbstractEntity {

    public static final String FK_SPACE = "reservations_space_id_fkey";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false, foreignKey = @ForeignKey(name = FK_SPACE))
    private ParkingSpaceEntity space;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private VehicleEntity vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "reservation_date", nullable = false)
    private LocalDate reservationDate;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReservationEntity() {
    }

    public ReservationEntity(ParkingSpaceEntity space, VehicleEntity vehicle, UserEntity user,
            LocalDate reservationDate, long priceCents) {
        this.space = space;
        this.vehicle = vehicle;
        this.user = user;
        this.reservationDate = reservationDate;
        this.priceCents = priceCents;
        this.createdAt = Instant.now();
    }

    public ParkingSpaceEntity getSpace() {
        return space;
    }

    public VehicleEntity getVehicle() {
        return vehicle;
    }

    public UserEntity getUser() {
        return user;
    }

    public LocalDate getReservationDate() {
        return reservationDate;
    }

    public long getPriceCents() {
        return priceCents;
    }
}
