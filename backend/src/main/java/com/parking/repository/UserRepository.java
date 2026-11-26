package com.parking.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.parking.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    /**
     * Takes the PostgreSQL transaction-level advisory lock {@code key}, waiting while another transaction holds
     * it. It is released at commit or rollback, so this only makes sense inside the caller's transaction.
     * Native SQL: a PostgreSQL function with no JPQL equivalent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT true FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
    boolean acquireTransactionLock(@Param("key") long key);
}
