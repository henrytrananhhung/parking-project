package com.parking.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.parking.entity.VehicleEntity;
import com.parking.repository.projection.VehicleDayStatus;
import com.parking.repository.projection.VehicleOwnerRow;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {

    List<VehicleEntity> findByUserIdOrderById(Long userId);

    Optional<VehicleEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByPlate(String plate);

    /**
     * The vehicle's owner and plate, and its reservation for {@code date} if any, in one statement (vehicle primary
     * key + the {@code (vehicle_id, reservation_date)} unique index). Empty if the vehicle does not exist.
     * Read-only transaction of its own (joins the caller's if any): the reservation coordination calls it outside
     * service transactions.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.parking.repository.projection.VehicleDayStatus(
                v.user.id, v.plate, r.id, r.space.id, r.user.id, r.priceCents)
            FROM VehicleEntity v
            LEFT JOIN ReservationEntity r ON r.vehicle = v AND r.reservationDate = :date
            WHERE v.id = :vehicleId
            """)
    Optional<VehicleDayStatus> findDayStatus(@Param("vehicleId") long vehicleId, @Param("date") LocalDate date);

    /**
     * The newest vehicles' owners and plates, at most {@code limit} (one query): preloads the reservation cache at
     * startup, so the opening burst does not need one read per vehicle. Read-only transaction of its own.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.parking.repository.projection.VehicleOwnerRow(v.id, v.user.id, v.plate)
            FROM VehicleEntity v ORDER BY v.id DESC
            """)
    List<VehicleOwnerRow> findOwners(Limit limit);
}
