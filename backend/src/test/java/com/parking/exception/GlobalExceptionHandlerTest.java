package com.parking.exception;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Real Spring MVC exception resolution (standalone MockMvc), no application context or database. */
class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        RuntimeException next;

        @GetMapping("/boom")
        void boom() {
            throw next;
        }
    }

    private final ThrowingController controller = new ThrowingController();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    static Stream<RuntimeException> temporaryFailures() {
        return Stream.of(
                // Hikari pool exhausted when the transaction begins (the 8 PM peak).
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available")),
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
                new CannotAcquireLockException("lock timeout"),
                new QueryTimeoutException("statement timeout"),
                // Bounded waits of the reservation coordination expired (load shedding at the peak).
                new CapacityExceededException("space lock"),
                new CapacityExceededException("reservation permit"),
                // Redis unreachable or too slow: reservations fail closed.
                new RedisConnectionException("Unable to connect to Redis server"),
                new RedisTimeoutException("Command execution timeout"));
    }

    @ParameterizedTest
    @MethodSource("temporaryFailures")
    void temporaryFailureIs503WithRetryAfter(RuntimeException failure) throws Exception {
        controller.next = failure;

        mvc.perform(get("/boom"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, Integer.toString(GlobalExceptionHandler.RETRY_AFTER_SECONDS)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(ErrorCode.TEMPORARILY_UNAVAILABLE.message()));
    }

    @Test
    void permanentDatabaseErrorIsNotDisguisedAsTemporary() {
        controller.next = new DataIntegrityViolationException("unexpected constraint");

        // Unhandled by the advice: it propagates and becomes a 500 in the real container.
        assertThatThrownBy(() -> mvc.perform(get("/boom")))
                .hasRootCauseInstanceOf(DataIntegrityViolationException.class);
    }
}
