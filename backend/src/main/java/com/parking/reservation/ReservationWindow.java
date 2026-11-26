package com.parking.reservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;

/**
 * Reservations for tomorrow open daily at {@code open-time} (business timezone) and stay open until midnight.
 * The target date is always computed on the server, never taken from the client.
 * Demo deployments set RESERVATION_OPEN_TIME=00:00 (same rules, open all day) - there is no API override.
 */
@Component
public class ReservationWindow {

    private final Clock clock;
    private final LocalTime openTime;
    private final ZoneId zone;

    public ReservationWindow(Clock clock,
            @Value("${parking.reservation.open-time}") String openTime,
            @Value("${parking.reservation.timezone}") String timezone) {
        this.clock = clock;
        this.openTime = LocalTime.parse(openTime);
        this.zone = ZoneId.of(timezone);
    }

    public boolean isOpen() {
        return !now().toLocalTime().isBefore(openTime);
    }

    public LocalDate targetDate() {
        return now().toLocalDate().plusDays(1);
    }

    /** @return the date being reserved, or throws RESERVATION_CLOSED before the opening time. */
    public LocalDate requireOpen() {
        ZonedDateTime now = now();
        if (now.toLocalTime().isBefore(openTime)) {
            throw new BusinessException(ErrorCode.RESERVATION_CLOSED);
        }
        return now.toLocalDate().plusDays(1);
    }

    public LocalTime openTime() {
        return openTime;
    }

    public ZoneId zone() {
        return zone;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock.withZone(zone));
    }
}
