package com.parking.dto.response;

import java.time.LocalDate;

/** A confirmed reservation; a repeated request returns the original one. */
public record ReservationResponse(long id, int spaceId, LocalDate date, String plate, long priceCents) {
}
