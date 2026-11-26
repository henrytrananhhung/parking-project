package com.parking.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.parking.entity.ParkingSpaceEntity;
import com.parking.repository.projection.SpaceOccupancy;

public interface ParkingSpaceRepository extends JpaRepository<ParkingSpaceEntity, Long> {

    /** Every space with its reservation for {@code date}, if any: one query, scalar columns only (no entities). */
    @Query("""
            SELECT new com.parking.repository.projection.SpaceOccupancy(s.id, v.plate, r.user.id)
            FROM ParkingSpaceEntity s
            LEFT JOIN ReservationEntity r ON r.space = s AND r.reservationDate = :date
            LEFT JOIN r.vehicle v
            ORDER BY s.id
            """)
    List<SpaceOccupancy> findOccupancy(@Param("date") LocalDate date);
}
