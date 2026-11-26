package com.parking.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    WalletRepository wallets;
    @Mock
    WalletTransactionRepository ledger;
    WalletService service;

    @BeforeEach
    void setUp() {
        service = new WalletService(wallets, ledger, 100_000L); // max $1,000 per deposit
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5", "10.001", "1000.01", "5000"})
    void rejectsInvalidDepositAmounts(String amount) {
        assertThatThrownBy(() -> service.deposit(1L, new BigDecimal(amount)))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.INVALID_AMOUNT);
        verify(wallets, never()).credit(anyLong(), anyLong());
        verify(ledger, never()).save(any());
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> service.deposit(1L, null))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void depositCreditsCentsRecordsLedgerAndReturnsNewBalance() {
        when(wallets.credit(1L, 1550L)).thenReturn(1);
        when(wallets.findBalanceCents(1L)).thenReturn(Optional.of(2550L));

        long balance = service.deposit(1L, new BigDecimal("15.50"));

        assertThat(balance).isEqualTo(2550L);
        ArgumentCaptor<WalletTransaction> entry = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(ledger).save(entry.capture());
        assertThat(entry.getValue().getType()).isEqualTo(WalletTransaction.Type.DEPOSIT);
        assertThat(entry.getValue().getAmountCents()).isEqualTo(1550L);
    }

    @Test
    void acceptsDepositExactlyAtTheLimit() {
        when(wallets.credit(1L, 100_000L)).thenReturn(1);
        when(wallets.findBalanceCents(1L)).thenReturn(Optional.of(100_000L));

        assertThat(service.deposit(1L, new BigDecimal("1000.00"))).isEqualTo(100_000L);
    }

    @Test
    void depositToMissingWalletFails() {
        when(wallets.credit(1L, 1000L)).thenReturn(0);

        assertThatThrownBy(() -> service.deposit(1L, BigDecimal.TEN))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND);
        verify(ledger, never()).save(any());
    }
}
