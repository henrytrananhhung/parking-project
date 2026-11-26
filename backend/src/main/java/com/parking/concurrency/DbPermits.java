package com.parking.concurrency;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.parking.concurrency.ConcurrencyProperties.Permits;
import com.parking.exception.CapacityExceededException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Global (all instances) limit on concurrent reservation transactions: one Redisson
 * {@link RPermitExpirableSemaphore}, so scaling out the application does not multiply the reservation load on
 * PostgreSQL. Every other database access (deposits, reads, the dashboard fallback) is limited only by each
 * instance's HikariCP pool and does not need Redis.
 *
 * <p>Waits are bounded ({@link CapacityExceededException}) and every permit has a lease, so a crashed instance
 * cannot leak permits. The lease is a soft limit: if it expires while its holder still works, one more holder is
 * admitted for a while; the connection pool remains the hard limit.
 *
 * <p>Permits bound concurrent work, they do not reserve connections. Because the budget is global, one instance may
 * hold every permit at once, so its pool should be at least the number of permits (checked at startup), plus
 * headroom for the database work outside the budget. That work can still fill a pool; a reservation then waits for
 * a connection up to the pool timeout (503).
 *
 * <p>If Redis loses the semaphore, it is re-created with all its permits while transactions admitted before the loss
 * may still run: for that moment up to twice the budget can be active, still bounded by each pool. The old holders'
 * releases are ignored (unknown permits), so the count never inflates.
 */
@Component
public class DbPermits {

    private static final Logger log = LoggerFactory.getLogger(DbPermits.class);

    private final RedissonClient redisson;
    private final Permits settings;
    private final String semaphoreName;
    /** Whether this instance has created (or found) the semaphore at least once. */
    private volatile boolean initialized;
    private final Timer acquiredWait;
    private final Timer timedOutWait;
    private final Counter leaseExpired;

    public DbPermits(RedissonClient redisson, ConcurrencyProperties properties, MeterRegistry meters,
            @Value("${spring.datasource.hikari.maximum-pool-size}") int connectionPoolSize) {
        this.redisson = redisson;
        this.settings = properties.reservationPermits();
        this.semaphoreName = semaphoreName(settings.permits());
        // Registered once: a meter lookup per call was a measurable part of the CPU profile under load.
        this.acquiredWait = waitTimer(meters, "acquired");
        this.timedOutWait = waitTimer(meters, "timeout");
        this.leaseExpired = meters.counter("parking.lease.expired", "kind", "db_permit");
        if (connectionPoolSize < settings.permits()) {
            log.warn("Connection pool ({}) is smaller than the reservation permits ({}): one instance holding many "
                    + "permits makes their holders wait for connections", connectionPoolSize, settings.permits());
        }
    }

    /**
     * Runs {@code work} while holding one reservation permit, and always releases it. A failure to release (lease
     * already expired, Redis unreachable) is logged and never replaces the outcome of {@code work}.
     *
     * @throws CapacityExceededException if no permit was granted within the configured wait
     * @throws RedisException if Redis is unreachable (fail closed: nothing was done)
     */
    public <T> T callWithPermit(Supplier<T> work) {
        RPermitExpirableSemaphore semaphore = semaphore();
        if (!initialized) {
            semaphore.trySetPermits(settings.permits());
            initialized = true;
        }
        long started = System.nanoTime();
        String permitId = acquire(semaphore, settings.waitTimeout().toMillis());
        if (permitId == null) {
            // Either the budget is exhausted, or the semaphore was lost with Redis data (restart without persistence)
            // and had no permits at all. Re-create it if missing (no-op otherwise) and try once more without waiting,
            // so every request that waited on the lost semaphore gets through, not only the one that re-created it.
            semaphore.trySetPermits(settings.permits());
            permitId = acquire(semaphore, 0);
        }
        // One sample per call: the whole wait, and its final outcome.
        (permitId != null ? acquiredWait : timedOutWait).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        if (permitId == null) {
            throw new CapacityExceededException("reservation permit");
        }
        try {
            return work.get();
        } finally {
            releaseQuietly(semaphore, permitId);
        }
    }

    /** Creates the semaphore if it does not exist yet (no-op otherwise). */
    public void initialize() {
        semaphore().trySetPermits(settings.permits());
        initialized = true;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initializeAtStartup() {
        try {
            initialize();
        } catch (RedisException e) {
            log.warn("Redis unavailable at startup; the reservation semaphore is created on first use: {}", e.toString());
        }
    }

    /**
     * The configured size is part of the name: a new size takes effect at once as a new semaphore, instead of being
     * ignored by {@code trySetPermits} on the existing one. Every instance must therefore run with the same size.
     */
    static String semaphoreName(int permits) {
        return "parking:permits:reservation:" + permits;
    }

    private RPermitExpirableSemaphore semaphore() {
        return redisson.getPermitExpirableSemaphore(semaphoreName);
    }

    private String acquire(RPermitExpirableSemaphore semaphore, long waitMillis) {
        try {
            return semaphore.tryAcquire(waitMillis, settings.lease().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Timer waitTimer(MeterRegistry meters, String outcome) {
        return Timer.builder("parking.db_permit.wait")
                .description("Time waiting for a reservation permit")
                .tag("outcome", outcome)
                .register(meters);
    }

    private void releaseQuietly(RPermitExpirableSemaphore semaphore, String permitId) {
        try {
            if (!semaphore.tryRelease(permitId)) {
                leaseExpired.increment();
                log.warn("Reservation permit expired (or lost with Redis data) before it was released");
            }
        } catch (RedisException e) {
            log.warn("Could not release reservation permit (it expires with its lease): {}", e.toString());
        }
    }
}
