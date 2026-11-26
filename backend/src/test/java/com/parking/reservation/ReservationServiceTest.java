package com.parking.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;
import com.parking.vehicle.Vehicle;
import com.parking.vehicle.VehicleRepository;
import com.parking.wallet.WalletRepository;
import com.parking.wallet.WalletTransaction;
import com.parking.wallet.WalletTransactionRepository;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final long USER = 7L;
    private static final long VEHICLE = 11L;
    private static final int SPACE = 42;
    private static final long PRICE = 1000L;
    // 2026-11-26 21:00 in Ho Chi Minh City: window open, target date 2026-11-27.
    private static final Instant OPEN_INSTANT = Instant.parse("2026-11-26T14:00:00Z");
    private static final LocalDate TOMORROW = LocalDate.of(2026, 11, 27);

    @Mock
    ReservationRepository reservations;
    @Mock
    VehicleRepository vehicles;
    @Mock
    WalletRepository wallets;
    @Mock
    WalletTransactionRepository ledger;
    @Mock
    ApplicationEventPublisher events;
    @Mock
    JdbcClient jdbc;

    private ReservationService serviceAt(Instant now) {
        ReservationWindow window = new ReservationWindow(Clock.fixed(now, ZoneOffset.UTC), "20:00", "Asia/Ho_Chi_Minh");
        return new ReservationService(reservations, vehicles, wallets, ledger, window, events, jdbc, PRICE);
    }

    private void ownsVehicle() {
        Vehicle vehicle = new Vehicle(USER, "51F12345");
        ReflectionTestUtils.setField(vehicle, "id", VEHICLE);
        when(vehicles.findByIdAndUserId(VEHICLE, USER)).thenReturn(Optional.of(vehicle));
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((BusinessException) e).code();
    }

    @Test
    void successfulReservationDeductsExactlyTenDollarsRecordsLedgerAndPublishesEvent() {
        ownsVehicle();
        when(reservations.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 99L);
            return r;
        });
        when(wallets.debitIfSufficient(USER, PRICE)).thenReturn(1);

        ReservationService.ReservationResult result = serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE);

        assertThat(result).isEqualTo(new ReservationService.ReservationResult(99L, SPACE, TOMORROW, "51F12345", PRICE));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReservationDate()).isEqualTo(TOMORROW);
        assertThat(saved.getValue().getSpaceId()).isEqualTo(SPACE);

        verify(wallets).debitIfSufficient(USER, 1000L);

        ArgumentCaptor<WalletTransaction> entry = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(ledger).save(entry.capture());
        assertThat(entry.getValue().getType()).isEqualTo(WalletTransaction.Type.RESERVATION);
        assertThat(entry.getValue().getAmountCents()).isEqualTo(-1000L);
        assertThat(entry.getValue().getReservationId()).isEqualTo(99L);

        verify(events).publishEvent(new ReservationConfirmed(99L, SPACE, VEHICLE, USER, TOMORROW));
    }

    @Test
    void rejectsBeforeOpeningTimeWithoutTouchingTheDatabase() {
        Instant beforeOpening = Instant.parse("2026-11-26T12:59:00Z"); // 19:59 local

        assertThatThrownBy(() -> serviceAt(beforeOpening).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.RESERVATION_CLOSED);
        verifyNoInteractions(vehicles, reservations, wallets, ledger, events);
    }

    @Test
    void rejectsVehicleNotOwnedByUser() {
        when(vehicles.findByIdAndUserId(VEHICLE, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verifyNoInteractions(reservations, wallets, ledger, events);
    }

    @Test
    void duplicateSpaceReservationIsRejectedWithoutCharging() {
        ownsVehicle();
        when(reservations.saveAndFlush(any(Reservation.class))).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_reservations_space_date\""));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_ALREADY_RESERVED);
        assertNothingCharged();
    }

    @Test
    void duplicateVehicleReservationIsRejectedWithoutCharging() {
        ownsVehicle();
        when(reservations.saveAndFlush(any(Reservation.class))).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_reservations_vehicle_date\""));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_ALREADY_RESERVED);
        assertNothingCharged();
    }

    @Test
    void unknownSpaceIsRejected() {
        ownsVehicle();
        when(reservations.saveAndFlush(any(Reservation.class))).thenThrow(new DataIntegrityViolationException(
                "insert or update on table \"reservations\" violates foreign key constraint \"reservations_space_id_fkey\""));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, 999))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_NOT_FOUND);
        assertNothingCharged();
    }

    @Test
    void insufficientBalanceIsRejectedAndNoLedgerOrEvent() {
        ownsVehicle();
        when(reservations.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wallets.debitIfSufficient(USER, PRICE)).thenReturn(0);

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        // The inserted reservation row is removed by the transaction rollback (verified in integration tests).
        verify(ledger, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void plateSuffixIsLastThreeCharacters() {
        assertThat(ReservationService.plateSuffix("51F12345")).isEqualTo("345");
        assertThat(ReservationService.plateSuffix("AB")).isEqualTo("AB");
    }

    private void assertNothingCharged() {
        verify(wallets, never()).debitIfSufficient(anyLong(), anyLong());
        verify(ledger, never()).save(any());
        verify(events, never()).publishEvent(any());
    }
}
