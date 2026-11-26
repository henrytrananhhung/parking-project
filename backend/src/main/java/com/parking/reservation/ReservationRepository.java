package com.parking.reservation;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationClaimRepository {

    /** At most one row: {@code UNIQUE (vehicle_id, reservation_date)}. */
    Optional<Reservation> findByVehicleIdAndReservationDate(Long vehicleId, LocalDate reservationDate);
}
