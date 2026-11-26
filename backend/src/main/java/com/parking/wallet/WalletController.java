package com.parking.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.parking.common.BusinessException;
import com.parking.common.CurrentUser;
import com.parking.common.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    public record WalletResponse(long balanceCents) {
    }

    public record DepositRequest(@NotNull BigDecimal amount) {
    }

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public WalletResponse get(@AuthenticationPrincipal Jwt jwt) {
        return new WalletResponse(walletService.balanceCents(CurrentUser.id(jwt)));
    }

    /** The client generates one key per deposit and reuses it when retrying that deposit. */
    @PostMapping("/deposits")
    public WalletResponse deposit(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return new WalletResponse(
                walletService.deposit(CurrentUser.id(jwt), request.amount(), parseIdempotencyKey(idempotencyKey)));
    }

    /** Parsed here, not by Spring's converter, so a rejected value never appears in the error response. */
    private static UUID parseIdempotencyKey(String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
    }
}
