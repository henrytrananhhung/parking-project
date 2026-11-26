package com.parking.reservation;

import java.time.LocalDate;
import java.util.Optional;

/** Reservation insert that Spring Data cannot derive: {@code INSERT ... ON CONFLICT DO NOTHING}. */
public interface ReservationClaimRepository {

    /**
     * Inserts the reservation unless the space or the vehicle is already reserved for that date.
     *
     * <p>Like a plain INSERT, this waits for any in-flight transaction holding the same space or vehicle:
     * if that transaction rolls back, this insert proceeds. So an empty result always means a
     * <em>committed</em> reservation holds the slot, and a following query in the same
     * (READ COMMITTED) transaction can see it. Unlike a plain INSERT, a conflict does not abort the
     * transaction.
     *
     * @return the new reservation id, or empty if the space or the vehicle is already taken for the date
     * @throws org.springframework.dao.DataIntegrityViolationException for anything other than a
     *         unique conflict, e.g. an unknown space (foreign key)
     */
    Optional<Long> insertIfFree(int spaceId, long vehicleId, long userId, LocalDate date, long priceCents);
}
