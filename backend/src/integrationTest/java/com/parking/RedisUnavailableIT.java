package com.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.parking.service.AuthService;
import com.parking.service.ReservationService;
import com.parking.service.ReservationWindow;
import com.parking.service.VehicleService;
import com.parking.service.WalletService;

/**
 * Redis unreachable for the whole run (nothing listens on its address): the application still starts, reservations
 * fail closed with 503 before touching PostgreSQL, and deposits, the dashboard and everything else keep working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "parking.redis.address=redis://127.0.0.1:1",
        "parking.redis.connect-timeout=300ms",
        "parking.redis.timeout=300ms",
        "parking.redis.retry-attempts=0" })
@ActiveProfiles("test")
@Import({ RedisUnavailableIT.PostgresOnly.class, IntegrationTestConfiguration.class })
class RedisUnavailableIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresOnly {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        }
    }

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    AuthService authService;
    @Autowired
    VehicleService vehicleService;
    @Autowired
    WalletService walletService;
    @Autowired
    ReservationService reservationService;
    @Autowired
    ReservationWindow window;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void windowOpen() {
        jdbc.sql("TRUNCATE wallet_transactions, reservations, vehicles, wallets, users RESTART IDENTITY CASCADE").update();
        clock.set(LocalDate.now(window.zone()).atTime(21, 0).atZone(window.zone()).toInstant());
    }

    @Test
    void reservationsFailClosedWithoutTouchingPostgreSqlWhileDepositsKeepWorking() {
        long userId = authService.createAccount("offline", "password123");
        long vehicleId = vehicleService.register(userId, "OFFLINE1").id();
        UUID depositKey = UUID.randomUUID();

        assertThatThrownBy(() -> reservationService.reserve(userId, vehicleId, 1)).isInstanceOf(RedisException.class);
        assertThat(walletService.deposit(userId, BigDecimal.TEN, depositKey)).as("deposits need no Redis").isEqualTo(1000L);
        assertThat(walletService.deposit(userId, BigDecimal.TEN, depositKey)).as("replay, applied once").isEqualTo(1000L);

        assertThat(jdbc.sql("SELECT count(*) FROM reservations").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM wallet_transactions WHERE type = 'RESERVATION'").query(Long.class)
                .single()).as("nothing charged").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM wallet_transactions WHERE type = 'DEPOSIT'").query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(walletService.balanceCents(userId)).isEqualTo(1000L);
    }

    @Test
    void apiAnswers503ForReservationsButStillServesDepositsAndTheDashboard() {
        RestClient http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                }).build();
        String token = (String) http.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "offline2", "password", "password123")).retrieve().body(JSON).get("token");
        Object vehicleId = http.post().uri("/api/vehicles").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("plate", "OFFLINE2")).retrieve().body(JSON).get("id");

        ResponseEntity<Map<String, Object>> reservation = http.post().uri("/api/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("spaceId", 1, "vehicleId", vehicleId)).retrieve().toEntity(JSON);
        ResponseEntity<Map<String, Object>> deposit = http.post().uri("/api/wallet/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", 25)).retrieve().toEntity(JSON);
        ResponseEntity<Map<String, Object>> dashboard = http.get().uri("/api/spaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().toEntity(JSON);

        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(reservation.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(reservation.getBody()).containsEntry("code", "TEMPORARILY_UNAVAILABLE");
        assertThat(deposit.getStatusCode()).as("deposit without Redis").isEqualTo(HttpStatus.OK);
        assertThat(deposit.getBody()).containsEntry("balanceCents", 2500);
        assertThat(dashboard.getStatusCode()).as("dashboard from PostgreSQL").isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) dashboard.getBody().get("spaces")).hasSize(80);
    }
}
