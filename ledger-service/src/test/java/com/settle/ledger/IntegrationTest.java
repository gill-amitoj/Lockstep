package com.settle.ledger;

import com.settle.ledger.domain.Account;
import com.settle.ledger.service.AccountService;
import com.settle.ledger.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.util.UUID;

/**
 * Base class for integration tests: full Spring context + real PostgreSQL.
 *
 * Every test class extends this with the SAME annotations, so Spring's test
 * context cache reuses one application context (and so one Postgres container)
 * across all classes instead of starting a new one each time.
 *
 * Tests do not clean the database between runs. Each test creates its own
 * fresh accounts and asserts only on those, so tests cannot interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected TransferService transferService;

    /** Opens a new customer account for a unique owner and deposits money into it from the SYSTEM account. */
    protected Account openFundedAccount(long depositCents) {
        Account account = accountService.openAccount("owner-" + UUID.randomUUID());
        if (depositCents > 0) {
            Long systemId = accountService.getSystemAccount().getId();
            transferService.transfer(systemId, account.getId(), depositCents);
        }
        return accountService.getAccount(account.getId());
    }
}
