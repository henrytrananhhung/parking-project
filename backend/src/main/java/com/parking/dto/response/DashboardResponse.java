package com.parking.dto.response;

import java.time.LocalDate;
import java.util.List;

/** {@code GET /api/spaces}: every space for the target date, plus the reservation window. */
public record DashboardResponse(LocalDate date, boolean open, String opensAt, String timezone,
        List<SpaceStatusResponse> spaces) {
}
