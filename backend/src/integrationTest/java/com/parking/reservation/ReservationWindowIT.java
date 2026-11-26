package com.parking.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.parking.IntegrationTestSupport;
import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;

/** The real configuration (opens 20:00 Asia/Ho_Chi_Minh, closes at midnight), driven by the test clock. */
class ReservationWindowIT extends IntegrationTestSupport {

    @Autowired
    ReservationService reservationService;

    @Test
    void closedOneMillisecondBeforeEightPmAndOpenAtEightPmForTomorrow() {
        long userId = newUser("early", BigDecimal.TEN);
        long vehicleId = newVehicle(userId, "EARLY01");
        LocalDate today = businessToday();

        setBusinessTime(today.atTime(19, 59, 59, 999_000_000));
        assertThatThrownBy(() -> reservationService.reserve(userId, vehicleId, 1))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.RESERVATION_CLOSED);

        setBusinessTime(today.atTime(20, 0));
        assertThat(reservationService.reserve(userId, vehicleId, 1).date()).isEqualTo(today.plusDays(1));
    }

    @Test
    void closedAfterMidnightUntilEightPm() {
        long userId = newUser("nightowl", BigDecimal.TEN);
        long vehicleId = newVehicle(userId, "OWL0001");

        setBusinessTime(businessToday().plusDays(1).atTime(0, 30));

        assertThatThrownBy(() -> reservationService.reserve(userId, vehicleId, 1))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.RESERVATION_CLOSED);
        assertThat(balanceCents(userId)).isEqualTo(1000L);
    }

    @Test
    void sameVehicleCanReserveAgainForTheFollowingDay() {
        // "One space per vehicle per DAY": the limit resets with the date.
        long userId = newUser("daily", new BigDecimal("20"));
        long vehicleId = newVehicle(userId, "DAILY01");
        ReservationService.ReservationResult first = reservationService.reserve(userId, vehicleId, 1);

        clock.advance(Duration.ofDays(1));
        ReservationService.ReservationResult next = reservationService.reserve(userId, vehicleId, 1);

        assertThat(next.date()).isEqualTo(first.date().plusDays(1));
        assertThat(balanceCents(userId)).isZero();
    }
}
