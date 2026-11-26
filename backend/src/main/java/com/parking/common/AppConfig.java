package com.parking.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Injected everywhere time matters, so tests can use Clock.fixed(...) instead of mocking time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
