package com.parking;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Test-only beans that replace production ones (by {@code @Primary}) for every integration test. */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfiguration {

    /** Movable time; IntegrationTestSupport resets it before each test. */
    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(Instant.now());
    }

    /** BCrypt cost 4 instead of 10: tests that create 150 accounts stay fast. Same algorithm, same code path. */
    @Bean
    @Primary
    PasswordEncoder fastPasswordEncoder() {
        return new BCryptPasswordEncoder(4);
    }
}
