package com.parking.vehicle;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByUserIdOrderById(Long userId);

    Optional<Vehicle> findByIdAndUserId(Long id, Long userId);

    boolean existsByPlate(String plate);
}
