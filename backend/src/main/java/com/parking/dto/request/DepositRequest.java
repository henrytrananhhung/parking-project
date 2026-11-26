package com.parking.dto.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/** {@code POST /api/wallet/deposits} body: a dollar amount. */
public record DepositRequest(@NotNull BigDecimal amount) {
}
