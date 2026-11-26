package com.lockstep.fraud.repository;

import com.lockstep.fraud.domain.FraudFlag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, Long> {

    List<FraudFlag> findAllByOrderByIdDesc(Pageable pageable);

    List<FraudFlag> findByAccountIdOrderByIdDesc(Long accountId, Pageable pageable);

    List<FraudFlag> findByTransferId(Long transferId);
}
