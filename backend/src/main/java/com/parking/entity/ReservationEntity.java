package com.parking.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservations")
public class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Integer spaceId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reservation_date", nullable = false)
    private LocalDate reservationDate;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReservationEntity() {
    }

    public ReservationEntity(int spaceId, long vehicleId, long userId, LocalDate reservationDate, long priceCents) {
        this.spaceId = spaceId;
        this.vehicleId = vehicleId;
        this.userId = userId;
        this.reservationDate = reservationDate;
        this.priceCents = priceCents;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Integer getSpaceId() {
        return spaceId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getReservationDate() {
        return reservationDate;
    }

    public long getPriceCents() {
        return priceCents;
    }
}
