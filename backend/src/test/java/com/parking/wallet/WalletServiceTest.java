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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final UUID KEY = UUID.fromString("6f1c2a52-8a4e-4c43-9d1f-2b7f3f0c9a11");

    @Mock
    WalletRepository wallets;
    @Mock
    WalletTransactionRepository ledger;
    WalletService service;

    @BeforeEach
    void setUp() {
        service = new WalletService(wallets, ledger, 100_000L); // max $1,000 per deposit
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((BusinessException) e).code();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5", "10.001", "1000.01", "5000"})
    void rejectsInvalidDepositAmounts(String amount) {
        assertThatThrownBy(() -> service.deposit(1L, new BigDecimal(amount), KEY))
                .extracting(WalletServiceTest::codeOf).isEqualTo(ErrorCode.INVALID_AMOUNT);
        verify(ledger, never()).insertDepositIfNew(anyLong(), anyLong(), any());
        verify(wallets, never()).credit(anyLong(), anyLong());
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> service.deposit(1L, null, KEY))
                .extracting(WalletServiceTest::codeOf).isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void newDepositRecordsLedgerEntryCreditsCentsAndReturnsNewBalance() {
        when(ledger.insertDepositIfNew(1L, 1550L, KEY)).thenReturn(Optional.of(31L));
        when(wallets.credit(1L, 1550L)).thenReturn(1);
        when(wallets.findBalanceCents(1L)).thenReturn(Optional.of(2550L));

        assertThat(service.deposit(1L, new BigDecimal("15.50"), KEY)).isEqualTo(2550L);
        verify(wallets).credit(1L, 1550L);
    }

    @Test
    void repeatedKeyReturnsTheBalanceWithoutCreditingAgain() {
        when(ledger.insertDepositIfNew(1L, 1550L, KEY)).thenReturn(Optional.empty());
        when(ledger.findDepositAmountCents(1L, KEY)).thenReturn(Optional.of(1550L));
        when(wallets.findBalanceCents(1L)).thenReturn(Optional.of(2550L));

        assertThat(service.deposit(1L, new BigDecimal("15.50"), KEY)).isEqualTo(2550L);
        verify(wallets, never()).credit(anyLong(), anyLong());
    }

    @Test
    void keyReusedForADifferentAmountIsRejectedWithoutCrediting() {
        when(ledger.insertDepositIfNew(1L, 2000L, KEY)).thenReturn(Optional.empty());
        when(ledger.findDepositAmountCents(1L, KEY)).thenReturn(Optional.of(1550L));

        assertThatThrownBy(() -> service.deposit(1L, new BigDecimal("20"), KEY))
                .extracting(WalletServiceTest::codeOf).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        verify(wallets, never()).credit(anyLong(), anyLong());
    }

    @Test
    void acceptsDepositExactlyAtTheLimit() {
        when(ledger.insertDepositIfNew(1L, 100_000L, KEY)).thenReturn(Optional.of(31L));
        when(wallets.credit(1L, 100_000L)).thenReturn(1);
        when(wallets.findBalanceCents(1L)).thenReturn(Optional.of(100_000L));

        assertThat(service.deposit(1L, new BigDecimal("1000.00"), KEY)).isEqualTo(100_000L);
    }

    @Test
    void depositToMissingWalletFails() {
        // The ledger entry inserted first is removed by the transaction rollback.
        when(ledger.insertDepositIfNew(1L, 1000L, KEY)).thenReturn(Optional.of(31L));
        when(wallets.credit(1L, 1000L)).thenReturn(0);

        assertThatThrownBy(() -> service.deposit(1L, BigDecimal.TEN, KEY))
                .extracting(WalletServiceTest::codeOf).isEqualTo(ErrorCode.WALLET_NOT_FOUND);
    }
}
