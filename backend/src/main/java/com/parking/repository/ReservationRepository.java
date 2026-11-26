package com.parking.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.parking.entity.ReservationEntity;
import com.parking.repository.projection.ConfirmedReservation;

public interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {

    /**
     * Inserts the reservation unless the space or the vehicle is already reserved for that date.
     *
     * <p>Native SQL because JPQL has no {@code ON CONFLICT}: a JPA persist would raise a unique violation, which
     * aborts the PostgreSQL transaction, and the replay lookup that follows a conflict must run in that same
     * transaction. Like a plain INSERT, this waits for any in-flight transaction holding the same space or
     * vehicle: if that transaction rolls back, this insert proceeds. So an empty result always means a
     * <em>committed</em> reservation holds the slot, and a following query in the same (READ COMMITTED)
     * transaction can see it. Must run inside the caller's transaction, together with the payment.
     *
     * @return the new reservation id, or empty if the space or the vehicle is already taken for the date
     * @throws org.springframework.dao.DataIntegrityViolationException for anything other than a unique conflict,
     *         e.g. an unknown space (foreign key {@link ReservationEntity#FK_SPACE})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            INSERT INTO reservations (space_id, vehicle_id, user_id, reservation_date, price_cents)
            VALUES (:spaceId, :vehicleId, :userId, :date, :priceCents)
            ON CONFLICT DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    Optional<Long> insertIfFree(@Param("spaceId") long spaceId, @Param("vehicleId") long vehicleId,
            @Param("userId") long userId, @Param("date") LocalDate date, @Param("priceCents") long priceCents);

    /** At most one row: {@code UNIQUE (vehicle_id, reservation_date)}. */
    Optional<ReservationEntity> findByVehicleIdAndReservationDate(Long vehicleId, LocalDate reservationDate);

    /** The user's reservations, newest date first; the vehicle (for its plate) is fetched in the same query. */
    @Query("""
            SELECT r FROM ReservationEntity r JOIN FETCH r.vehicle
            WHERE r.user.id = :userId
            ORDER BY r.reservationDate DESC, r.space.id
            """)
    List<ReservationEntity> findAllByUserIdWithVehicle(@Param("userId") long userId);

    /**
     * Every reservation of {@code date}, in one query (at most one per space): reloads the reservation cache.
     * Read-only transaction of its own (joins the caller's if any): called outside service transactions.
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.parking.repository.projection.ConfirmedReservation(
                r.id, r.space.id, r.vehicle.id, r.user.id, v.plate, r.priceCents)
            FROM ReservationEntity r JOIN r.vehicle v
            WHERE r.reservationDate = :date
            """)
    List<ConfirmedReservation> findConfirmedOn(@Param("date") LocalDate date);
}
