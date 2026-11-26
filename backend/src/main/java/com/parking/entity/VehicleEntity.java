package com.parking.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "vehicles", uniqueConstraints = @UniqueConstraint(name = VehicleEntity.UK_PLATE, columnNames = "plate"))
public class VehicleEntity extends AbstractEntity {

    public static final String UK_PLATE = "uk_vehicles_plate";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    /** Normalized: upper case letters and digits only. */
    @Column(nullable = false, length = 20)
    private String plate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VehicleEntity() {
    }

    public VehicleEntity(UserEntity user, String plate) {
        this.user = user;
        this.plate = plate;
        this.createdAt = Instant.now();
    }

    public UserEntity getUser() {
        return user;
    }

    public String getPlate() {
        return plate;
    }
}
