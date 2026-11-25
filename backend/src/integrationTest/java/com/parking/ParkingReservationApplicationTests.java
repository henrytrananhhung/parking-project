package com.parking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class ParkingReservationApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void flywayMigratesSchemaAndSeedsEightyParkingSpaces() {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM parking_spaces", Integer.class);
		assertThat(count).isEqualTo(80);
	}

}
