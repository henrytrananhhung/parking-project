package com.parking.reservation;

import java.time.LocalDate;

/**
 * Published inside the reservation transaction; listeners run only AFTER_COMMIT.
 * Future hook: update the Redis dashboard / fast-rejection cache once the DB has committed.
 */
public record ReservationConfirmed(long reservationId, int spaceId, long vehicleId, long userId, LocalDate date) {
}
