package com.parking.dto.response;

/** Registration and login result. The token is never logged. */
public record AuthResponse(String token, String username) {

    @Override
    public String toString() {
        return "AuthResponse{username=" + username + "}";
    }
}
