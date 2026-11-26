package com.parking.dto.request;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/login} body. */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {

    @Override
    public String toString() {
        return "LoginRequest{username=" + username + "}";
    }
}
