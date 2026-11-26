package com.parking.concurrency;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.parking.exception.CapacityExceededException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * One Redisson fair lock per (date, space): requests for the same space are served one at a time, in the order
 * Redis received their lock requests, across all application instances. Requests for different spaces never wait
 * for each other.
 *
 * <p>The wait is bounded ({@link CapacityExceededException} on timeout; Redisson then removes the request from the
 * queue) and the lock has an explicit lease instead of a watchdog, so a crashed holder blocks its space for at most
 * the lease. The lease is a soft limit: if it expires while the holder still works, the next request may proceed
 * and PostgreSQL serializes the two.
 *
 * <p>A request whose instance dies while it is queued cannot remove itself. Redisson drops its queue entry once the
 * holder's remaining lease plus the lock wait have passed (with a wait time, Redisson 4.8 uses the wait as the
 * per-waiter timeout, not {@code Config.fairLockWaitTimeout}); until then requests for that space time out with 503.
 */
@Component
public class SpaceLocks {

    private static final Logger log = LoggerFactory.getLogger(SpaceLocks.class);

    private final RedissonClient redisson;
    private final Duration wait;
    private final Duration lease;
    // Registered once: a meter lookup per call was a measurable part of the CPU profile under load.
    private final Timer acquiredWait;
    private final Timer timedOutWait;
    private final Counter ambiguousRetries;
    private final Counter leaseExpired;

    public SpaceLocks(RedissonClient redisson, ConcurrencyProperties properties, MeterRegistry meters) {
        this.redisson = redisson;
        this.wait = properties.spaceLock().waitTimeout();
        this.lease = properties.spaceLock().lease();
        this.acquiredWait = waitTimer(meters, "acquired");
        this.timedOutWait = waitTimer(meters, "timeout");
        this.ambiguousRetries = meters.counter("parking.space_lock.ambiguous_retry");
        this.leaseExpired = meters.counter("parking.lease.expired", "kind", "space_lock");
    }

    private static Timer waitTimer(MeterRegistry meters, String outcome) {
        return Timer.builder("parking.space_lock.wait")
                .description("Time waiting for a space lock")
                .tag("outcome", outcome)
                .register(meters);
    }

    /**
     * Runs {@code work} while holding the space's lock, and always releases it. A failure to release (lease already
     * expired, Redis unreachable) is logged and never replaces the outcome of {@code work}.
     *
     * @throws CapacityExceededException if the lock was not granted within the configured wait
     * @throws RedisException if Redis is unreachable (fail closed: nothing was done)
     */
    public <T> T callWithLock(LocalDate date, long spaceId, Supplier<T> work) {
        RLock lock = redisson.getFairLock(lockName(date, spaceId));
        if (!acquire(lock)) {
            throw new CapacityExceededException("space lock");
        }
        try {
            return work.get();
        } finally {
            unlockQuietly(lock);
        }
    }

    static String lockName(LocalDate date, long spaceId) {
        return "parking:lock:" + date + ":" + spaceId;
    }

    private boolean acquire(RLock lock) {
        long started = System.nanoTime();
        boolean acquired = false;
        try {
            try {
                acquired = lock.tryLock(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RedisException e) {
                // Ambiguous: Redis may have queued this request (or even granted the lock) before the client gave
                // up. Left alone, that queue entry would block the space until it expires. Retrying from the same
                // thread resumes the same entry, and Redisson removes it if this attempt times out. If Redis is
                // really down, the retry fails too and the request fails closed (its entry then expires on its own).
                ambiguousRetries.increment();
                long remaining = wait.toMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                acquired = lock.tryLock(Math.max(remaining, 0), lease.toMillis(), TimeUnit.MILLISECONDS);
                if (acquired && lock.getHoldCount() > 1) {
                    lock.unlock(); // the first attempt had succeeded too: keep a single hold
                }
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            (acquired ? acquiredWait : timedOutWait).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            // The lease expired first: another request may already hold the lock, so there is nothing to release.
            leaseExpired.increment();
            log.warn("Space lock {} expired before it was released", lock.getName());
        } catch (RedisException e) {
            log.warn("Could not release space lock {} (it expires with its lease): {}", lock.getName(), e.toString());
        }
    }
}
