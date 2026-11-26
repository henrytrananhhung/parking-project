package com.parking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis connection settings ({@code parking.redis.*}). Named to avoid confusion with Spring Boot's own Redis
 * properties: this application talks to Redis through Redisson only.
 */
@ConfigurationProperties("parking.redis")
public record ParkingRedisProperties(
        String address,
        String password,
        Duration connectTimeout,
        Duration timeout,
        int retryAttempts,
        Duration retryDelay,
        int connectionPoolSize,
        int connectionMinimumIdleSize,
        int subscriptionConnectionPoolSize,
        int subscriptionConnectionMinimumIdleSize,
        int subscriptionsPerConnection) {

    /** Never print the password. */
    @Override
    public String toString() {
        return "ParkingRedisProperties[address=" + address + "]";
    }
}
