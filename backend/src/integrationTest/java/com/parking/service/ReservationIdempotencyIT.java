package com.parking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.parking.IntegrationTestSupport;
import com.parking.dto.response.ReservationResponse;
import com.parking.exception.BusinessException;
import com.parking.exception.ErrorCode;

/**
 * Clients retry at 8 PM (timeouts, double clicks, 5xx): repeating a request that already succeeded must return
 * the original reservation and never charge twice.
 */
class ReservationIdempotencyIT extends IntegrationTestSupport {

    @Autowired
    ReservationService reservationService;

    @Test
    void retryAfterSuccessReturnsTheOriginalReservationAndChargesOnce() {
        long userId = newUser("retry", new BigDecimal("50"));
        long vehicleId = newVehicle(userId, "RETRY01");

        ReservationResponse first = reservationService.reserve(userId, vehicleId, 3);
        ReservationResponse retry = reservationService.reserve(userId, vehicleId, 3);

        assertThat(retry).isEqualTo(first);
        assertThat(balanceCents(userId)).isEqualTo(4000L);
        assertThat(count("SELECT count(*) FROM reservations WHERE vehicle_id = ?", vehicleId)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateSubmitsAllReturnTheSameReservationAndChargeOnce() throws Exception {
        long userId = newUser("doubleclick", new BigDecimal("50"));
        long vehicleId = newVehicle(userId, "DOUBLE01");
        List<Callable<ReservationResponse>> submits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            submits.add(() -> reservationService.reserve(userId, vehicleId, 4));
        }

        List<Outcome<ReservationResponse>> outcomes = runConcurrently(submits);

        assertThat(outcomes).allMatch(Outcome::success);
        assertThat(outcomes.stream().map(o -> o.value().id()).distinct()).hasSize(1);
        assertThat(balanceCents(userId)).isEqualTo(4000L);
    }

    @Test
    void repeatForADifferentSpaceIsRejectedAndKeepsTheOriginalReservation() {
        long userId = newUser("switcher", new BigDecimal("50"));
        long vehicleId = newVehicle(userId, "SWITCH01");
        reservationService.reserve(userId, vehicleId, 3);

        assertThatThrownBy(() -> reservationService.reserve(userId, vehicleId, 4))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.VEHICLE_ALREADY_RESERVED);
        assertThat(count("SELECT count(*) FROM reservations WHERE space_id = 4")).isZero();
        assertThat(balanceCents(userId)).isEqualTo(4000L);
    }
}
