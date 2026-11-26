package com.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V4 on a database that already holds data written under the earlier schema versions: nothing is lost or changed,
 * and the new keys, constraints and identity are in place. Runs Flyway directly on its own container, step by step
 * (V2, data, V3, data, V4), independent of the Spring context.
 */
class MigrationIT {

    /** Business columns per table (everything except the columns V4 adds), first column = row order. */
    private static final Map<String, String> BUSINESS_COLUMNS = new LinkedHashMap<>();

    static {
        BUSINESS_COLUMNS.put("users", "id, username, password_cipher, created_at");
        BUSINESS_COLUMNS.put("vehicles", "id, user_id, plate, created_at");
        BUSINESS_COLUMNS.put("wallets", "user_id, balance_cents, updated_at");
        BUSINESS_COLUMNS.put("parking_spaces", "id");
        BUSINESS_COLUMNS.put("reservations", "id, space_id, vehicle_id, user_id, reservation_date, price_cents, created_at");
        BUSINESS_COLUMNS.put("wallet_transactions",
                "id, user_id, type, amount_cents, reservation_id, idempotency_key, created_at");
    }

    private static PostgreSQLContainer postgres;
    private static DataSource dataSource;
    private static JdbcClient jdbc;
    private static Map<String, String> beforeV4;
    private static Map<String, String> afterV4;

