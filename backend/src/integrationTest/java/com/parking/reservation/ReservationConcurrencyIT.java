package com.parking.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.parking.IntegrationTestSupport;
import com.parking.common.BusinessException;
import com.parking.common.ErrorCode;

/**
 * Behaviour that Mockito cannot prove: real PostgreSQL constraints, row locks and transaction rollback.
 */
class ReservationConcurrencyIT extends IntegrationTestSupport {

    @Autowired
    ReservationService reservationService;

    @Test
    void manyUsersRacingForTheSameSpace_exactlyOneWinsAndOnlyTheWinnerPays() throws Exception {
        int users = 20;
        List<long[]> contenders = new ArrayList<>(); // {userId, vehicleId}
        for (int i = 0; i < users; i++) {
            long userId = newUser("racer" + i, BigDecimal.TEN);
            contenders.add(new long[] {userId, newVehicle(userId, "RACE" + String.format("%03d", i))});
        }

        List<Callable<ReservationService.ReservationResult>> tasks = new ArrayList<>();
        for (long[] c : contenders) {
            tasks.add(() -> reservationService.reserve(c[0], c[1], 1));
        }
        List<Outcome<ReservationService.ReservationResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.success())
                .extracting(Outcome::errorCode).containsOnly("SPACE_ALREADY_RESERVED").hasSize(users - 1);
        assertThat(count("SELECT count(*) FROM reservations WHERE space_id = 1")).isEqualTo(1);
        // Total money: 20 x $10 deposited, exactly one $10 charge.
        assertThat(count("SELECT sum(balance_cents) FROM wallets")).isEqualTo(users * 1000L - 1000L);
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'RESERVATION'")).isEqualTo(1);
    }

    @Test
    void sameVehicleRacingForDifferentSpaces_onlyOneReservationAndOneCharge() throws Exception {
        long userId = newUser("driver", new BigDecimal("100"));
        long vehicleId = newVehicle(userId, "51F12345");

        List<Callable<ReservationService.ReservationResult>> tasks = new ArrayList<>();
        for (int space = 1; space <= 10; space++) {
            int s = space;
            tasks.add(() -> reservationService.reserve(userId, vehicleId, s));
        }
        List<Outcome<ReservationService.ReservationResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.success())
                .extracting(Outcome::errorCode).containsOnly("VEHICLE_ALREADY_RESERVED");
        assertThat(count("SELECT count(*) FROM reservations WHERE vehicle_id = ?", vehicleId)).isEqualTo(1);
        assertThat(balanceCents(userId)).isEqualTo(9000L);
    }

    @Test
    void concurrentDeductionsNeverOverdrawTheWallet() throws Exception {
        // $30 balance, 10 vehicles, 10 different spaces at once: exactly 3 can be paid for.
        long userId = newUser("fleet", new BigDecimal("30"));
        List<Callable<ReservationService.ReservationResult>> tasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            long vehicleId = newVehicle(userId, "FLEET" + String.format("%02d", i));
            int space = 10 + i;
            tasks.add(() -> reservationService.reserve(userId, vehicleId, space));
        }
        List<Outcome<ReservationService.ReservationResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(Outcome::success).hasSize(3);
        assertThat(outcomes).filteredOn(o -> !o.success())
                .extracting(Outcome::errorCode).containsOnly("INSUFFICIENT_BALANCE").hasSize(7);
        assertThat(balanceCents(userId)).isZero();
        assertThat(count("SELECT count(*) FROM reservations WHERE user_id = ?", userId)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE user_id = ? AND type = 'RESERVATION'", userId))
                .isEqualTo(3);
    }

    @Test
    void failedReservationsRollBackCompletely() {
        long alice = newUser("alice", new BigDecimal("50"));
        long aliceCar = newVehicle(alice, "ALICE01");
        reservationService.reserve(alice, aliceCar, 5);

        // Space conflict: Bob is not charged and nothing is recorded for him.
        long bob = newUser("bob", new BigDecimal("50"));
        long bobCar = newVehicle(bob, "BOB0001");
        assertThatThrownBy(() -> reservationService.reserve(bob, bobCar, 5))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.SPACE_ALREADY_RESERVED);
        assertThat(balanceCents(bob)).isEqualTo(5000L);
        assertThat(count("SELECT count(*) FROM reservations WHERE user_id = ?", bob)).isZero();

        // Insufficient balance: the reservation row inserted before the debit is rolled back.
        long carol = newUser("carol", new BigDecimal("5"));
        long carolCar = newVehicle(carol, "CAROL01");
        assertThatThrownBy(() -> reservationService.reserve(carol, carolCar, 6))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        assertThat(balanceCents(carol)).isEqualTo(500L);
        assertThat(count("SELECT count(*) FROM reservations WHERE space_id = 6")).isZero();
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE user_id = ? AND type = 'RESERVATION'", carol))
                .isZero();

        // ...so space 6 is still free for someone who can pay.
        reservationService.reserve(bob, bobCar, 6);
        assertThat(balanceCents(bob)).isEqualTo(4000L);
    }

    @Test
    void realisticRace_manyUsersPickingRandomSpaces_eachPickedSpaceWonExactlyOnce() throws Exception {
        // The 8 PM shape: more users than spaces, overlapping picks. Fixed seed: same picks every run.
        int users = 150;
        Random random = new Random(20_261_126L);
        Set<Integer> pickedSpaces = new HashSet<>();
        List<Callable<ReservationService.ReservationResult>> tasks = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            long userId = newUser("user" + i, BigDecimal.TEN);
            long vehicleId = newVehicle(userId, "CAR" + String.format("%04d", i));
            int space = 1 + random.nextInt(80);
            pickedSpaces.add(space);
            tasks.add(() -> reservationService.reserve(userId, vehicleId, space));
        }

        List<Outcome<ReservationService.ReservationResult>> outcomes = runConcurrently(tasks);

        // Every picked space has exactly one winner; everyone else lost to a taken space and paid nothing.
        int winners = pickedSpaces.size();
        assertThat(outcomes).filteredOn(Outcome::success).hasSize(winners);
        assertThat(outcomes).filteredOn(o -> !o.success())
                .extracting(Outcome::errorCode).containsOnly("SPACE_ALREADY_RESERVED").hasSize(users - winners);
        assertThat(count("SELECT count(DISTINCT space_id) FROM reservations")).isEqualTo(winners);
        assertThat(count("SELECT sum(balance_cents) FROM wallets")).isEqualTo((users - winners) * 1000L);
    }

    @Test
    void winnerWhoCannotPayReleasesTheSpaceToTheNextInLine() throws Exception {
        // 20 rounds so the "broke user inserts first, then rolls back" interleaving actually happens.
        for (int space = 20; space < 40; space++) {
            long broke = newUser("broke" + space, new BigDecimal("5"));
            long brokeCar = newVehicle(broke, "BROKE" + space);
            long payer = newUser("payer" + space, BigDecimal.TEN);
            long payerCar = newVehicle(payer, "PAYER" + space);
            int s = space;

            List<Outcome<ReservationService.ReservationResult>> outcomes = runConcurrently(List.of(
                    () -> reservationService.reserve(broke, brokeCar, s),
                    () -> reservationService.reserve(payer, payerCar, s)));

            assertThat(outcomes.get(1).success()).as("paying user, space %d", s).isTrue();
            assertThat(outcomes.get(0).errorCode()).as("broke user, space %d", s)
                    .isIn("INSUFFICIENT_BALANCE", "SPACE_ALREADY_RESERVED");
        }
    }

    @Test
    void failureAfterTheDebitRollsTheDebitBack() {
        long userId = newUser("unlucky", BigDecimal.TEN);
        long vehicleId = newVehicle(userId, "UNLUCKY1");
        // Fault injection inside PostgreSQL: the reservation's ledger insert (after the debit) fails.
        jdbc.sql("""
                CREATE FUNCTION test_fail_reservation_charge() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'injected failure after the debit'; END $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_reservation_charge BEFORE INSERT ON wallet_transactions
                FOR EACH ROW WHEN (NEW.type = 'RESERVATION') EXECUTE FUNCTION test_fail_reservation_charge()
                """).update();
        try {
            assertThatThrownBy(() -> reservationService.reserve(userId, vehicleId, 9))
                    .hasStackTraceContaining("injected failure after the debit");
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_reservation_charge ON wallet_transactions").update();
            jdbc.sql("DROP FUNCTION test_fail_reservation_charge()").update();
        }

        assertThat(balanceCents(userId)).isEqualTo(1000L);
        assertThat(count("SELECT count(*) FROM reservations")).isZero();
    }

    @Test
    void unknownSpaceIsRejectedByTheForeignKeyWithoutCharging() {
        long userId = newUser("lost", new BigDecimal("10"));
        long vehicleId = newVehicle(userId, "LOST001");

        assertThatThrownBy(() -> reservationService.reserve(userId, vehicleId, 81))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.SPACE_NOT_FOUND);
        assertThat(balanceCents(userId)).isEqualTo(1000L);
        assertThat(count("SELECT count(*) FROM reservations")).isZero();
    }

    @Test
    void dashboardShowsAllEightySpacesWithPlateSuffixForReservedOnes() {
        long alice = newUser("alice", new BigDecimal("10"));
        reservationService.reserve(alice, newVehicle(alice, "51F12345"), 7);
        long bob = newUser("bob", BigDecimal.ZERO);

        ReservationService.Dashboard forAlice = reservationService.dashboard(alice);
        ReservationService.Dashboard forBob = reservationService.dashboard(bob);

        assertThat(forAlice.spaces()).hasSize(80);
        assertThat(forAlice.open()).isTrue();
        assertThat(forAlice.spaces().get(6)).isEqualTo(new ReservationService.SpaceStatus(7, true, "345", true));
        assertThat(forBob.spaces().get(6)).isEqualTo(new ReservationService.SpaceStatus(7, true, "345", false));
        assertThat(forBob.spaces()).filteredOn(ReservationService.SpaceStatus::reserved).hasSize(1);
        assertThat(reservationService.myReservations(alice)).singleElement()
                .satisfies(r -> assertThat(r.plate()).isEqualTo("51F12345"));
    }
}
