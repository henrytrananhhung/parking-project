package com.parking.dto.response;

/** One parking space on the dashboard: only the last 3 plate characters of a reserved space are shown. */
public record SpaceStatusResponse(int id, boolean reserved, String plateSuffix, boolean mine) {
}
