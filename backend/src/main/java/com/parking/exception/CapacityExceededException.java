package com.parking.exception;

/**
 * A bounded wait expired: a space lock or a database permit was not granted in time. A capacity condition, not a
 * business outcome: the request did not touch the database, so the client can safely retry (503 + Retry-After).
 */
public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(String resource) {
        super(resource + " not acquired within its wait time");
    }
}
