package com.parking.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;

/** Business timezone Asia/Ho_Chi_Minh = UTC+7 (no DST). Time comes from Clock.fixed, not a mock. */
class ReservationWindowTest {

    private static ReservationWindow windowAtUtc(String utcInstant, String openTime) {
        return new ReservationWindow(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC), openTime, "Asia/Ho_Chi_Minh");
    }

    @Test
    void closedOneMinuteBeforeOpening() {
        ReservationWindow window = windowAtUtc("2026-11-26T12:59:00Z", "20:00"); // 19:59 local

        assertThat(window.isOpen()).isFalse();
        assertThatThrownBy(window::requireOpen)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.RESERVATION_CLOSED);
    }

    @Test
    void opensExactlyAtOpeningTimeForTomorrow() {
        ReservationWindow window = windowAtUtc("2026-11-26T13:00:00Z", "20:00"); // 20:00 local

        assertThat(window.isOpen()).isTrue();
        assertThat(window.requireOpen()).isEqualTo(LocalDate.of(2026, 11, 27));
    }

    @Test
    void staysOpenUntilMidnightLocal() {
        ReservationWindow window = windowAtUtc("2026-11-26T16:59:59Z", "20:00"); // 23:59:59 local

        assertThat(window.requireOpen()).isEqualTo(LocalDate.of(2026, 11, 27));
    }

    @Test
    void targetDateUsesBusinessTimezoneNotUtc() {
        // 17:30 UTC on Nov 26 is already 00:30 on Nov 27 in Ho Chi Minh City -> target is Nov 28, window closed.
        ReservationWindow window = windowAtUtc("2026-11-26T17:30:00Z", "20:00");

        assertThat(window.targetDate()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(window.isOpen()).isFalse();
    }

    @Test
    void demoOpeningTimeMidnightIsOpenAllDayWithSameTargetDateRule() {
        ReservationWindow window = windowAtUtc("2026-11-26T02:00:00Z", "00:00"); // 09:00 local

        assertThat(window.isOpen()).isTrue();
        assertThat(window.requireOpen()).isEqualTo(LocalDate.of(2026, 11, 27));
    }
}
