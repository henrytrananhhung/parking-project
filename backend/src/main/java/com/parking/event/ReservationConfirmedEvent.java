package com.parking.event;

import java.time.LocalDate;

/**
 * Published inside the reservation transaction; listeners run only AFTER_COMMIT.
 * Future hook: update the Redis dashboard / fast-rejection cache once the DB has committed.
 *
 * <p>AFTER_COMMIT listeners run on the request thread <em>before</em> the DB connection returns to the pool.
 * Keep them fast, or make slow ones (network calls such as Redis) {@code @Async}, or they lengthen connection
 * hold time during the 8 PM peak.
 */
public record ReservationConfirmedEvent(long reservationId, int spaceId, long vehicleId, long userId, LocalDate date) {
}
