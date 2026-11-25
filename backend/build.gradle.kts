plugins {
	java
	id("org.springframework.boot") version "4.2.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.parking"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	// Cloud SQL Java Connector: private IP + TLS handled by the socket factory (gcp profile only).
	runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.31.1")

	// Unit tests: JUnit 5 + Mockito + AssertJ. No Docker, no GCP, no network.
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Integration tests live in src/integrationTest and run only via `./gradlew integrationTest`
// (Testcontainers + real PostgreSQL, requires Docker). They are NOT part of `test` or `check`.
testing {
	suites {
		named<JvmTestSuite>("test") {
			useJUnitJupiter()
		}
		register<JvmTestSuite>("integrationTest") {
			useJUnitJupiter()
			dependencies {
				implementation(project())
				implementation("org.springframework.boot:spring-boot-starter-test")
				implementation("org.springframework.boot:spring-boot-testcontainers")
				implementation("org.testcontainers:testcontainers-junit-jupiter")
				implementation("org.testcontainers:testcontainers-postgresql")
			}
		}
	}
}

// Give integration tests the same compile/runtime classpath as the application itself.
configurations["integrationTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
