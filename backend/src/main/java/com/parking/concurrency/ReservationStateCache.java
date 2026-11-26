package com.parking.concurrency;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import com.parking.dto.response.ReservationResponse;
import com.parking.mapper.ReservationMapper;
import com.parking.repository.ParkingSpaceRepository;
import com.parking.repository.ReservationRepository;
import com.parking.repository.VehicleRepository;
import com.parking.repository.projection.ConfirmedReservation;
import com.parking.repository.projection.SpaceOccupancy;
import com.parking.repository.projection.VehicleOwnerRow;

/**
 * Committed reservations per date, shared by all instances in one Redis hash per date, so that requests for
 * reserved spaces (and the dashboard) are answered without PostgreSQL.
 *
 * <p>Only <em>committed</em> facts are stored (written after the commit), and they never become false: reservations
 * are never cancelled and vehicles never change owner. A <em>missing</em> entry proves nothing, because its write
 * may have failed, so callers treat it as unknown and ask PostgreSQL, which stays the source of truth. The one
 * exception: once a single PostgreSQL read shows every space of a date reserved, the unique (space, date)
 * constraint guarantees that no reservation can ever be added for that date. The complete set is then final and
 * kept in memory per instance, and absence from it is a fact too.
 *
 * <p>Hash {@code parking:reservations:{date}}. The two entries of a reservation are written by one HSET, so they
 * are either both present or both absent:
 * <ul>
 * <li>{@code s:{spaceId}} = {@code vehicleId:userId:plateSuffix}</li>
 * <li>{@code v:{vehicleId}} = {@code spaceId:userId:reservationId:priceCents}</li>
 * <li>{@code complete} = epoch millis of the last reload from PostgreSQL (the dashboard trusts the hash while
 * fresh)</li>
 * <li>{@code final} = present once a reload saw every space reserved</li>
 * </ul>
 * Manual deletes in PostgreSQL (smoke-test cleanup) leave stale entries: follow the cleanup runbook in the README
 * (flush Redis, restart the instances).
 */
@Component
public class ReservationStateCache {

    private static final Logger log = LoggerFactory.getLogger(ReservationStateCache.class);

    private static final String KEY_PREFIX = "parking:reservations:";
    private static final String SPACE = "s:";
    private static final String VEHICLE = "v:";
    private static final String COMPLETE = "complete";
    private static final String FINAL = "final";
    private static final Duration RELOAD_LEASE = Duration.ofSeconds(10);
    private static final Duration DATABASE_FALLBACK_TTL = Duration.ofSeconds(1);

    private final RedissonClient redisson;
    private final ReservationRepository reservations;
    private final ParkingSpaceRepository parkingSpaces;
    private final VehicleRepository vehicles;
    private final Duration refreshInterval;
    private final int ownerCacheSize;

    private final ConcurrentMap<LocalDate, DaySnapshot> finalDays = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, VehicleOwner> owners = new ConcurrentHashMap<>();
    private final ReentrantLock databaseFallbackLock = new ReentrantLock();
    private volatile KnownSpaces knownSpaces;
    private volatile DatabaseOccupancy databaseFallback;

    /**
     * The repository queries used here run in read-only transactions of their own (declared on the repository
     * methods), only on reloads and fallbacks: a cache hit opens no transaction and takes no connection.
     */
    public ReservationStateCache(RedissonClient redisson, ReservationRepository reservations,
            ParkingSpaceRepository parkingSpaces, VehicleRepository vehicles, ConcurrencyProperties properties) {
        this.redisson = redisson;
        this.reservations = reservations;
        this.parkingSpaces = parkingSpaces;
        this.vehicles = vehicles;
        this.refreshInterval = properties.cacheRefreshInterval();
        this.ownerCacheSize = properties.ownerCacheSize();
    }

    /** What is known (committed) about one request's space and vehicle on one date. */
    public record Facts(SpaceHolder space, VehicleReservation vehicle, boolean dayFinal) {

        /** True if the space is known to be reserved or the vehicle is known to hold a reservation. */
        public boolean decidable() {
            return space != null || vehicle != null;
        }
    }

    /** Who holds a reserved space. */
    public record SpaceHolder(long vehicleId, long userId, String plateSuffix) {

        String encode() {
            return vehicleId + ":" + userId + ":" + plateSuffix;
        }

