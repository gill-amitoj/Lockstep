package com.lockstep.fraud.web;

import com.lockstep.fraud.domain.FraudFlag;
import com.lockstep.fraud.repository.FraudFlagRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** ADMIN only (SecurityConfig). Newest flags first, optionally for one account. */
@RestController
@RequestMapping("/flags")
public class FlagController {

    private static final int MAX_LIMIT = 500;

    private final FraudFlagRepository flags;

    public FlagController(FraudFlagRepository flags) {
        this.flags = flags;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<FlagResponse> list(@RequestParam(required = false) Long accountId,
                                   @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        PageRequest page = PageRequest.of(0, limit);
        List<FraudFlag> result = accountId == null
                ? flags.findAllByOrderByIdDesc(page)
                : flags.findByAccountIdOrderByIdDesc(accountId, page);
        return result.stream().map(FlagResponse::from).toList();
    }

    public record FlagResponse(Long id, Long transferId, Long accountId, String rule, String reason,
                               long amountCents, Instant occurredAt, Instant flaggedAt) {

        static FlagResponse from(FraudFlag flag) {
            return new FlagResponse(flag.getId(), flag.getTransferId(), flag.getAccountId(), flag.getRule(),
                    flag.getReason(), flag.getAmountCents(), flag.getOccurredAt(), flag.getFlaggedAt());
        }
    }
}
