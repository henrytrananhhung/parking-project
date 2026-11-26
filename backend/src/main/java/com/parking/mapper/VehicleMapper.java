package com.parking.mapper;

import com.parking.dto.response.VehicleResponse;
import com.parking.entity.VehicleEntity;

public final class VehicleMapper {

    private VehicleMapper() {
    }

    public static VehicleResponse toResponse(VehicleEntity vehicle) {
        return new VehicleResponse(vehicle.getId(), vehicle.getPlate());
    }
}
