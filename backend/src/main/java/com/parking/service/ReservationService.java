package com.parking.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.dto.response.DashboardResponse;
import com.parking.dto.response.MyReservationResponse;
import com.parking.dto.response.ReservationResponse;
import com.parking.dto.response.SpaceStatusResponse;
import com.parking.entity.ReservationEntity;
import com.parking.entity.VehicleEntity;
import com.parking.entity.WalletTransactionEntity;
import com.parking.event.ReservationConfirmedEvent;
import com.parking.exception.BusinessException;
import com.parking.exception.Constraints;
import com.parking.exception.ErrorCode;
import com.parking.mapper.ReservationMapper;
import com.parking.repository.ReservationRepository;
import com.parking.repository.VehicleRepository;
import com.parking.repository.WalletRepository;
import com.parking.repository.WalletTransactionRepository;

/**
 * Reservation business logic. PostgreSQL guarantees correctness:
 * unique (space, date) and (vehicle, date) constraints + an atomic conditional balance debit,
 * all in one transaction. Any failure rolls back everything, so failed reservations never cost money.
 * Conflicts are detected with {@code INSERT ... ON CONFLICT DO NOTHING}, which keeps the transaction usable,
 * so a repeat of an already-successful request can be answered with the original reservation.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final VehicleRepository vehicles;
    private final WalletRepository wallets;
    private final WalletTransactionRepository ledger;
    private final ReservationWindow window;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final long priceCents;

    public ReservationService(ReservationRepository reservations, VehicleRepository vehicles,
            WalletRepository wallets, WalletTransactionRepository ledger, ReservationWindow window,
            ApplicationEventPublisher events, JdbcClient jdbc, TransactionTemplate tx,
            @Value("${parking.reservation.price-cents}") long priceCents) {
        this.reservations = reservations;
        this.vehicles = vehicles;
        this.wallets = wallets;
        this.ledger = ledger;
        this.window = window;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
        this.priceCents = priceCents;
    }

    /**
     * Reserves {@code spaceId} for tomorrow and charges the price. Idempotent: repeating a request that
     * already succeeded (client retry, double submit) returns the original reservation and charges nothing.
     */
    public ReservationResponse reserve(long userId, long vehicleId, int spaceId) {
        // Checked before the transaction starts: answering "closed" to everyone clicking at 19:59
        // must not take a connection from the pool.
        LocalDate date = window.requireOpen();
        return tx.execute(status -> reserveInTransaction(userId, vehicleId, spaceId, date));
    }

    private ReservationResponse reserveInTransaction(long userId, long vehicleId, int spaceId, LocalDate date) {
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

    /** All spaces for the target date. Single read path: the future Redis dashboard cache goes here. */
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(long userId) {
        LocalDate date = window.targetDate();
        List<SpaceStatusResponse> spaces = jdbc.sql("""
                SELECT s.id, v.plate, r.user_id
                FROM parking_spaces s
                LEFT JOIN reservations r ON r.space_id = s.id AND r.reservation_date = :date
                LEFT JOIN vehicles v ON v.id = r.vehicle_id
                ORDER BY s.id
                """)
                .param("date", date)
                .query((rs, n) -> ReservationMapper.toSpaceStatus(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, Long.class), userId))
                .list();
        return new DashboardResponse(date, window.isOpen(), window.openTime().toString(), window.zone().getId(), spaces);
    }

    @Transactional(readOnly = true)
    public List<MyReservationResponse> myReservations(long userId) {
        return jdbc.sql("""
                SELECT r.id, r.space_id, r.reservation_date, v.plate, r.price_cents
                FROM reservations r JOIN vehicles v ON v.id = r.vehicle_id
                WHERE r.user_id = :userId
                ORDER BY r.reservation_date DESC, r.space_id
                """)
                .param("userId", userId)
                .query((rs, n) -> new MyReservationResponse(rs.getLong(1), rs.getInt(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4), rs.getLong(5)))
                .list();
    }
}
