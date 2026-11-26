package com.parking.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;

import com.parking.IntegrationTestSupport;
import com.parking.concurrency.ConcurrencyProperties.Lease;
import com.parking.concurrency.ConcurrencyProperties.Permits;
import com.parking.config.ParkingRedisProperties;
import com.parking.config.RedisConfig;
import com.parking.exception.CapacityExceededException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The coordination primitives against a real Redis, with dedicated small settings. Each extra
 * {@link RedissonClient} plays one more application instance.
 */
class ConcurrencyControlIT extends IntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2030, 1, 15);
    private static final Duration LONG = Duration.ofSeconds(30);
    /** The test profile's pool: larger than every budget used here (no startup warning). */
    private static final int POOL_SIZE = 25;

    @Autowired
    ParkingRedisProperties redisProperties;

    private final List<RedissonClient> instances = new ArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        threads.shutdownNow();
        instances.forEach(client -> {
            if (!client.isShutdown()) {
                client.shutdown();
            }
        });
    }

    @Test
    void reservationBudgetIsGlobalAcrossInstances() throws Exception {
        ConcurrencyProperties twoPermits = settings(new Lease(LONG, LONG), new Permits(2, LONG, LONG));
        DbPermits first = permitsOn(newInstance(), twoPermits);
        DbPermits second = permitsOn(newInstance(), twoPermits);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        List<Future<?>> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            DbPermits instance = i % 2 == 0 ? first : second;
            work.add(threads.submit(() -> instance.callWithPermit(() -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                sleep(100);
                return running.decrementAndGet();
            })));
        }
        for (Future<?> f : work) {
            f.get(30, TimeUnit.SECONDS);
        }

        // Two instances, one budget of 2: never more than 2 holders in total, and the budget was used.
        assertThat(maxRunning).hasValue(2);
    }

    @Test
    void permitWaitIsBoundedAndTheWorkNeverRuns() throws Exception {
        DbPermits permits = permitsOn(newInstance(),
                settings(new Lease(LONG, LONG), new Permits(1, Duration.ofMillis(300), LONG)));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> permits.callWithPermit(() -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holding.await(10, TimeUnit.SECONDS);
        AtomicInteger ran = new AtomicInteger();

        long started = System.nanoTime();
        assertThatThrownBy(() -> permits.callWithPermit(ran::incrementAndGet))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(ran).hasValue(0);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void expiredPermitLeaseAdmitsTheNextHolderAndIsReleasedQuietly() throws Exception {
        // Soft limit: a holder slower than its lease lets one more in; its late release must not fail its work.
        DbPermits permits = permitsOn(newInstance(),
                settings(new Lease(LONG, LONG), new Permits(1, Duration.ofSeconds(5), Duration.ofMillis(300))));
        CountDownLatch slowStarted = new CountDownLatch(1);
        Future<String> slow = threads.submit(() -> permits.callWithPermit(() -> {
            slowStarted.countDown();
            sleep(1500);
            return "slow done";
        }));
        slowStarted.await(10, TimeUnit.SECONDS);

        String next = permits.callWithPermit(() -> "admitted after the lease expired");

        assertThat(next).isEqualTo("admitted after the lease expired");
        assertThat(slow.get(10, TimeUnit.SECONDS)).isEqualTo("slow done");
    }

    @Test
    void spaceLockGrantsWaitersInArrivalOrderAcrossInstances() throws Exception {
        ConcurrencyProperties settings = settings(new Lease(LONG, LONG), new Permits(1, LONG, LONG));
        SpaceLocks first = new SpaceLocks(newInstance(), settings, new SimpleMeterRegistry());
        SpaceLocks second = new SpaceLocks(newInstance(), settings, new SimpleMeterRegistry());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> grantOrder = new CopyOnWriteArrayList<>();
        Future<?> holder = threads.submit(() -> first.callWithLock(DATE, 7, () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holding.await(10, TimeUnit.SECONDS);

        // Enqueue alternately from both instances, each only once the previous one is in the queue.
        List<Future<?>> waiters = new ArrayList<>();
        String[] names = { "A@second", "B@first", "C@second", "D@first" };
        for (int i = 0; i < names.length; i++) {
            SpaceLocks instance = i % 2 == 0 ? second : first;
            String name = names[i];
            waiters.add(threads.submit(() -> instance.callWithLock(DATE, 7, () -> grantOrder.add(name))));
            int queued = i + 1;
            waitUntil(() -> queueLength(DATE, 7) == queued);
        }
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        for (Future<?> waiter : waiters) {
            waiter.get(10, TimeUnit.SECONDS);
        }

        assertThat(grantOrder).containsExactly(names);
    }

    @Test
    void burstOfWaitersOnOneSpaceOfAFreshInstanceIsServedWithinTheProductionWait() throws Exception {
        // 300 requests (virtual threads, as in production) queue for one space on an instance that has just started:
        // each must get its subscription and be woken in turn, well within the 3 s production wait.
        SpaceLocks locks = new SpaceLocks(newInstance(),
                settings(new Lease(Duration.ofSeconds(3), Duration.ofSeconds(20)), new Permits(1, LONG, LONG)),
                new SimpleMeterRegistry());
        int waiters = 300;
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> locks.callWithLock(DATE, 11, () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holding.await(10, TimeUnit.SECONDS);
        AtomicInteger served = new AtomicInteger();
        List<Future<?>> queued = new ArrayList<>();
        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < waiters; i++) {
                queued.add(virtualThreads.submit(() -> locks.callWithLock(DATE, 11, served::incrementAndGet)));
            }
            waitUntil(() -> queueLength(DATE, 11) == waiters);
            long released = System.nanoTime();
            release.countDown();
            for (Future<?> request : queued) {
                request.get(30, TimeUnit.SECONDS); // a CapacityExceededException would fail here
            }
            System.out.printf("Drained %d waiters on one space in %d ms%n", waiters,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - released));
        }
        holder.get(10, TimeUnit.SECONDS);

        assertThat(served).hasValue(waiters);
    }

    @Test
    void lockWaitIsBoundedAndATimedOutWaiterLeavesTheQueue() throws Exception {
        SpaceLocks locks = new SpaceLocks(newInstance(),
                settings(new Lease(Duration.ofMillis(300), LONG), new Permits(1, LONG, LONG)), new SimpleMeterRegistry());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> locks.callWithLock(DATE, 8, () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holding.await(10, TimeUnit.SECONDS);
        AtomicInteger ran = new AtomicInteger();

        assertThatThrownBy(() -> locks.callWithLock(DATE, 8, ran::incrementAndGet))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(ran).hasValue(0);
        assertThat(queueLength(DATE, 8)).as("the timed-out waiter removed itself").isZero();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        assertThat(locks.callWithLock(DATE, 8, () -> "free again")).isEqualTo("free again");
    }

    @Test
    void expiredLockLeaseLetsTheNextRequestInAndIsReleasedQuietly() throws Exception {
        SpaceLocks locks = new SpaceLocks(newInstance(),
                settings(new Lease(Duration.ofSeconds(5), Duration.ofMillis(300)), new Permits(1, LONG, LONG)),
                new SimpleMeterRegistry());
        CountDownLatch slowStarted = new CountDownLatch(1);
        Future<String> slow = threads.submit(() -> locks.callWithLock(DATE, 9, () -> {
            slowStarted.countDown();
            sleep(1500);
            return "slow done";
        }));
        slowStarted.await(10, TimeUnit.SECONDS);

        assertThat(locks.callWithLock(DATE, 9, () -> "admitted")).isEqualTo("admitted");
        // Its lease expired long ago: the unlock fails inside, the work's result still comes back.
        assertThat(slow.get(10, TimeUnit.SECONDS)).isEqualTo("slow done");
    }

    @Test
    void crashedWaiterBlocksItsSpaceOnlyUntilTheLeasePlusTheLockWait() throws Exception {
        Duration wait = Duration.ofSeconds(1);
        Duration lease = Duration.ofSeconds(1);
        ConcurrencyProperties settings = settings(new Lease(wait, lease), new Permits(1, LONG, LONG));
        ConcurrencyProperties patientHolder = settings(new Lease(LONG, lease), new Permits(1, LONG, LONG));
        SpaceLocks holderInstance = new SpaceLocks(newInstance(), patientHolder, new SimpleMeterRegistry());
        SpaceLocks survivor = new SpaceLocks(newInstance(), settings, new SimpleMeterRegistry());
        RedissonClient crashing = newInstance();
        SpaceLocks doomed = new SpaceLocks(crashing, settings, new SimpleMeterRegistry());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> holderInstance.callWithLock(DATE, 10, () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holding.await(10, TimeUnit.SECONDS);
        long enqueued = System.nanoTime();
        threads.submit(() -> doomed.callWithLock(DATE, 10, () -> null));
        waitUntil(() -> queueLength(DATE, 10) == 1);

        crashing.shutdown(); // dies while queued: it never removes itself from the queue
        assertThat(queueLength(DATE, 10)).as("the dead request is still queued").isEqualTo(1);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        // Like a client retrying after each 503, until the dead entry expires.
        String result = null;
        int timedOut = 0;
        while (result == null) {
            try {
                result = survivor.callWithLock(DATE, 10, () -> "acquired despite the dead waiter");
            } catch (CapacityExceededException e) {
                timedOut++;
                assertThat(Duration.ofNanos(System.nanoTime() - enqueued)).isLessThan(Duration.ofSeconds(10));
            }
        }
        Duration blocked = Duration.ofNanos(System.nanoTime() - enqueued);

        assertThat(result).isEqualTo("acquired despite the dead waiter");
        // Lower bound: the dead entry really blocked the free space. Upper bound: it lives the holder's remaining
        // lease (at most `lease`) plus its own wait. Production: 20 s + 3 s.
        assertThat(timedOut).as("blocked at first").isPositive();
        assertThat(blocked).isGreaterThan(lease.plus(wait).minusMillis(500));
        assertThat(blocked).isLessThan(lease.plus(wait).plusSeconds(2));
    }

    @Test
    void semaphoreRecreatedAfterRedisDataLossOverAdmitsOnlyByTheHoldersStillRunningAndNeverInflates() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DbPermits permits = new DbPermits(newInstance(),
                settings(new Lease(LONG, LONG), new Permits(2, Duration.ofSeconds(1), LONG)), meters, POOL_SIZE);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch beforeLossHolding = new CountDownLatch(1);
        Future<?> beforeLoss = threads.submit(() -> permits.callWithPermit(() -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            beforeLossHolding.countDown();
            await(release);
            return running.decrementAndGet();
        }));
        beforeLossHolding.await(10, TimeUnit.SECONDS);

        // Redis loses its data while that transaction still runs.
        redisson.getKeys().deleteByPattern("*" + DbPermits.semaphoreName(2) + "*");
        CountDownLatch afterLossHolding = new CountDownLatch(2);
        List<Future<?>> afterLoss = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            afterLoss.add(threads.submit(() -> permits.callWithPermit(() -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                afterLossHolding.countDown();
                await(release);
                return running.decrementAndGet();
            })));
        }
        assertThat(afterLossHolding.await(10, TimeUnit.SECONDS)).as("both admitted once re-created").isTrue();
        release.countDown();
        beforeLoss.get(10, TimeUnit.SECONDS);
        for (Future<?> holder : afterLoss) {
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(maxRunning).as("the budget of 2 + the 1 holder admitted before the loss").hasValue(3);
        assertThat(redisson.getPermitExpirableSemaphore(DbPermits.semaphoreName(2))
                .availablePermits()).as("the old holder's release did not inflate the budget").isEqualTo(2);
        assertThat(meters.counter("parking.lease.expired", "kind", "db_permit").count()).isEqualTo(1);
    }

    @Test
    void lostReplyToAGrantedLockLeavesASingleHoldThatIsReleased() throws Exception {
        // The client gives up on its lock request (200 ms timeout) while Redis holds it (paused 300 ms) and then
        // grants it: the retry must end with one hold, released at the end, not a lock left until its lease.
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SpaceLocks locks = new SpaceLocks(connectedInstance(Duration.ofMillis(200)),
                settings(new Lease(Duration.ofSeconds(3), Duration.ofSeconds(20)), new Permits(1, LONG, LONG)), meters);

        pauseRedis(Duration.ofMillis(300));
        String result = locks.callWithLock(DATE, 12, () -> "work done");

        assertThat(result).isEqualTo("work done");
        assertThat(meters.counter("parking.space_lock.ambiguous_retry").count()).as("the lost reply was retried")
                .isEqualTo(1);
        assertThat(redisson.getFairLock(SpaceLocks.lockName(DATE, 12)).isLocked()).as("released").isFalse();
        assertThat(queueLength(DATE, 12)).isZero();
    }

    @Test
    void lostReplyWhileQueuedResumesTheQueuedRequest() throws Exception {
        // Same, while another request holds the lock: Redis queues the request after the client gave up. The retry
        // resumes that queue entry and gets the lock when the holder releases it, leaving no stale entry behind.
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ConcurrencyProperties settings = settings(new Lease(Duration.ofSeconds(5), Duration.ofSeconds(20)),
                new Permits(1, LONG, LONG));
        SpaceLocks holderInstance = new SpaceLocks(newInstance(), settings, new SimpleMeterRegistry());
        SpaceLocks impatient = new SpaceLocks(connectedInstance(Duration.ofMillis(200)), settings, meters);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> holderInstance.callWithLock(DATE, 13, () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        holding.await(10, TimeUnit.SECONDS);

        pauseRedis(Duration.ofMillis(300));
        Future<String> queued = threads.submit(() -> impatient.callWithLock(DATE, 13, () -> "acquired after the holder"));
        waitUntil(() -> meters.counter("parking.space_lock.ambiguous_retry").count() == 1);
        waitUntil(() -> queueLength(DATE, 13) == 1);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(queued.get(10, TimeUnit.SECONDS)).isEqualTo("acquired after the holder");
        assertThat(queueLength(DATE, 13)).isZero();
        assertThat(redisson.getFairLock(SpaceLocks.lockName(DATE, 13)).isLocked()).isFalse();
    }

    private ConcurrencyProperties settings(Lease spaceLock, Permits permits) {
        return new ConcurrencyProperties(spaceLock, permits, Duration.ofSeconds(60), 1000);
    }

    private static DbPermits permitsOn(RedissonClient instance, ConcurrencyProperties settings) {
        return new DbPermits(instance, settings, new SimpleMeterRegistry(), POOL_SIZE);
    }

    /** A fresh client with the application's own Redis settings: one more application instance. */
    private RedissonClient newInstance() {
        return newInstance(redisProperties.timeout());
    }

    /** The same, with another command timeout. */
    private RedissonClient newInstance(Duration timeout) {
        ParkingRedisProperties p = redisProperties;
        RedissonClient client = RedisConfig.createClient(new ParkingRedisProperties(p.address(), p.password(),
                p.connectTimeout(), timeout, p.retryAttempts(), p.retryDelay(), p.connectionPoolSize(),
                p.connectionMinimumIdleSize(), p.subscriptionConnectionPoolSize(),
                p.subscriptionConnectionMinimumIdleSize(), p.subscriptionsPerConnection()));
        instances.add(client);
        return client;
    }

    /**
     * A client whose connections are already open (lazy initialization would otherwise connect on first use, and a
     * paused Redis would delay the connection instead of the command under test).
     */
    private RedissonClient connectedInstance(Duration timeout) {
        RedissonClient client = newInstance(timeout);
        client.getKeys().count();
        return client;
    }

    /**
     * {@code CLIENT PAUSE ... ALL} over a raw connection: Redis keeps every command it receives meanwhile and runs it
     * afterwards, which is how a reply gets "lost" for a client with a shorter timeout.
     */
    private void pauseRedis(Duration duration) throws IOException {
        URI address = URI.create(redisProperties.address());
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            send(out, "AUTH", redisProperties.password());
            assertThat(in.readLine()).isEqualTo("+OK");
            send(out, "CLIENT", "PAUSE", Long.toString(duration.toMillis()), "ALL");
            assertThat(in.readLine()).isEqualTo("+OK");
        }
    }

    private static void send(OutputStream out, String... arguments) throws IOException {
        StringBuilder command = new StringBuilder("*").append(arguments.length).append("\r\n");
        for (String argument : arguments) {
            command.append('$').append(argument.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(argument).append("\r\n");
        }
        out.write(command.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Length of Redisson's fair-lock queue for one space (its internal key naming). */
    private int queueLength(LocalDate date, long spaceId) {
        return redisson.getList("redisson_lock_queue:{" + SpaceLocks.lockName(date, spaceId) + "}", StringCodec.INSTANCE)
                .size();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
