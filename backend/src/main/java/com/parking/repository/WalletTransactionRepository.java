package com.parking.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.parking.entity.WalletTransactionEntity;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, Long>, DepositLedgerRepository {
}
