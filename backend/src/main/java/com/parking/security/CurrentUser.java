package com.parking.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated user's id is the JWT subject. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
