package com.parking.common;

import org.springframework.http.HttpStatus;

/** Stable, client-facing business error codes (returned in ProblemDetail.code). */
public enum ErrorCode {
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password."),
    INVALID_PLATE(HttpStatus.BAD_REQUEST, "License plate must contain 4-12 letters or digits."),
    PLATE_TAKEN(HttpStatus.CONFLICT, "This license plate is already registered."),
    VEHICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Vehicle not found."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "Deposit must be greater than 0, at most 2 decimal places, and within the deposit limit."),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found."),
    RESERVATION_CLOSED(HttpStatus.CONFLICT, "Reservations for tomorrow are not open yet."),
    SPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "Parking space not found."),
    SPACE_ALREADY_RESERVED(HttpStatus.CONFLICT, "This parking space is already reserved for that date."),
    VEHICLE_ALREADY_RESERVED(HttpStatus.CONFLICT, "This vehicle already has a reservation for that date."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "Insufficient balance. A reservation costs $10.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
