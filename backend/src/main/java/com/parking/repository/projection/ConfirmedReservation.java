package com.parking.repository.projection;

/** A committed reservation of one date, with what the reservation cache needs (no entities). */
public record ConfirmedReservation(Long reservationId, Long spaceId, Long vehicleId, Long userId, String plate,
        Long priceCents) {
}
