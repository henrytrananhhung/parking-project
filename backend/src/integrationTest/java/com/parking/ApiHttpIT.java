package com.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Real HTTP through the security filter chain: the status codes, error codes and headers that clients use to
 * decide whether (and how) to retry.
 */
class ApiHttpIT extends IntegrationTestSupport {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private record Driver(String token, Object vehicleId) {
    }

    @Value("${local.server.port}")
    int port;

    private RestClient http;

    @BeforeEach
    void httpClient() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // Never throw: each test asserts the status itself.
                })
                .build();
    }

    @Test
    void apiWithoutATokenIs401() {
        assertThat(get("/api/wallet", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void depositRetriedWithTheSameKeyIsAppliedOnce() {
        String token = register("saver");
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map<String, Object>> first = deposit(token, 15, key);
        ResponseEntity<Map<String, Object>> retry = deposit(token, 15, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).containsEntry("balanceCents", 1500);
        assertThat(balanceOf(token)).isEqualTo(1500);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "not-a-uuid")
    void depositWithoutAValidKeyIs400AndDoesNotEchoTheValue(String key) {
        String token = register("keyless");

        ResponseEntity<Map<String, Object>> response = deposit(token, 15, key);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_INVALID");
        assertThat(response.getBody().toString()).doesNotContain("not-a-uuid");
        assertThat(balanceOf(token)).isZero();
    }

    @Test
    void depositKeyReusedForAnotherAmountIs422() {
        String token = register("reuser");
        String key = UUID.randomUUID().toString();
        deposit(token, 15, key);

        ResponseEntity<Map<String, Object>> reused = deposit(token, 20, key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reused.getBody()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(balanceOf(token)).isEqualTo(1500);
    }

    @Test
    void reservationRetriedOverHttpGetsTheSame201AndOneCharge() {
        Driver driver = driverWithTenDollars("retrier", "51F12345");

        ResponseEntity<Map<String, Object>> first = reserve(driver, 5);
        ResponseEntity<Map<String, Object>> retry = reserve(driver, 5);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(balanceOf(driver.token())).isZero();
    }

    @Test
    void takenSpaceIs409WithItsCode() {
        reserve(driverWithTenDollars("alice", "ALICE01"), 5);
        Driver bob = driverWithTenDollars("bob", "BOB0001");

        ResponseEntity<Map<String, Object>> response = reserve(bob, 5);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "SPACE_ALREADY_RESERVED");
        assertThat(balanceOf(bob.token())).isEqualTo(1000);
    }

    @Test
    void invalidReservationRequestIs400ValidationFailed() {
        String token = register("sloppy");

        ResponseEntity<Map<String, Object>> response = post("/api/reservations", token, Map.of(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
    }

    private String register(String username) {
        ResponseEntity<Map<String, Object>> response =
                post("/api/auth/register", null, Map.of("username", username, "password", "password123"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("token");
    }

    private Driver driverWithTenDollars(String username, String plate) {
        String token = register(username);
        deposit(token, 10, UUID.randomUUID().toString());
        return new Driver(token, post("/api/vehicles", token, Map.of("plate", plate), null).getBody().get("id"));
    }

    private ResponseEntity<Map<String, Object>> deposit(String token, int dollars, String idempotencyKey) {
        return post("/api/wallet/deposits", token, Map.of("amount", dollars), idempotencyKey);
    }

    private ResponseEntity<Map<String, Object>> reserve(Driver driver, int spaceId) {
        return post("/api/reservations", driver.token(), Map.of("spaceId", spaceId, "vehicleId", driver.vehicleId()), null);
    }

    private long balanceOf(String token) {
        return ((Number) get("/api/wallet", token).getBody().get("balanceCents")).longValue();
    }

    private ResponseEntity<Map<String, Object>> post(String path, String token, Object body, String idempotencyKey) {
        return http.post().uri(path)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                    if (idempotencyKey != null) {
                        headers.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(JSON);
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return http.get().uri(path)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .retrieve()
                .toEntity(JSON);
    }
}
