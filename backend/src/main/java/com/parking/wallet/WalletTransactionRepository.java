package com.parking.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long>, DepositLedgerRepository {
}
