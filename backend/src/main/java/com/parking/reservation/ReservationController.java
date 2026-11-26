package com.parking.reservation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.parking.common.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Non-transactional entry point for reservations. Future request filtering
 * (rate limiting, duplicate-request rejection, Redis fast-rejects) belongs here, before the DB transaction.
 */
@RestController
@RequestMapping("/api")
public class ReservationController {

    public record ReserveRequest(@NotNull Integer spaceId, @NotNull Long vehicleId) {
    }

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/spaces")
    public ReservationService.Dashboard spaces(@AuthenticationPrincipal Jwt jwt) {
        return reservationService.dashboard(CurrentUser.id(jwt));
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationService.ReservationResult reserve(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReserveRequest request) {
        return reservationService.reserve(CurrentUser.id(jwt), request.vehicleId(), request.spaceId());
    }

    @GetMapping("/reservations/me")
    public List<ReservationService.MyReservation> mine(@AuthenticationPrincipal Jwt jwt) {
        return reservationService.myReservations(CurrentUser.id(jwt));
    }
}
