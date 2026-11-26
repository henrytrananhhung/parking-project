package com.parking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /api/auth/register} body. */
public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,49}$",
                message = "must be 3-50 characters: letters, digits, '.', '_' or '-'") String username,
        // BCrypt only uses the first 72 bytes.
        @NotBlank @Size(min = 8, max = 72) String password) {

    @Override
    public String toString() {
        return "RegisterRequest{username=" + username + "}";
    }
}
