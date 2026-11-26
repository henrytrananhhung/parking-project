package com.parking.mapper;

import com.parking.dto.response.MyReservationResponse;
import com.parking.dto.response.SpaceStatusResponse;
import com.parking.entity.ReservationEntity;
import com.parking.repository.projection.SpaceOccupancy;

public final class ReservationMapper {

    private ReservationMapper() {
    }

    public static SpaceStatusResponse toSpaceStatus(SpaceOccupancy row, long currentUserId) {
        boolean reserved = row.plate() != null;
        return new SpaceStatusResponse(Math.toIntExact(row.spaceId()), reserved,
                reserved ? plateSuffix(row.plate()) : null,
                reserved && row.userId() != null && row.userId() == currentUserId);
    }

    /** Reads only the space id (no space query) and the vehicle fetched with the reservation. */
    public static MyReservationResponse toMyReservation(ReservationEntity reservation) {
        return new MyReservationResponse(reservation.getId(), Math.toIntExact(reservation.getSpace().getId()),
                reservation.getReservationDate(), reservation.getVehicle().getPlate(), reservation.getPriceCents());
    }

    /** "51F12345" -> "345": the dashboard never shows a full plate. */
    static String plateSuffix(String plate) {
        return plate.length() <= 3 ? plate : plate.substring(plate.length() - 3);
    }
}
