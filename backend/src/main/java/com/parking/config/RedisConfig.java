package com.parking.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ConstantDelay;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.parking.concurrency.ConcurrencyProperties;

/**
 * Redisson client for the reservation coordination (space locks, permits, confirmed-reservation cache).
 *
 * <p>Lazy initialization: the client connects on first use, so the application starts and serves everything that
 * does not need Redis (dashboard from the database, wallet, vehicles, login) while Redis is down. When Redis is up,
 * the first use is the permit initialization at startup, which also opens the idle connections.
 */
@Configuration
@EnableConfigurationProperties({ ParkingRedisProperties.class, ConcurrencyProperties.class })
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(ParkingRedisProperties redis) {
        return createClient(redis);
    }

    /**
     * A client with this application's settings (also used by tests that simulate more instances).
     *
     * <p>{@code retryAttempts} also bounds how often a waiter tries to get a pub/sub subscription, which a fair-lock
     * waiter needs to be woken. Too few attempts while a burst of waiters opens new subscription connections leaves
     * waiters asleep until their wait expires; pre-opened subscription connections avoid that burst.
     */
    public static RedissonClient createClient(ParkingRedisProperties redis) {
        Config config = new Config();
        config.setLazyInitialization(true);
        if (StringUtils.hasText(redis.password())) {
            config.setPassword(redis.password());
        }
        config.useSingleServer()
                .setAddress(redis.address())
                .setConnectTimeout(Math.toIntExact(redis.connectTimeout().toMillis()))
                .setTimeout(Math.toIntExact(redis.timeout().toMillis()))
                .setRetryAttempts(redis.retryAttempts())
                .setRetryDelay(new ConstantDelay(redis.retryDelay()))
                .setConnectionPoolSize(redis.connectionPoolSize())
                .setConnectionMinimumIdleSize(redis.connectionMinimumIdleSize())
                .setSubscriptionConnectionPoolSize(redis.subscriptionConnectionPoolSize())
                .setSubscriptionConnectionMinimumIdleSize(redis.subscriptionConnectionMinimumIdleSize())
                .setSubscriptionsPerConnection(redis.subscriptionsPerConnection());
        return Redisson.create(config);
    }
}
