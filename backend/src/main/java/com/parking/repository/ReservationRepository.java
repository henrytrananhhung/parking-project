package com.parking.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.parking.entity.ReservationEntity;

public interface ReservationRepository extends JpaRepository<ReservationEntity, Long>, ReservationClaimRepository {

    /** At most one row: {@code UNIQUE (vehicle_id, reservation_date)}. */
    Optional<ReservationEntity> findByVehicleIdAndReservationDate(Long vehicleId, LocalDate reservationDate);
}
