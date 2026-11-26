package com.parking.vehicle;

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
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    public record VehicleRequest(@NotBlank String plate) {
    }

    public record VehicleResponse(long id, String plate) {
        static VehicleResponse from(Vehicle v) {
            return new VehicleResponse(v.getId(), v.getPlate());
        }
    }

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    public List<VehicleResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return vehicleService.list(CurrentUser.id(jwt)).stream().map(VehicleResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleResponse register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VehicleRequest request) {
        return VehicleResponse.from(vehicleService.register(CurrentUser.id(jwt), request.plate()));
    }
}
