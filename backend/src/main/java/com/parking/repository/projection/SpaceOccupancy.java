package com.parking.repository.projection;

/**
 * One dashboard row: a parking space and, if it is reserved for the requested date, the vehicle plate and the
 * owner. {@code plate} and {@code userId} are {@code null} for a free space.
 */
public record SpaceOccupancy(Long spaceId, String plate, Long userId) {
}
