package com.parking;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final String REDIS_PASSWORD = "test-redis";

	// Same major version as docker-compose and Cloud SQL.
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
	}

	// Same image and settings as docker-compose: password required, never evict coordination state.
	@Bean
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
				.withCommand("redis-server", "--requirepass", REDIS_PASSWORD, "--maxmemory-policy", "noeviction")
				.withExposedPorts(6379);
	}

	@Bean
	DynamicPropertyRegistrar redisProperties(GenericContainer<?> redisContainer) {
		return registry -> {
			registry.add("parking.redis.address",
					() -> "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
			registry.add("parking.redis.password", () -> REDIS_PASSWORD);
		};
	}

}
