package com.parking.mapper;

import com.parking.dto.response.SpaceStatusResponse;

public final class ReservationMapper {

    private ReservationMapper() {
    }

    /**
     * @param plate   plate of the vehicle holding the space, or {@code null} if the space is free
     * @param ownerId user holding the space, or {@code null} if the space is free
     */
    public static SpaceStatusResponse toSpaceStatus(long spaceId, String plate, Long ownerId, long currentUserId) {
        boolean reserved = plate != null;
        return new SpaceStatusResponse(Math.toIntExact(spaceId), reserved, reserved ? plateSuffix(plate) : null,
                reserved && ownerId != null && ownerId == currentUserId);
    }

    /** "51F12345" -> "345": the dashboard never shows a full plate. */
    static String plateSuffix(String plate) {
        return plate.length() <= 3 ? plate : plate.substring(plate.length() - 3);
    }
}
