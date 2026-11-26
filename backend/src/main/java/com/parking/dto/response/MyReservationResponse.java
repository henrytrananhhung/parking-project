package com.parking.dto.response;

import java.time.LocalDate;

public record MyReservationResponse(long id, int spaceId, LocalDate date, String plate, long priceCents) {
}
