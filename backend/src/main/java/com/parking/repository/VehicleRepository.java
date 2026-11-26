package com.parking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.parking.entity.VehicleEntity;

public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {

    List<VehicleEntity> findByUserIdOrderById(Long userId);

    Optional<VehicleEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByPlate(String plate);
}
