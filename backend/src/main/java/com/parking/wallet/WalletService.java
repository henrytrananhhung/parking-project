package com.parking.wallet;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;

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

    /** Deposits a dollar amount (max 2 decimal places) atomically and records it in the ledger. */
    @Transactional
    public long deposit(long userId, BigDecimal amount) {
        long cents = toValidDepositCents(amount);
        if (wallets.credit(userId, cents) == 0) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        ledger.save(WalletTransaction.deposit(userId, cents));
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
