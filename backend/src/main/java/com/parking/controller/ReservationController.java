package com.parking.controller;

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

import com.parking.dto.request.ReservationRequest;
import com.parking.dto.response.DashboardResponse;
import com.parking.dto.response.MyReservationResponse;
import com.parking.dto.response.ReservationResponse;
import com.parking.security.CurrentUser;
import com.parking.service.ReservationService;

import jakarta.validation.Valid;

/**
 * Non-transactional entry point for reservations. Future request filtering
 * (rate limiting, duplicate-request rejection, Redis fast-rejects) belongs here, before the DB transaction.
 */
@RestController
@RequestMapping("/api")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/spaces")
    public DashboardResponse spaces(@AuthenticationPrincipal Jwt jwt) {
        return reservationService.dashboard(CurrentUser.id(jwt));
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReservationRequest request) {
        return reservationService.reserve(CurrentUser.id(jwt), request.vehicleId(), request.spaceId());
    }

    @GetMapping("/reservations/me")
    public List<MyReservationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return reservationService.myReservations(CurrentUser.id(jwt));
    }
}
