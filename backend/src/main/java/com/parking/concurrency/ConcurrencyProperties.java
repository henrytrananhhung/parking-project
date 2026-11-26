package com.parking.concurrency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded waits and leases of the reservation coordination ({@code parking.concurrency.*}).
 *
 * <p>Leases are soft limits: they bound how long a crashed instance can block others, but a lease can expire while
 * its holder is still working (GC pause, slow commit). Correctness never depends on them; PostgreSQL constraints
 * decide every reservation.
 */
@ConfigurationProperties("parking.concurrency")
public record ConcurrencyProperties(
        Lease spaceLock,
        Permits reservationPermits,
        Duration cacheRefreshInterval,
        int ownerCacheSize) {

    /** How long to wait for a lock, and how long Redis keeps it if its holder never releases it. */
    public record Lease(Duration waitTimeout, Duration lease) {
    }

    /** The global budget of {@code permits} concurrent holders, with a bounded wait and a lease per permit. */
    public record Permits(int permits, Duration waitTimeout, Duration lease) {
    }
}
