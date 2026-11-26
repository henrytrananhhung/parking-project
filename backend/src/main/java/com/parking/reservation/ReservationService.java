package com.parking.reservation;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.parking.common.BusinessException;
import com.parking.common.Constraints;
import com.parking.common.ErrorCode;
import com.parking.vehicle.Vehicle;
import com.parking.vehicle.VehicleRepository;
import com.parking.wallet.WalletRepository;
import com.parking.wallet.WalletTransaction;
import com.parking.wallet.WalletTransactionRepository;

/**
 * Reservation business logic. PostgreSQL guarantees correctness:
 * unique (space, date) and (vehicle, date) constraints + an atomic conditional balance debit,
 * all in one transaction. Any failure rolls back everything, so failed reservations never cost money.
 */
@Service
public class ReservationService {

    public record ReservationResult(long id, int spaceId, LocalDate date, String plate, long priceCents) {
    }

    public record SpaceStatus(int id, boolean reserved, String plateSuffix, boolean mine) {
    }

    public record Dashboard(LocalDate date, boolean open, String opensAt, String timezone, List<SpaceStatus> spaces) {
    }

    public record MyReservation(long id, int spaceId, LocalDate date, String plate, long priceCents) {
    }

    private final ReservationRepository reservations;
    private final VehicleRepository vehicles;
    private final WalletRepository wallets;
    private final WalletTransactionRepository ledger;
    private final ReservationWindow window;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final long priceCents;

    public ReservationService(ReservationRepository reservations, VehicleRepository vehicles,
            WalletRepository wallets, WalletTransactionRepository ledger, ReservationWindow window,
            ApplicationEventPublisher events, JdbcClient jdbc,
            @Value("${parking.reservation.price-cents}") long priceCents) {
        this.reservations = reservations;
        this.vehicles = vehicles;
        this.wallets = wallets;
        this.ledger = ledger;
        this.window = window;
        this.events = events;
        this.jdbc = jdbc;
        this.priceCents = priceCents;
    }

    @Transactional
    public ReservationResult reserve(long userId, long vehicleId, int spaceId) {
        LocalDate date = window.requireOpen();
        Vehicle vehicle = vehicles.findByIdAndUserId(vehicleId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND));

        // 1. Claim the space: the unique constraints decide the winner under concurrency.
        Reservation reservation;
        try {
            reservation = reservations.saveAndFlush(new Reservation(spaceId, vehicleId, userId, date, priceCents));
        } catch (DataIntegrityViolationException e) {
            throw toBusinessException(e);
        }

        // 2. Pay: atomic conditional debit. 0 rows => insufficient balance => whole transaction rolls back.
        if (wallets.debitIfSufficient(userId, priceCents) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        // 3. Ledger + event (event listeners run only after commit).
        ledger.save(WalletTransaction.reservation(userId, priceCents, reservation.getId()));
        events.publishEvent(new ReservationConfirmed(reservation.getId(), spaceId, vehicleId, userId, date));
        return new ReservationResult(reservation.getId(), spaceId, date, vehicle.getPlate(), priceCents);
    }

    private static BusinessException toBusinessException(DataIntegrityViolationException e) {
        if (Constraints.violated(e, "uk_reservations_space_date")) {
            return new BusinessException(ErrorCode.SPACE_ALREADY_RESERVED);
        }
        if (Constraints.violated(e, "uk_reservations_vehicle_date")) {
            return new BusinessException(ErrorCode.VEHICLE_ALREADY_RESERVED);
        }
        if (Constraints.violated(e, "reservations_space_id_fkey")) {
            return new BusinessException(ErrorCode.SPACE_NOT_FOUND);
        }
        throw e;
    }

    /** All spaces for the target date. Single read path: the future Redis dashboard cache goes here. */
    @Transactional(readOnly = true)
    public Dashboard dashboard(long userId) {
        LocalDate date = window.targetDate();
        List<SpaceStatus> spaces = jdbc.sql("""
                SELECT s.id, v.plate, r.user_id
                FROM parking_spaces s
                LEFT JOIN reservations r ON r.space_id = s.id AND r.reservation_date = :date
                LEFT JOIN vehicles v ON v.id = r.vehicle_id
                ORDER BY s.id
                """)
                .param("date", date)
                .query((rs, n) -> {
                    String plate = rs.getString(2);
                    long ownerId = rs.getLong(3);
                    boolean reserved = plate != null;
                    return new SpaceStatus(rs.getInt(1), reserved, reserved ? plateSuffix(plate) : null,
                            reserved && ownerId == userId);
                })
                .list();
        return new Dashboard(date, window.isOpen(), window.openTime().toString(), window.zone().getId(), spaces);
    }

    @Transactional(readOnly = true)
    public List<MyReservation> myReservations(long userId) {
        return jdbc.sql("""
                SELECT r.id, r.space_id, r.reservation_date, v.plate, r.price_cents
                FROM reservations r JOIN vehicles v ON v.id = r.vehicle_id
                WHERE r.user_id = :userId
                ORDER BY r.reservation_date DESC, r.space_id
                """)
                .param("userId", userId)
                .query((rs, n) -> new MyReservation(rs.getLong(1), rs.getInt(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4), rs.getLong(5)))
                .list();
    }

    static String plateSuffix(String plate) {
        return plate.length() <= 3 ? plate : plate.substring(plate.length() - 3);
    }
}
