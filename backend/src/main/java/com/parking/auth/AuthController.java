package com.parking.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

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

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {

        @Override
        public String toString() {
            return "LoginRequest{username=" + username + "}";
        }
    }

    public record AuthResponse(String token, String username) {

        @Override
        public String toString() {
            return "AuthResponse{username=" + username + "}";
        }
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        AuthService.AuthResult result = authService.register(request.username(), request.password());
        return new AuthResponse(result.token(), result.username());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.AuthResult result = authService.login(request.username(), request.password());
        return new AuthResponse(result.token(), result.username());
    }
}
