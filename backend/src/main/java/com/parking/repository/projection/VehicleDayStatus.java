package com.parking.repository.projection;

/**
 * A vehicle (owner, plate) and its reservation for one date, if any: everything a reservation request for an
 * already-reserved space needs to be answered exactly. The {@code reservation...} fields and {@code priceCents} are
 * {@code null} when the vehicle holds no reservation that day.
 */
public record VehicleDayStatus(Long ownerId, String plate, Long reservationId, Long reservationSpaceId,
        Long reservationUserId, Long priceCents) {

    public boolean hasReservation() {
        return reservationId != null;
    }
}
