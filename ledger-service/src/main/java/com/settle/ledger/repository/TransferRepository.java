package com.settle.ledger.repository;

import com.settle.ledger.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
}
