package com.parking.service;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.parking.dto.response.ReservationResponse;
import com.parking.entity.ReservationEntity;
import com.parking.entity.VehicleEntity;
import com.parking.entity.WalletTransactionEntity;
import com.parking.event.ReservationConfirmedEvent;
import com.parking.exception.BusinessException;
import com.parking.exception.Constraints;
import com.parking.exception.ErrorCode;
import com.parking.repository.ReservationRepository;
import com.parking.repository.VehicleRepository;
import com.parking.repository.WalletRepository;
import com.parking.repository.WalletTransactionRepository;

/**
 * The reservation transaction. PostgreSQL guarantees correctness:
 * unique (space, date) and (vehicle, date) constraints + an atomic conditional balance debit,
 * all in one transaction. Any failure rolls back everything, so failed reservations never cost money.
 * Conflicts are detected with {@code INSERT ... ON CONFLICT DO NOTHING}, which keeps the transaction usable,
 * so a repeat of an already-successful request can be answered with the original reservation.
 *
 * <p>Transactional business logic only. Call it through {@link ReservationService#reserve}, which coordinates the
 * instances (space lock, reservation permit) before the transaction starts; {@code ArchitectureTest} keeps other
 * callers out.
 */
@Service
public class ReservationBookingService {

    private final ReservationRepository reservations;
    private final VehicleRepository vehicles;
    private final WalletRepository wallets;
    private final WalletTransactionRepository ledger;
    private final ApplicationEventPublisher events;
    private final long priceCents;

    public ReservationBookingService(ReservationRepository reservations, VehicleRepository vehicles,
            WalletRepository wallets, WalletTransactionRepository ledger, ApplicationEventPublisher events,
            @Value("${parking.reservation.price-cents}") long priceCents) {
        this.reservations = reservations;
        this.vehicles = vehicles;
        this.wallets = wallets;
        this.ledger = ledger;
        this.events = events;
        this.priceCents = priceCents;
    }

    /**
     * Claims {@code spaceId} on {@code date} for the vehicle and charges the price, in one transaction. Idempotent:
     * repeating a request that already succeeded returns the original reservation and charges nothing.
     */
    @Transactional
    public ReservationResponse book(long userId, long vehicleId, int spaceId, LocalDate date) {
        VehicleEntity vehicle = vehicles.findByIdAndUserId(vehicleId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND));

        // 1. Claim the space: the unique constraints decide the winner under concurrency.
        Optional<Long> claimed = claim(spaceId, vehicleId, userId, date);
        if (claimed.isEmpty()) {
            return replayOrReject(userId, vehicleId, spaceId, date, vehicle.getPlate());
        }
        long reservationId = claimed.get();

        // 2. Pay: atomic conditional debit. 0 rows => insufficient balance => whole transaction rolls back.
        if (wallets.debitIfSufficient(userId, priceCents) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        // 3. Ledger + event (event listeners run only after commit).
        // References only (vehicle.getUser() is a lazy proxy, getReferenceById runs no SQL): one INSERT.
        ledger.save(WalletTransactionEntity.reservationCharge(vehicle.getUser(), priceCents,
                reservations.getReferenceById(reservationId)));
        events.publishEvent(new ReservationConfirmedEvent(reservationId, spaceId, vehicleId, userId, date));
        return new ReservationResponse(reservationId, spaceId, date, vehicle.getPlate(), priceCents);
    }

    private Optional<Long> claim(int spaceId, long vehicleId, long userId, LocalDate date) {
        try {
            return reservations.insertIfFree(spaceId, vehicleId, userId, date, priceCents);
        } catch (DataIntegrityViolationException e) {
            // ON CONFLICT absorbs unique conflicts only; an unknown space still fails the foreign key.
            if (Constraints.violated(e, ReservationEntity.FK_SPACE)) {
                throw new BusinessException(ErrorCode.SPACE_NOT_FOUND);
            }
            throw e;
        }
    }

    /**
     * The space or the vehicle is already reserved for the date (by a committed transaction).
     * If it is this caller's own reservation of this vehicle on this space, the request is a repeat:
     * return the original result. Otherwise report which rule blocked it.
     */
    private ReservationResponse replayOrReject(long userId, long vehicleId, int spaceId, LocalDate date, String plate) {
        ReservationEntity existing = reservations.findByVehicleIdAndReservationDate(vehicleId, date)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_ALREADY_RESERVED));
        if (existing.getSpace().getId() != spaceId || existing.getUser().getId() != userId) {
            throw new BusinessException(ErrorCode.VEHICLE_ALREADY_RESERVED);
        }
        return new ReservationResponse(existing.getId(), spaceId, date, plate, existing.getPriceCents());
    }
}
