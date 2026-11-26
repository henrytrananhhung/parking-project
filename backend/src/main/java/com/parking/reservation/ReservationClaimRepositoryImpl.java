package com.parking.reservation;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Spring Data fragment implementation, picked up by its {@code Impl} suffix. */
class ReservationClaimRepositoryImpl implements ReservationClaimRepository {

    private final JdbcClient jdbc;

    ReservationClaimRepositoryImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> insertIfFree(int spaceId, long vehicleId, long userId, LocalDate date, long priceCents) {
        return jdbc.sql("""
                INSERT INTO reservations (space_id, vehicle_id, user_id, reservation_date, price_cents)
                VALUES (:spaceId, :vehicleId, :userId, :date, :priceCents)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)
                .param("spaceId", spaceId)
                .param("vehicleId", vehicleId)
                .param("userId", userId)
                .param("date", date)
                .param("priceCents", priceCents)
                .query(Long.class)
                .optional();
    }
}
