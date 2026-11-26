package com.parking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Extends IntegrationTestSupport only to share its Spring context (and database container). */
class ParkingReservationApplicationTests extends IntegrationTestSupport {

    @Test
    void flywayMigratesSchemaAndSeedsEightyParkingSpaces() {
        assertThat(count("SELECT count(*) FROM parking_spaces")).isEqualTo(80);
    }
}
