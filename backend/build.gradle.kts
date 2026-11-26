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

// Load Mockito as a Java agent explicitly (JDK 21+ warns about, and will block, dynamic self-attach).
val mockitoAgent: Configuration by configurations.creating

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
	// Cloud KMS client for password protection (used only by KmsPasswordCipher in the gcp profile).
	implementation(platform("com.google.cloud:libraries-bom:26.94.0"))
	implementation("com.google.cloud:google-cloud-kms")
	// Redis coordination (per-space fair lock, distributed semaphores, confirmed-reservation cache).
	implementation("org.redisson:redisson:4.8.0")

	// Unit tests: JUnit 5 + Mockito + AssertJ. No Docker, no GCP, no network.
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test>().configureEach {
	jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.singleFile}") })
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
				implementation("org.testcontainers:testcontainers-postgresql")
			}
		}
	}
}

// Give integration tests the same compile/runtime classpath as the application itself.
configurations["integrationTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())
