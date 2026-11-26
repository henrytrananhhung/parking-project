package com.parking.repository.projection;

/** A vehicle's owner and plate (no entities): preloads the reservation cache's owner map. */
public record VehicleOwnerRow(Long vehicleId, Long userId, String plate) {
}
