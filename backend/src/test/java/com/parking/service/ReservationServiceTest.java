package com.parking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.client.RedisConnectionException;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.parking.concurrency.DbPermits;
import com.parking.concurrency.ReservationStateCache;
import com.parking.concurrency.ReservationStateCache.Facts;
import com.parking.concurrency.ReservationStateCache.SpaceHolder;
import com.parking.concurrency.ReservationStateCache.VehicleOwner;
import com.parking.concurrency.ReservationStateCache.VehicleReservation;
import com.parking.concurrency.SpaceLocks;
import com.parking.dto.response.ReservationResponse;
import com.parking.entity.AbstractEntity;
import com.parking.entity.ParkingSpaceEntity;
import com.parking.entity.ReservationEntity;
import com.parking.entity.UserEntity;
import com.parking.entity.VehicleEntity;
import com.parking.entity.WalletTransactionEntity;
import com.parking.event.ReservationConfirmedEvent;
import com.parking.exception.BusinessException;
import com.parking.exception.ErrorCode;
import com.parking.repository.ReservationRepository;
import com.parking.repository.VehicleRepository;
import com.parking.repository.WalletRepository;
import com.parking.repository.WalletTransactionRepository;
import com.parking.repository.projection.VehicleDayStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final long USER = 7L;
    private static final long VEHICLE = 11L;
    private static final int SPACE = 42;
    private static final long PRICE = 1000L;
    private static final String PLATE = "51F12345";
    // 2026-11-26 21:00 in Ho Chi Minh City: window open, target date 2026-11-27.
    private static final Instant OPEN_INSTANT = Instant.parse("2026-11-26T14:00:00Z");
    private static final LocalDate TOMORROW = LocalDate.of(2026, 11, 27);

    private static final Facts NOTHING_KNOWN = new Facts(null, null, false);
    /** Space {@link #SPACE} is reserved (committed) by someone else's vehicle. */
    private static final SpaceHolder RIVAL = new SpaceHolder(99L, 8L, "999");

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
    ReservationStateCache stateCache;
    @Mock
    SpaceLocks spaceLocks;
    @Mock
    DbPermits dbPermits;

    @BeforeEach
    void nothingCachedAndCoordinationGrantedImmediately() {
        lenient().when(stateCache.lookup(any(), anyLong(), anyLong())).thenReturn(NOTHING_KNOWN);
        lenient().when(stateCache.isKnownSpace(anyLong())).thenReturn(true);
        lenient().when(spaceLocks.callWithLock(any(), anyLong(), any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        lenient().when(dbPermits.callWithPermit(any()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
    }

    /**
     * The coordination under test, over the real reservation transaction logic (its repositories are mocks; without
     * a Spring proxy, {@code @Transactional} has no effect here: transaction boundaries are covered by the ITs).
     */
    private ReservationService serviceAt(Instant now) {
        ReservationWindow window = new ReservationWindow(Clock.fixed(now, ZoneOffset.UTC), "20:00", "Asia/Ho_Chi_Minh");
        ReservationBookingService booking = new ReservationBookingService(reservations, vehicles, wallets, ledger,
                events, PRICE);
        return new ReservationService(booking, reservations, vehicles, window, stateCache, spaceLocks, dbPermits,
                new SimpleMeterRegistry());
    }

    private void ownsVehicle() {
        when(vehicles.findByIdAndUserId(VEHICLE, USER)).thenReturn(Optional.of(vehicle()));
    }

    private void spaceIsFree(long newReservationId) {
        when(reservations.insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE)).thenReturn(Optional.of(newReservationId));
    }

    /** The insert hits a committed conflict; {@code vehiclesReservation} is what this vehicle already holds. */
    private void conflictWhileVehicleHolds(Optional<ReservationEntity> vehiclesReservation) {
        when(reservations.insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE)).thenReturn(Optional.empty());
        when(reservations.findByVehicleIdAndReservationDate(VEHICLE, TOMORROW)).thenReturn(vehiclesReservation);
    }

    private void cached(Facts facts) {
        when(stateCache.lookup(TOMORROW, SPACE, VEHICLE)).thenReturn(facts);
    }

    private void databaseShows(VehicleDayStatus status) {
        when(vehicles.findDayStatus(VEHICLE, TOMORROW)).thenReturn(Optional.ofNullable(status));
    }

    private static ReservationEntity existing(long id, int spaceId, long userId) {
        ParkingSpaceEntity space = withId(BeanUtils.instantiateClass(ParkingSpaceEntity.class), spaceId);
        return withId(new ReservationEntity(space, vehicle(), user(userId), TOMORROW, PRICE), id);
    }

    private static VehicleEntity vehicle() {
        return withId(new VehicleEntity(user(USER), PLATE), VEHICLE);
    }

    private static UserEntity user(long id) {
        return withId(new UserEntity("user" + id, "CIPHERTEXT"), id);
    }

    private static <T extends AbstractEntity> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((BusinessException) e).code();
    }

    // ---- The database path (nothing known yet): unchanged transaction behaviour ----

    @Test
    void successfulReservationDeductsExactlyTenDollarsRecordsLedgerAndPublishesEvent() {
        ownsVehicle();
        spaceIsFree(99L);
        when(wallets.debitIfSufficient(USER, PRICE)).thenReturn(1);
        when(reservations.getReferenceById(99L)).thenReturn(existing(99L, SPACE, USER));

        ReservationResponse result = serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE);

        assertThat(result).isEqualTo(new ReservationResponse(99L, SPACE, TOMORROW, PLATE, PRICE));
        verify(reservations).insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE);
        verify(wallets).debitIfSufficient(USER, 1000L);

        ArgumentCaptor<WalletTransactionEntity> entry = ArgumentCaptor.forClass(WalletTransactionEntity.class);
        verify(ledger).save(entry.capture());
        assertThat(entry.getValue().getType()).isEqualTo(WalletTransactionEntity.Type.RESERVATION);
        assertThat(entry.getValue().getAmountCents()).isEqualTo(-1000L);
        assertThat(entry.getValue().getReservation().getId()).isEqualTo(99L);
        assertThat(entry.getValue().getUser().getId()).isEqualTo(USER);

        verify(events).publishEvent(new ReservationConfirmedEvent(99L, SPACE, VEHICLE, USER, TOMORROW));
    }

    @Test
    void successfulReservationRunsUnderTheSpaceLockAndAReservationPermitAndIsCached() {
        ownsVehicle();
        spaceIsFree(99L);
        when(wallets.debitIfSufficient(USER, PRICE)).thenReturn(1);
        when(reservations.getReferenceById(99L)).thenReturn(existing(99L, SPACE, USER));

        ReservationResponse result = serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE);

        InOrder order = inOrder(spaceLocks, dbPermits, reservations, stateCache);
        order.verify(spaceLocks).callWithLock(eq(TOMORROW), eq((long) SPACE), any());
        order.verify(dbPermits).callWithPermit(any());
        order.verify(reservations).insertIfFree(SPACE, VEHICLE, USER, TOMORROW, PRICE);
        order.verify(stateCache).recordConfirmed(TOMORROW, VEHICLE, USER, result);
    }

    @Test
    void repeatOfASuccessfulRequestReturnsTheOriginalReservationWithoutChargingAgain() {
        ownsVehicle();
        conflictWhileVehicleHolds(Optional.of(existing(99L, SPACE, USER)));

        ReservationResponse result = serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE);

        assertThat(result).isEqualTo(new ReservationResponse(99L, SPACE, TOMORROW, PLATE, PRICE));
        assertNothingCharged();
    }

    @Test
    void rejectsBeforeOpeningTimeWithoutTouchingTheDatabaseOrRedis() {
        Instant beforeOpening = Instant.parse("2026-11-26T12:59:00Z"); // 19:59 local

        assertThatThrownBy(() -> serviceAt(beforeOpening).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.RESERVATION_CLOSED);
        // No transaction was even started, so no pool connection was taken; Redis was not asked either.
        verifyNoInteractions(vehicles, reservations, wallets, ledger, events, stateCache, spaceLocks,
                dbPermits);
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
    void conflictTheCacheDidNotKnowAboutReloadsTheCache() {
        ownsVehicle();
        conflictWhileVehicleHolds(Optional.empty());

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .isInstanceOf(BusinessException.class);
        // The space looked free under the lock, yet PostgreSQL had committed it: the cache missed a write.
        verify(stateCache).reload(TOMORROW);
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
    void unknownSpaceIsDecidedByTheDatabaseWithoutCreatingALock() {
        when(stateCache.isKnownSpace(999)).thenReturn(false);
        ownsVehicle();
        when(reservations.insertIfFree(999, VEHICLE, USER, TOMORROW, PRICE)).thenThrow(new DataIntegrityViolationException(
                "insert or update on table \"reservations\" violates foreign key constraint \"reservations_space_id_fkey\""));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, 999))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_NOT_FOUND);
        verifyNoInteractions(spaceLocks);
        verify(dbPermits).callWithPermit(any());
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
        verify(stateCache, never()).recordConfirmed(any(), anyLong(), anyLong(), any());
    }

    @Test
    void redisUnavailableFailsClosedBeforeTheDatabase() {
        when(stateCache.lookup(TOMORROW, SPACE, VEHICLE)).thenThrow(new RedisConnectionException("unreachable"));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .isInstanceOf(RedisConnectionException.class);
        verifyNoInteractions(vehicles, reservations, wallets, ledger);
    }

    // ---- Answers from committed reservations: same answers as the transaction, without it ----

    @Test
    void vehicleHoldingAnotherSpaceIsRejectedFromCommittedFactsWithoutTheDatabase() {
        cached(new Facts(null, new VehicleReservation(SPACE + 1, USER, 55L, PRICE), false));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_ALREADY_RESERVED);
        verifyNoInteractions(spaceLocks, dbPermits, vehicles, reservations, wallets, ledger);
    }

    @Test
    void vehicleWhoseReservationBelongsToAnotherUserIsNotFound() {
        // The reservation's user is the vehicle's owner: the caller does not own this vehicle.
        cached(new Facts(null, new VehicleReservation(SPACE, USER + 1, 55L, PRICE), false));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verifyNoInteractions(spaceLocks, dbPermits, vehicles, reservations, wallets, ledger);
    }

    @Test
    void repeatOfASuccessfulRequestIsReplayedFromCommittedFacts() {
        cached(new Facts(new SpaceHolder(VEHICLE, USER, "345"), new VehicleReservation(SPACE, USER, 99L, PRICE), false));
        when(stateCache.owner(VEHICLE)).thenReturn(Optional.of(new VehicleOwner(USER, PLATE)));

        ReservationResponse result = serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE);

        assertThat(result).isEqualTo(new ReservationResponse(99L, SPACE, TOMORROW, PLATE, PRICE));
        verifyNoInteractions(spaceLocks, dbPermits, vehicles, reservations, wallets, ledger);
    }

    @Test
    void takenSpaceOfASoldOutDateIsRejectedWithoutTheDatabase() {
        cached(new Facts(RIVAL, null, true));
        when(stateCache.owner(VEHICLE)).thenReturn(Optional.of(new VehicleOwner(USER, PLATE)));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_ALREADY_RESERVED);
        verifyNoInteractions(spaceLocks, dbPermits, vehicles, reservations, wallets, ledger);
    }

    @Test
    void takenSpaceWithSomeoneElsesVehicleIsNotFoundWithoutTheDatabase() {
        // Ownership is checked first by the transaction, and vehicles never change owner.
        cached(new Facts(RIVAL, null, false));
        when(stateCache.owner(VEHICLE)).thenReturn(Optional.of(new VehicleOwner(USER + 1, PLATE)));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verifyNoInteractions(spaceLocks, dbPermits, vehicles, reservations, wallets, ledger);
    }

    @Test
    void takenSpaceBeforeSellOutIsDecidedByOneRead() {
        // Before sell-out a missing vehicle entry proves nothing (its cache write may have failed): ask PostgreSQL.
        cached(new Facts(RIVAL, null, false));
        databaseShows(new VehicleDayStatus(USER, PLATE, null, null, null, null));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_ALREADY_RESERVED);
        verify(vehicles).findDayStatus(VEHICLE, TOMORROW);
        verifyNoInteractions(dbPermits);
        verify(stateCache).rememberOwner(VEHICLE, USER, PLATE);
        verifyNoInteractions(spaceLocks);
        verify(reservations, never()).insertIfFree(anyLong(), anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void databaseReadKeepsTheTransactionsOrderOfChecks() {
        cached(new Facts(RIVAL, null, false));
        ReservationService service = serviceAt(OPEN_INSTANT);

        databaseShows(null);
        assertThatThrownBy(() -> service.reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).as("no such vehicle").isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);

        databaseShows(new VehicleDayStatus(USER + 1, PLATE, null, null, null, null));
        assertThatThrownBy(() -> service.reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).as("someone else's").isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);

        databaseShows(new VehicleDayStatus(USER, PLATE, 55L, (long) SPACE + 1, USER, PRICE));
        assertThatThrownBy(() -> service.reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).as("holds another").isEqualTo(ErrorCode.VEHICLE_ALREADY_RESERVED);

        databaseShows(new VehicleDayStatus(USER, PLATE, 99L, (long) SPACE, USER, PRICE));
        assertThat(service.reserve(USER, VEHICLE, SPACE)).as("its own: replay")
                .isEqualTo(new ReservationResponse(99L, SPACE, TOMORROW, PLATE, PRICE));
    }

    @Test
    void spaceReservedWhileWaitingIsAnsweredAfterTheLockIsReleased() {
        // Free when the request arrives; reserved by the request ahead of it by the time it holds the lock.
        when(stateCache.lookup(TOMORROW, SPACE, VEHICLE)).thenReturn(NOTHING_KNOWN, new Facts(RIVAL, null, false));
        databaseShows(new VehicleDayStatus(USER, PLATE, null, null, null, null));

        assertThatThrownBy(() -> serviceAt(OPEN_INSTANT).reserve(USER, VEHICLE, SPACE))
                .extracting(ReservationServiceTest::codeOf).isEqualTo(ErrorCode.SPACE_ALREADY_RESERVED);
        InOrder order = inOrder(spaceLocks, vehicles);
        order.verify(spaceLocks).callWithLock(eq(TOMORROW), eq((long) SPACE), any());
        order.verify(vehicles).findDayStatus(VEHICLE, TOMORROW);
        // No claim, no payment: the pending reservation ahead of it committed first.
        verify(reservations, never()).insertIfFree(anyLong(), anyLong(), anyLong(), any(), anyLong());
        verifyNoInteractions(dbPermits);
    }

    private void assertNothingCharged() {
        verify(wallets, never()).debitIfSufficient(anyLong(), anyLong());
        verify(ledger, never()).save(any());
        verify(events, never()).publishEvent(any());
    }
}