        /** @return the decoded holder, or null if absent or malformed (treated as unknown) */
        static SpaceHolder parse(String value) {
            String[] parts = value == null ? new String[0] : value.split(":", 3);
            if (parts.length != 3) {
                return null;
            }
            try {
                return new SpaceHolder(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** The reservation a vehicle holds. */
    public record VehicleReservation(long spaceId, long userId, long reservationId, long priceCents) {

        String encode() {
            return spaceId + ":" + userId + ":" + reservationId + ":" + priceCents;
        }

        /** @return the decoded reservation, or null if absent or malformed (treated as unknown) */
        static VehicleReservation parse(String value) {
            String[] parts = value == null ? new String[0] : value.split(":", 4);
            if (parts.length != 4) {
                return null;
            }
            try {
                return new VehicleReservation(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]), Long.parseLong(parts[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** A vehicle's owner and plate. Vehicles never change owner or plate, so this never goes stale. */
    public record VehicleOwner(long userId, String plate) {
    }

    /**
     * Committed facts about {@code spaceId} and {@code vehicleId} on {@code date}: from memory if the date is sold
     * out, else one Redis round trip.
     *
     * @throws RedisException if Redis is unreachable (reservations then fail closed)
     */
    public Facts lookup(LocalDate date, long spaceId, long vehicleId) {
        DaySnapshot day = finalDays.get(date);
        if (day != null) {
            return day.facts(spaceId, vehicleId);
        }
        String spaceField = SPACE + spaceId;
        String vehicleField = VEHICLE + vehicleId;
        Map<String, String> fields = hash(date).getAll(Set.of(spaceField, vehicleField, FINAL));
        if (fields.containsKey(FINAL)) {
            DaySnapshot loaded = keepIfFinal(date, DaySnapshot.fromHash(hash(date).readAllMap(), knownSpaces()));
            if (loaded != null) {
                return loaded.facts(spaceId, vehicleId);
            }
        }
        return new Facts(SpaceHolder.parse(fields.get(spaceField)), VehicleReservation.parse(fields.get(vehicleField)),
                false);
    }

    /**
     * Records a reservation PostgreSQL has committed. Best effort, never throws: the reservation is committed
     * whatever happens here, and a missing entry only means more work for the database later. When the date looks
     * sold out, a reload from PostgreSQL confirms it and marks the date final.
     */
    public void recordConfirmed(LocalDate date, long vehicleId, long userId, ReservationResponse reservation) {
        rememberOwner(vehicleId, userId, reservation.plate());
        try {
            RMap<String, String> hash = hash(date);
            hash.putAll(Map.of(
                    SPACE + reservation.spaceId(),
                    new SpaceHolder(vehicleId, userId, ReservationMapper.plateSuffix(reservation.plate())).encode(),
                    VEHICLE + vehicleId,
                    new VehicleReservation(reservation.spaceId(), userId, reservation.id(), reservation.priceCents())
                            .encode()));
            hash.expire(expiryOf(date));
            if (hash.size() >= 2 * knownSpaces().ids().size()) {
                reload(date);
            }
        } catch (RedisException | DataAccessException | TransactionException e) {
            log.warn("Reservation {} committed but not cached: {}", reservation.id(), e.toString());
        }
    }

    /**
     * Every known space with its reservation on {@code date}, if any (dashboard). From memory if the date is sold
     * out, else from the Redis hash while it is fresh, else after a reload. Falls back to PostgreSQL (at most one
     * query per second per instance) when Redis is unreachable or another instance is reloading.
     */
    public List<SpaceOccupancy> occupancy(LocalDate date) {
        DaySnapshot day = finalDays.get(date);
        if (day != null) {
            return occupancyOf(day.bySpace());
        }
        try {
            Map<String, String> fields = hash(date).readAllMap();
            if (fields.containsKey(FINAL)) {
                DaySnapshot loaded = keepIfFinal(date, DaySnapshot.fromHash(fields, knownSpaces()));
                if (loaded != null) {
                    return occupancyOf(loaded.bySpace());
                }
            }
            if (isFresh(fields.get(COMPLETE))) {
                return occupancyOf(holdersBySpace(fields));
            }
            Optional<List<ConfirmedReservation>> reloaded = reload(date);
            if (reloaded.isPresent()) {
                return occupancyOf(DaySnapshot.holders(reloaded.get()));
            }
        } catch (RedisException e) {
            log.debug("Redis unavailable, dashboard from PostgreSQL: {}", e.toString());
        }
        return databaseOccupancy(date);
    }

    /**
     * Reloads {@code date} from PostgreSQL into Redis, unless another instance is already doing it. Merges: entries
     * are never removed. Marks the date final if the read shows every known space reserved.
     *
     * @return the reservations read, or empty if another instance holds the reload lock
     * @throws RedisException if Redis is unreachable
     */
    public Optional<List<ConfirmedReservation>> reload(LocalDate date) {
        RLock lock = redisson.getLock(KEY_PREFIX + date + ":reload");
        if (!tryLockNow(lock)) {
            return Optional.empty();
        }
        try {
            List<ConfirmedReservation> rows = reservations.findConfirmedOn(date);
            DaySnapshot finalDay = DaySnapshot.fromRows(rows, knownSpaces());
            Map<String, String> fields = new HashMap<>();
            for (ConfirmedReservation r : rows) {
                rememberOwner(r.vehicleId(), r.userId(), r.plate());
                fields.put(SPACE + r.spaceId(), DaySnapshot.holderOf(r).encode());
                fields.put(VEHICLE + r.vehicleId(), DaySnapshot.reservationOf(r).encode());
            }
            fields.put(COMPLETE, Long.toString(System.currentTimeMillis()));
            if (finalDay != null) {
                fields.put(FINAL, "1");
            }
            RMap<String, String> hash = hash(date);
            hash.putAll(fields);
            hash.expire(expiryOf(date));
            keepIfFinal(date, finalDay);
            return Optional.of(rows);
        } finally {
            unlockQuietly(lock);
        }
    }

    /** True for the spaces that exist (loaded once: spaces are only added by migrations, i.e. with a restart). */
    public boolean isKnownSpace(long spaceId) {
        return knownSpaces().set().contains(spaceId);
    }

    public Optional<VehicleOwner> owner(long vehicleId) {
        return Optional.ofNullable(owners.get(vehicleId));
    }

    /** Remembers a vehicle's owner and plate in this instance (bounded: cleared when full). */
    public void rememberOwner(long vehicleId, long userId, String plate) {
        if (owners.size() >= ownerCacheSize && !owners.containsKey(vehicleId)) {
            owners.clear();
        }
        owners.put(vehicleId, new VehicleOwner(userId, plate));
    }

    /** Drops what this instance holds in memory (not Redis). For tests, and after manual data changes. */
    public void clearLocalCaches() {
        finalDays.clear();
        owners.clear();
        databaseFallback = null;
    }

    @EventListener(ApplicationReadyEvent.class)
    void loadKnownSpacesAtStartup() {
        try {
            knownSpaces();
            preloadOwners();
        } catch (DataAccessException | TransactionException e) {
            log.warn("Could not preload spaces and vehicle owners at startup; loaded on first use: {}", e.toString());
        }
    }

    /**
     * Loads the owners of the newest vehicles (up to the owner cache size) in one query. Vehicles never change owner,
     * so these entries never go stale; vehicles registered later are learned on first use. Measured in Phase 2:
     * without it, a fresh instance needs one bounded database read per vehicle in the opening burst.
     */
    public void preloadOwners() {
        for (VehicleOwnerRow row : vehicles.findOwners(Limit.of(ownerCacheSize))) {
            owners.putIfAbsent(row.vehicleId(), new VehicleOwner(row.userId(), row.plate()));
        }
    }

    private KnownSpaces knownSpaces() {
        KnownSpaces known = knownSpaces;
        if (known == null) {
            List<Long> ids = parkingSpaces.findAllIds();
            known = new KnownSpaces(List.copyOf(ids), Set.copyOf(ids));
            knownSpaces = known; // concurrent first loads compute the same value
        }
        return known;
    }

    private RMap<String, String> hash(LocalDate date) {
        return redisson.getMap(KEY_PREFIX + date, StringCodec.INSTANCE);
    }

    /** Keeps the snapshot of a sold-out date in memory (bounded: older dates are dropped). */
    private DaySnapshot keepIfFinal(LocalDate date, DaySnapshot day) {
        if (day != null) {
            finalDays.keySet().removeIf(other -> other.isBefore(date));
            finalDays.put(date, day);
        }
        return day;
    }

    private boolean isFresh(String completeMillis) {
        if (completeMillis == null) {
            return false;
        }
        try {
            return System.currentTimeMillis() - Long.parseLong(completeMillis) < refreshInterval.toMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private List<SpaceOccupancy> occupancyOf(Map<Long, SpaceHolder> holders) {
        List<SpaceOccupancy> rows = new ArrayList<>();
        for (Long spaceId : knownSpaces().ids()) {
            SpaceHolder holder = holders.get(spaceId);
            rows.add(holder == null
                    ? new SpaceOccupancy(spaceId, null, null)
                    : new SpaceOccupancy(spaceId, holder.plateSuffix(), holder.userId()));
        }
        return rows;
    }

    private static Map<Long, SpaceHolder> holdersBySpace(Map<String, String> fields) {
        Map<Long, SpaceHolder> holders = new HashMap<>();
        fields.forEach((field, value) -> {
            if (field.startsWith(SPACE)) {
                SpaceHolder holder = SpaceHolder.parse(value);
                Long spaceId = parseId(field.substring(SPACE.length()));
                if (holder != null && spaceId != null) {
                    holders.put(spaceId, holder);
                }
            }
        });
        return holders;
    }

    /** Fallback while Redis cannot answer: one query per second per instance at most. */
    private List<SpaceOccupancy> databaseOccupancy(LocalDate date) {
        DatabaseOccupancy memo = databaseFallback;
        if (memo != null && memo.isFreshFor(date)) {
            return memo.rows();
        }
        databaseFallbackLock.lock();
        try {
            memo = databaseFallback;
            if (memo != null && memo.isFreshFor(date)) {
                return memo.rows();
            }
            List<SpaceOccupancy> rows = parkingSpaces.findOccupancy(date);
            databaseFallback = new DatabaseOccupancy(date, rows, System.nanoTime());
            return rows;
        } finally {
            databaseFallbackLock.unlock();
        }
    }

    private static boolean tryLockNow(RLock lock) {
        try {
            return lock.tryLock(0, RELOAD_LEASE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException | RedisException e) {
            log.warn("Could not release {} (it expires with its lease): {}", lock.getName(), e.toString());
        }
    }

    /** Keys outlive their date by about two days, then Redis drops them. */
    private static Instant expiryOf(LocalDate date) {
        return date.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record KnownSpaces(List<Long> ids, Set<Long> set) {
    }

    private record DatabaseOccupancy(LocalDate date, List<SpaceOccupancy> rows, long loadedAtNanos) {

        boolean isFreshFor(LocalDate requested) {
            return date.equals(requested) && System.nanoTime() - loadedAtNanos < DATABASE_FALLBACK_TTL.toNanos();
        }
    }

    /**
     * Every reservation of a sold-out date. Final: once every space is reserved, the unique (space, date)
     * constraint lets no reservation be added for that date, so absence from this snapshot is a fact.
     */
    private record DaySnapshot(Map<Long, SpaceHolder> bySpace, Map<Long, VehicleReservation> byVehicle) {

        Facts facts(long spaceId, long vehicleId) {
            return new Facts(bySpace.get(spaceId), byVehicle.get(vehicleId), true);
        }

        /** @return the snapshot if one consistent database read covers every known space, else null */
        static DaySnapshot fromRows(List<ConfirmedReservation> rows, KnownSpaces known) {
            Map<Long, VehicleReservation> byVehicle = new HashMap<>();
            for (ConfirmedReservation r : rows) {
                byVehicle.put(r.vehicleId(), reservationOf(r));
            }
            return complete(holders(rows), byVehicle, known);
        }

        /** @return the snapshot if the hash holds a consistent pair of entries for every known space, else null */
        static DaySnapshot fromHash(Map<String, String> fields, KnownSpaces known) {
            Map<Long, VehicleReservation> byVehicle = new HashMap<>();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (field.getKey().startsWith(VEHICLE)) {
                    Long vehicleId = parseId(field.getKey().substring(VEHICLE.length()));
                    VehicleReservation held = VehicleReservation.parse(field.getValue());
                    if (vehicleId == null || held == null) {
                        return null;
                    }
                    byVehicle.put(vehicleId, held);
                }
            }
            return complete(holdersBySpace(fields), byVehicle, known);
        }

        static Map<Long, SpaceHolder> holders(List<ConfirmedReservation> rows) {
            Map<Long, SpaceHolder> bySpace = new HashMap<>();
            for (ConfirmedReservation r : rows) {
                bySpace.put(r.spaceId(), holderOf(r));
            }
            return bySpace;
        }

        static SpaceHolder holderOf(ConfirmedReservation r) {
            return new SpaceHolder(r.vehicleId(), r.userId(), ReservationMapper.plateSuffix(r.plate()));
        }

        static VehicleReservation reservationOf(ConfirmedReservation r) {
            return new VehicleReservation(r.spaceId(), r.userId(), r.reservationId(), r.priceCents());
        }

        /**
         * Final only if every known space has a holder whose vehicle entry points back to it (same space, same
         * user), and there is no other entry. The entry count alone is not trusted.
         */
        private static DaySnapshot complete(Map<Long, SpaceHolder> bySpace, Map<Long, VehicleReservation> byVehicle,
                KnownSpaces known) {
            int spaces = known.ids().size();
            if (spaces == 0 || bySpace.size() != spaces || byVehicle.size() != spaces) {
                return null;
            }
            for (Long spaceId : known.ids()) {
                SpaceHolder holder = bySpace.get(spaceId);
                if (holder == null) {
                    return null;
                }
                VehicleReservation held = byVehicle.get(holder.vehicleId());
                if (held == null || held.spaceId() != spaceId || held.userId() != holder.userId()) {
                    return null;
                }
            }
            return new DaySnapshot(Map.copyOf(bySpace), Map.copyOf(byVehicle));
        }
    }
}
