package com.parking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Parking spaces 1-80, seeded by Flyway (V2). The application only reads them. */
@Entity
@Table(name = "parking_spaces")
public class ParkingSpaceEntity extends AbstractEntity {

    protected ParkingSpaceEntity() {
    }
}
