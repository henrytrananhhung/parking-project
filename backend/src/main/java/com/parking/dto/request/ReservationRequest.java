package com.parking.dto.request;

import jakarta.validation.constraints.NotNull;

/** {@code POST /api/reservations} body. The date is always tomorrow, decided by the server. */
public record ReservationRequest(@NotNull Integer spaceId, @NotNull Long vehicleId) {
}
