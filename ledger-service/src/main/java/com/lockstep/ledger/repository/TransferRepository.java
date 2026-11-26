package com.lockstep.ledger.repository;

import com.lockstep.ledger.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
}
