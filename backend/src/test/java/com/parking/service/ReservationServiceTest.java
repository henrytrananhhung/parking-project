package com.parking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.dto.response.ReservationResponse;
import com.parking.entity.ReservationEntity;
import com.parking.entity.VehicleEntity;
import com.parking.entity.WalletTransactionEntity;
import com.parking.event.ReservationConfirmedEvent;
import com.parking.exception.BusinessException;
import com.parking.exception.ErrorCode;
import com.parking.repository.ReservationRepository;
import com.parking.repository.VehicleRepository;
import com.parking.repository.WalletRepository;
import com.parking.repository.WalletTransactionRepository;

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

    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private ReservationService serviceAt(Instant now) {
        ReservationWindow window = new ReservationWindow(Clock.fixed(now, ZoneOffset.UTC), "20:00", "Asia/Ho_Chi_Minh");
        return new ReservationService(reservations, vehicles, wallets, ledger, window, events, jdbc,
                new TransactionTemplate(txManager), PRICE);
    }

    private void ownsVehicle() {
        VehicleEntity vehicle = new VehicleEntity(USER, "51F12345");
        ReflectionTestUtils.setField(vehicle, "id", VEHICLE);
        when(vehicles.findByIdAndUserId(VEHICLE, USER)).thenReturn(Optional.of(vehicle));
    }

    private void spaceIsFree(long newReservationId) {
        when(reservations.insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE)).thenReturn(Optional.of(newReservationId));
    }

    /** The insert hits a committed conflict; {@code vehiclesReservation} is what this vehicle already holds. */
    private void conflictWhileVehicleHolds(Optional<ReservationEntity> vehiclesReservation) {
        when(reservations.insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE)).thenReturn(Optional.empty());
        when(reservations.findByVehicleIdAndReservationDate(VEHICLE, TOMORROW)).thenReturn(vehiclesReservation);
    }

    private static ReservationEntity existing(long id, int spaceId, long userId) {
        ReservationEntity r = new ReservationEntity(spaceId, VEHICLE, userId, TOMORROW, PRICE);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((BusinessException) e).code();
    }

    @Test
    void successfulReservationDeductsExactlyTenDollarsRecordsLedgerAndPublishesEvent() {
        ownsVehicle();
        spaceIsFree(99L);
        when(wallets.debitIfSufficient(USER, PRICE)).thenReturn(1);

        ReservationResponse result = serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE);

        assertThat(result).isEqualTo(new ReservationResponse(99L, SPACE, TOMORROW, "51F12345", PRICE));
        verify(reservations).insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE);
        verify(wallets).debitIfSufficient(USER, 1000L);

        ArgumentCaptor<WalletTransactionEntity> entry = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(ledger).save(entry.capture());
        assertThat(entry.getValue().getType()).isEqualTo(WalletTransactionEntity.Type.RESERVATION);
        assertThat(entry.getValue().getAmountCents()).isEqualTo(-1000L);
        assertThat(entry.getValue().getReservationId()).isEqualTo(99L);

        verify(events).publishEvent(new ReservationConfirmedEvent(99L, SPACE, VEHICLE, USER, TOMORROW));
    }

    @Test
    void repeatOfASuccessfulRequestReturnsTheOriginalReservationWithoutChargingAgain() {
        ownsVehicle();
        conflictWhileVehicleHolds(Optional.of(existing(99L, SPACE, USER)));

        ReservationResponse result = serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE);

        assertThat(result).isEqualTo(new ReservationResponse(99L, SPACE, TOMORROW, "51F12345", PRICE));
        assertNothingCharged();
    }

    @Test
    void rejectsBeforeOpeningTimeWithoutTouchingTheDatabase() {
        Instant beforeOpening = Instant.parse("2026-11-26T12:59:00Z"); // 19:59 local

        assertThatThrownBy(() -> serviceAt(beforeOpening).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.RESERVATION_CLOSED);
        // No transaction was even started, so no pool connection was taken.
        verifyNoInteractions(txManager, vehicles, reservations, wallets, ledger, events);
    }

    @Test
    void rejectsVehicleNotOwnedByUser() {
        when(vehicles.findByIdAndUserId(VEHICLE, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verifyNoInteractions(reservations, wallets, ledger, events);
    }

    @Test
    void spaceTakenByAnotherVehicleIsRejectedWithoutCharging() {
        ownsVehicle();
        conflictWhileVehicleHolds(Optional.empty());

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_ALREADY_RESERVED);
        assertNothingCharged();
    }

    @Test
    void vehicleHoldingAnotherSpaceIsRejectedWithoutCharging() {
        ownsVehicle();
        conflictWhileVehicleHolds(Optional.of(existing(55L, SPACE + 1, USER)));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_ALREADY_RESERVED);
        assertNothingCharged();
    }

    @Test
    void reservationMadeByAnotherUserIsNeverReplayed() {
        ownsVehicle();
        conflictWhileVehicleHolds(Optional.of(existing(99L, SPACE, USER + 1)));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_ALREADY_RESERVED);
        assertNothingCharged();
    }

    @Test
    void unknownSpaceIsRejected() {
        ownsVehicle();
        when(reservations.insertIfFree(999, VEHICLE, USER, TOMORROW, PRICE)).thenThrow(new DataIntegrityViolationException(
                "insert or update on table \"reservations\" violates foreign key constraint \"reservations_space_id_fkey\""));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, 999))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_NOT_FOUND);
        assertNothingCharged();
    }

    @Test
    void unexpectedIntegrityViolationIsNotDisguisedAsABusinessError() {
        ownsVehicle();
        DataIntegrityViolationException unexpected = new DataIntegrityViolationException("some other constraint");
        when(reservations.insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE)).thenThrow(unexpected);

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE)).isSameAs(unexpected);
        assertNothingCharged();
    }

    @Test
    void insufficientBalanceIsRejectedAndNoLedgerOrEvent() {
        ownsVehicle();
        spaceIsFree(99L);
        when(wallets.debitIfSufficient(USER, PRICE)).thenReturn(0);

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        // The inserted reservation row is removed by the transaction rollback (verified in integration tests).
        verify(ledger, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    private void assertNothingCharged() {
        verify(wallets, never()).debitIfSufficient(anyLong(), anyLong());
        verify(ledger, never()).save(any());
        verify(events, never()).publishEvent(any());
    }
}
