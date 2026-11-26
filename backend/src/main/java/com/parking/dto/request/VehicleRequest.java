package com.parking.dto.request;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/vehicles} body. */
public record VehicleRequest(@NotBlank String plate) {
}
