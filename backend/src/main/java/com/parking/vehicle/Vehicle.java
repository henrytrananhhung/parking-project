package com.parking.vehicle;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Normalized: upper case letters and digits only. */
    @Column(nullable = false, length = 20)
    private String plate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Vehicle() {
    }

    public Vehicle(Long userId, String plate) {
        this.userId = userId;
        this.plate = plate;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPlate() {
        return plate;
    }
}
