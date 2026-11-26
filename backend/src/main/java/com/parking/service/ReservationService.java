package com.parking.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.parking.concurrency.DbPermits;
import com.parking.concurrency.ReservationStateCache;
import com.parking.concurrency.ReservationStateCache.Facts;
import com.parking.concurrency.ReservationStateCache.VehicleOwner;
import com.parking.concurrency.ReservationStateCache.VehicleReservation;
import com.parking.concurrency.SpaceLocks;
import com.parking.dto.response.DashboardResponse;
import com.parking.dto.response.MyReservationResponse;
import com.parking.dto.response.ReservationResponse;
import com.parking.dto.response.SpaceStatusResponse;
import com.parking.exception.BusinessException;
import com.parking.exception.CapacityExceededException;
import com.parking.exception.ErrorCode;
import com.parking.mapper.ReservationMapper;
import com.parking.repository.ReservationRepository;
import com.parking.repository.VehicleRepository;
import com.parking.repository.projection.VehicleDayStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reservation entry point: coordinates the application instances through Redis in front of the reservation
 * transaction ({@link ReservationBookingService#book}), which alone decides correctness in PostgreSQL.
 *
 * <p>Requests for a space wait in a fair (FIFO) queue so only one at a time reaches the database, a global semaphore
 * bounds concurrent transactions, and requests whose outcome follows from committed reservations are answered
 * without a transaction, exactly as the transaction would have answered them. Without Redis, reservations fail
 * closed (503).
 */
@Service
public class ReservationService {

    private final ReservationBookingService booking;
    private final ReservationRepository reservations;
    private final VehicleRepository vehicles;
    private final ReservationWindow window;
    private final ReservationStateCache stateCache;
    private final SpaceLocks spaceLocks;
    private final DbPermits dbPermits;
    private final Counter answeredFromCache;
    private final Counter answeredByDatabaseRead;
    private final Counter reservationTransactions;

    public ReservationService(ReservationBookingService booking, ReservationRepository reservations,
            VehicleRepository vehicles, ReservationWindow window, ReservationStateCache stateCache,
            SpaceLocks spaceLocks, DbPermits dbPermits, MeterRegistry meters) {
        this.booking = booking;
        this.reservations = reservations;
        this.vehicles = vehicles;
        this.window = window;
        this.stateCache = stateCache;
        this.spaceLocks = spaceLocks;
        this.dbPermits = dbPermits;
        this.answeredFromCache = resolutions(meters, "cache");
        this.answeredByDatabaseRead = resolutions(meters, "database_read");
        this.reservationTransactions = resolutions(meters, "transaction");
    }

    private static Counter resolutions(MeterRegistry meters, String path) {
        return Counter.builder("parking.reservation.resolution")
                .description("Reservation requests by how they were decided")
                .tag("path", path)
                .register(meters);
    }

    /**
     * Reserves {@code spaceId} for tomorrow and charges the price. Idempotent: repeating a request that
     * already succeeded (client retry, double submit) returns the original reservation and charges nothing.
     *
     * <ol>
     * <li>Window check (no I/O): "closed" answers never touch Redis or the database.</li>
     * <li>If committed reservations decide the outcome (space or vehicle already reserved), answer it without a
     * transaction (see {@link #answerFromCommittedFacts}).</li>
     * <li>Otherwise wait in the space's FIFO queue (bounded), check again, then take a reservation permit
     * (bounded) and run the reservation transaction. Pending reservations are never treated as reserved: a waiter
     * gets the space if the one before it fails.</li>
     * </ol>
     * Never runs inside a caller's transaction ({@code NEVER}): no database connection may be held while waiting
     * for the lock or a permit.
     *
     * @throws CapacityExceededException if the space lock or the reservation permit was not granted in time (503,
     *         retry)
     * @throws RedisException if Redis is unreachable (503, fail closed)
     * @throws IllegalTransactionStateException if called inside a transaction
     */
    @Transactional(propagation = Propagation.NEVER)
    public ReservationResponse reserve(long userId, long vehicleId, int spaceId) {
        // Checked before anything else: answering "closed" to everyone clicking at 19:59
        // must not take a connection from the pool.
        LocalDate date = window.requireOpen();
        Optional<ReservationResponse> decided = answerFromCommittedFacts(
                stateCache.lookup(date, spaceId, vehicleId), userId, vehicleId, spaceId, date);
        if (decided.isPresent()) {
            return decided.get();
        }
        if (!stateCache.isKnownSpace(spaceId)) { // seat not exist should throw exception for simpler
            // No lock key for arbitrary ids: the transaction answers exactly as before (e.g. SPACE_NOT_FOUND).
            return bookWithPermit(userId, vehicleId, spaceId, date);
        }
        LockedAttempt attempt;
        try {
            attempt = spaceLocks.callWithLock(date, spaceId, () -> {
                Facts facts = stateCache.lookup(date, spaceId, vehicleId);
                return facts.decidable() ? LockedAttempt.decidedBy(facts)
                        : LockedAttempt.reserved(bookWithPermit(userId, vehicleId, spaceId, date));
            });
        } catch (BusinessException e) {
            if (e.code() == ErrorCode.SPACE_ALREADY_RESERVED) {
                // Under the lock the cache showed the space free, but PostgreSQL has a committed reservation:
                // its cache write was lost. Reload, so the next requests are answered without a transaction.
                reloadQuietly(date);
            }
            throw e;
        }
        if (attempt.reservation() != null) {
            return attempt.reservation();
        }
        // Reserved while this request waited. Answered after the lock is released, so the requests queued
        // behind it never wait for this one's database read.
        return answerFromCommittedFacts(attempt.facts(), userId, vehicleId, spaceId, date)
                .orElseThrow(() -> new IllegalStateException("Decidable facts gave no answer"));
    }

    /** The outcome of the locked section: a new reservation, or the committed facts that decide the request. */
    private record LockedAttempt(ReservationResponse reservation, Facts facts) {

        static LockedAttempt reserved(ReservationResponse reservation) {
            return new LockedAttempt(reservation, null);
        }

        static LockedAttempt decidedBy(Facts facts) {
            return new LockedAttempt(null, facts);
        }
    }

    /**
     * Answers from committed reservations exactly as {@link ReservationBookingService#book} would, or returns empty
     * when neither the space nor the vehicle is known to be reserved (the request must then compete for the space).
     * The transaction's order of checks is kept: vehicle ownership ({@code VEHICLE_NOT_FOUND}), then the vehicle's
     * own reservation (replay or {@code VEHICLE_ALREADY_RESERVED}), then the space ({@code SPACE_ALREADY_RESERVED}).
     *
     * <p>A reservation's user is always its vehicle's owner (ownership is checked before every claim, and vehicles
     * never change owner). "The vehicle holds no reservation" is only known for a sold-out date; otherwise one
     * database read decides ({@link #answerFromDatabase}).
     */
    private Optional<ReservationResponse> answerFromCommittedFacts(Facts facts, long userId, long vehicleId,
            int spaceId, LocalDate date) {
        VehicleReservation held = facts.vehicle();
        if (held != null) {
            if (held.userId() != userId) {
                throw answeredFromCache(ErrorCode.VEHICLE_NOT_FOUND);
            }
            if (held.spaceId() != spaceId) {
                throw answeredFromCache(ErrorCode.VEHICLE_ALREADY_RESERVED);
            }
            Optional<VehicleOwner> owner = stateCache.owner(vehicleId);
            if (owner.isPresent()) {
                // Repeat of a successful request: the original reservation, charged once.
                answeredFromCache.increment();
                return Optional.of(new ReservationResponse(held.reservationId(), spaceId, date, owner.get().plate(),
                        held.priceCents()));
            }
            return Optional.of(answerFromDatabase(userId, vehicleId, spaceId, date));
        }
        if (facts.space() == null) {
            return Optional.empty();
        }
        Optional<VehicleOwner> owner = stateCache.owner(vehicleId);
        if (owner.isPresent() && owner.get().userId() != userId) {
            throw answeredFromCache(ErrorCode.VEHICLE_NOT_FOUND);
        }
        if (owner.isPresent() && facts.dayFinal()) {
            // Sold out: the vehicle has no reservation (it would be in the final snapshot), so the space decides.
            throw answeredFromCache(ErrorCode.SPACE_ALREADY_RESERVED);
        }
        return Optional.of(answerFromDatabase(userId, vehicleId, spaceId, date));
    }

    private BusinessException answeredFromCache(ErrorCode code) {
        answeredFromCache.increment();
        return new BusinessException(code);
    }

    /**
     * The space is reserved by a committed reservation, but the vehicle's state is not known: one read (its own
     * read-only transaction, limited only by the connection pool) instead of a claim attempt, with the same answers
     * as the reservation transaction.
     */
    private ReservationResponse answerFromDatabase(long userId, long vehicleId, int spaceId, LocalDate date) {
        Optional<VehicleDayStatus> found = vehicles.findDayStatus(vehicleId, date);
        answeredByDatabaseRead.increment();
        VehicleDayStatus vehicle = found.orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND));
        stateCache.rememberOwner(vehicleId, vehicle.ownerId(), vehicle.plate());
        if (vehicle.ownerId() != userId) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND);
        }
        if (!vehicle.hasReservation()) {
            throw new BusinessException(ErrorCode.SPACE_ALREADY_RESERVED);
        }
        if (vehicle.reservationSpaceId() != spaceId || vehicle.reservationUserId() != userId) {
            throw new BusinessException(ErrorCode.VEHICLE_ALREADY_RESERVED);
        }
        return new ReservationResponse(vehicle.reservationId(), spaceId, date, vehicle.plate(), vehicle.priceCents());
    }

    /** The reservation transaction under a reservation permit, then the cache write (best effort). */
    private ReservationResponse bookWithPermit(long userId, long vehicleId, int spaceId, LocalDate date) {
        ReservationResponse reservation = dbPermits.callWithPermit(() -> {
            reservationTransactions.increment();
            return booking.book(userId, vehicleId, spaceId, date);
        });
        // The permit is already released; the space lock (if any) is held until the cache knows the outcome.
        stateCache.recordConfirmed(date, vehicleId, userId, reservation);
        return reservation;
    }

    private void reloadQuietly(LocalDate date) {
        try {
            stateCache.reload(date);
        } catch (RedisException | DataAccessException | TransactionException e) {
            // The answer to the client is already decided; the reload is retried by later requests.
        }
    }

    /**
     * All spaces for the target date. Served from the reservation cache (Redis, or memory once sold out); without
     * Redis, from PostgreSQL at most once per second per instance. Not transactional: a cache hit takes no
     * connection.
     */
    public DashboardResponse dashboard(long userId) {
        LocalDate date = window.targetDate();
        List<SpaceStatusResponse> spaces = stateCache.occupancy(date).stream()
                .map(row -> ReservationMapper.toSpaceStatus(row, userId))
                .toList();
        return new DashboardResponse(date, window.isOpen(), window.openTime().toString(), window.zone().getId(), spaces);
    }

    @Transactional(readOnly = true)
    public List<MyReservationResponse> myReservations(long userId) {
        return reservations.findAllByUserIdWithVehicle(userId).stream()
                .map(ReservationMapper::toMyReservation)
                .toList();
    }
}