    @BeforeAll
    static void migrateADatabaseThatAlreadyHoldsData() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);

        migrateTo("2");
        execute(
                "INSERT INTO users (username, password_cipher) VALUES ('alice', 'c-a'), ('bob', 'c-b'), ('carol', 'c-c')",
                "INSERT INTO wallets (user_id, balance_cents) SELECT id, 0 FROM users",
                "INSERT INTO vehicles (user_id, plate) SELECT id, upper(username) || '01' FROM users",
                // Deposits made before V3 have no idempotency key.
                "INSERT INTO wallet_transactions (user_id, type, amount_cents) SELECT id, 'DEPOSIT', 5000 FROM users",
                reservation("ALICE01", 1, "2026-01-01"),
                charges("2026-01-01"));

        migrateTo("3");
        execute(
                "INSERT INTO wallet_transactions (user_id, type, amount_cents, idempotency_key) SELECT id, 'DEPOSIT',"
                        + " 100000, '6f1c2a52-8a4e-4c43-9d1f-2b7f3f0c9a11' FROM users WHERE username = 'carol'",
                // Carol's fleet takes all 80 spaces on one date.
                "INSERT INTO vehicles (user_id, plate) SELECT u.id, 'FLEET' || lpad(g::text, 3, '0')"
                        + " FROM users u, generate_series(1, 80) g WHERE u.username = 'carol'",
                "INSERT INTO reservations (space_id, vehicle_id, user_id, reservation_date, price_cents)"
                        + " SELECT substring(plate FROM 6)::int, id, user_id, DATE '2026-01-02', 1000"
                        + " FROM vehicles WHERE plate LIKE 'FLEET%'",
                charges("2026-01-02"),
                reservation("BOB01", 5, "2026-01-03"),
                charges("2026-01-03"),
                "UPDATE wallets w SET balance_cents ="
                        + " (SELECT sum(amount_cents) FROM wallet_transactions t WHERE t.user_id = w.user_id)");

        beforeV4 = snapshot();
        migrateTo("4");
        afterV4 = snapshot();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void existingRowsAreUnchanged() {
        assertThat(beforeV4.get("reservations")).startsWith("82:");
        assertThat(beforeV4.get("wallet_transactions")).startsWith("86:");
        assertThat(afterV4).isEqualTo(beforeV4);
    }

    @Test
    void moneyAndBookingRulesStillHold() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM (SELECT w.user_id FROM wallets w
                    LEFT JOIN wallet_transactions t ON t.user_id = w.user_id
                    GROUP BY w.user_id, w.balance_cents
                    HAVING w.balance_cents <> COALESCE(SUM(t.amount_cents), 0)) v
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM (SELECT r.id FROM reservations r
                    LEFT JOIN wallet_transactions t ON t.reservation_id = r.id AND t.type = 'RESERVATION'
                    GROUP BY r.id HAVING count(t.id) <> 1) v
                """).query(Long.class).single()).isZero();
    }

    @Test
    void everyEntityTableHasANonNullVersionStartingAtZero() {
        for (String table : BUSINESS_COLUMNS.keySet()) {
            assertThat(column(table, "version")).as(table).isEqualTo("bigint NO 0 NO");
            assertThat(jdbc.sql("SELECT count(*) FROM " + table + " WHERE version <> 0").query(Long.class).single())
                    .as(table).isZero();
        }
    }

    @Test
    void walletsHaveTheirOwnGeneratedIdAndStayOnePerUser() {
        assertThat(column("wallets", "id")).isEqualTo("bigint NO nextval('wallets_id_seq'::regclass) NO");
        assertThat(jdbc.sql("SELECT count(DISTINCT id) FROM wallets").query(Long.class).single()).isEqualTo(3);
        assertThat(constraints("wallets")).contains(
                "wallets_pkey PRIMARY KEY (id)",
                "uk_wallets_user_id UNIQUE (user_id)",
                "wallets_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)",
                "ck_wallets_balance_non_negative CHECK ((balance_cents >= 0))");
    }

    @Test
    void spaceIdsAreBigintAndTheReservationConstraintsKeepTheirNames() {
        assertThat(column("parking_spaces", "id")).isEqualTo("bigint NO  YES");
        assertThat(column("reservations", "space_id")).isEqualTo("bigint NO  NO");
        assertThat(constraints("reservations")).contains(
                "reservations_pkey PRIMARY KEY (id)",
                "reservations_space_id_fkey FOREIGN KEY (space_id) REFERENCES parking_spaces(id)",
                "reservations_vehicle_id_fkey FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)",
                "reservations_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)",
                "uk_reservations_space_date UNIQUE (space_id, reservation_date)",
                "uk_reservations_vehicle_date UNIQUE (vehicle_id, reservation_date)");
        assertThat(constraints("wallet_transactions"))
                .contains("uk_wallet_transactions_user_idempotency_key UNIQUE (user_id, idempotency_key)")
                .anySatisfy(c -> assertThat(c).startsWith("ck_wallet_transactions_deposit_has_key").endsWith("NOT VALID"));
    }

    @Test
    void foreignKeyAndUniqueConstraintsAreStillEnforced() {
        assertThatThrownBy(() -> execute(reservation("ALICE01", 999, "2026-02-01")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("reservations_space_id_fkey");
        assertThatThrownBy(() -> execute(reservation("ALICE01", 1, "2026-01-02")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_reservations_space_date");
    }

    @Test
    void nextGeneratedSpaceIdContinuesAfterTheSeededSpaces() {
        Long next = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> {
            status.setRollbackOnly();
            return jdbc.sql("INSERT INTO parking_spaces DEFAULT VALUES RETURNING id").query(Long.class).single();
        });
        assertThat(next).isEqualTo(81L);
    }

    private static void migrateTo(String version) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(version).load().migrate();
    }

    private static void execute(String... statements) {
        for (String sql : statements) {
            jdbc.sql(sql).update();
        }
    }

    private static String reservation(String plate, int spaceId, String date) {
        return "INSERT INTO reservations (space_id, vehicle_id, user_id, reservation_date, price_cents) SELECT "
                + spaceId + ", id, user_id, DATE '" + date + "', 1000 FROM vehicles WHERE plate = '" + plate + "'";
    }

    private static String charges(String date) {
        return "INSERT INTO wallet_transactions (user_id, type, amount_cents, reservation_id)"
                + " SELECT user_id, 'RESERVATION', -price_cents, id FROM reservations WHERE reservation_date = DATE '"
                + date + "'";
    }

    /** Per table: row count and a checksum over every business column of every row. */
    private static Map<String, String> snapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        BUSINESS_COLUMNS.forEach((table, columns) -> {
            String orderBy = columns.split(",")[0];
            snapshot.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(ROW(" + columns
                    + ")::text, '|' ORDER BY " + orderBy + ")), '') FROM " + table).query(String.class).single());
        });
        return snapshot;
    }

    /** "data_type is_nullable column_default is_identity", e.g. "bigint NO 0 NO". */
    private static String column(String table, String column) {
        return jdbc.sql("""
                SELECT data_type || ' ' || is_nullable || ' ' || coalesce(column_default, '') || ' ' || is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """).param(table).param(column).query(String.class).single();
    }

    private static List<String> constraints(String table) {
        return jdbc.sql("SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass")
                .param(table).query(String.class).list();
    }
}
