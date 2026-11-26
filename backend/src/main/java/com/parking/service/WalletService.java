package com.parking.service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.parking.exception.BusinessException;
import com.parking.exception.ErrorCode;
import com.parking.repository.WalletRepository;
import com.parking.repository.WalletTransactionRepository;

@Service
public class WalletService {

    private final WalletRepository wallets;
    private final WalletTransactionRepository ledger;
    private final long maxDepositCents;

    public WalletService(WalletRepository wallets, WalletTransactionRepository ledger,
            @Value("${parking.wallet.max-deposit-cents}") long maxDepositCents) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.maxDepositCents = maxDepositCents;
    }

    @Transactional(readOnly = true)
    public long balanceCents(long userId) {
        return wallets.findBalanceCents(userId).orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));
    }

    /**
     * Deposits a dollar amount (max 2 decimal places) atomically and records it in the ledger.
     * Idempotent per {@code idempotencyKey}: repeating a deposit that was already applied (client retry,
     * double submit) applies nothing and returns the current balance. Reusing a key for a different amount
     * is rejected.
     *
     * @return the balance in cents after the deposit
     */
    @Transactional
    public long deposit(long userId, BigDecimal amount, UUID idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        long cents = toValidDepositCents(amount);
        // The ledger entry claims the key first; the wallet is credited only if the entry is new.
        if (ledger.insertDepositIfNew(userId, cents, idempotencyKey).isEmpty()) {
            long recordedCents = ledger.findDepositAmountCents(userId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key conflict without a deposit entry"));
            if (recordedCents != cents) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return balanceCents(userId);
        }
        if (wallets.credit(userId, cents) == 0) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        return balanceCents(userId);
    }

    long toValidDepositCents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
        BigDecimal cents = amount.movePointRight(2);
        if (cents.compareTo(BigDecimal.valueOf(maxDepositCents)) > 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
        return cents.longValueExact();
    }
}
