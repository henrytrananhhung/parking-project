package com.parking.wallet;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.parking.common.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    public record WalletResponse(long balanceCents) {
    }

    public record DepositRequest(@NotNull BigDecimal amount) {
    }

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public WalletResponse get(@AuthenticationPrincipal Jwt jwt) {
        return new WalletResponse(walletService.balanceCents(CurrentUser.id(jwt)));
    }

    @PostMapping("/deposits")
    public WalletResponse deposit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DepositRequest request) {
        return new WalletResponse(walletService.deposit(CurrentUser.id(jwt), request.amount()));
    }
}
