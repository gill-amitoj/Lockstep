package com.lockstep.ledger;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Entry;
import com.lockstep.ledger.security.Caller;
import com.lockstep.ledger.security.Role;
import com.lockstep.ledger.security.TokenService;
import com.lockstep.ledger.service.AccountService;
import com.lockstep.ledger.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

/**
 * Base class for integration tests: full Spring context + real PostgreSQL.
 *
 * Every test class extends this with the SAME annotations and properties, so
 * Spring's test context cache reuses one application context (and so one
 * Postgres container) across all classes instead of starting a new one each time.
 *
 * Tests do not clean the database between runs. Each test creates its own
 * fresh users and accounts and asserts only on those, so tests cannot interfere.
 */
@SpringBootTest(properties = {
        // Test-only signing key. It protects nothing real: it only exists inside
        // throwaway test containers. Production reads JWT_SECRET from the environment.
        "lockstep.jwt.secret=test-only-secret-do-not-use-anywhere-else-0123456789",
        // Tests trigger reconciliation themselves, at known moments.
        "lockstep.reconciliation.scheduled=false"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    /** An admin used for test setup (deposits, reading balances). */
    protected static final Caller ADMIN = Caller.admin("test-admin");

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected TransferService transferService;

    @Autowired
    protected TokenService tokenService;

    /** Opens an account for a brand-new customer and deposits money into it from the SYSTEM account. */
    protected Account openFundedAccount(long depositCents) {
        Caller owner = Caller.customer("user-" + UUID.randomUUID().toString().substring(0, 8));
        Account account = accountService.openAccount(owner);
        if (depositCents > 0) {
            Long systemId = accountService.getSystemAccount().getId();
            transferService.transfer(ADMIN, systemId, account.getId(), depositCents);
        }
        return accountService.getAccount(ADMIN, account.getId());
    }

    /** The customer who owns the account. */
    protected static Caller ownerOf(Account account) {
        return Caller.customer(account.getOwnerId());
    }

    protected long balanceOf(Long accountId) {
        return accountService.getAccount(ADMIN, accountId).getBalanceCents();
    }

    protected List<Entry> entriesOf(Long accountId) {
        return accountService.getEntries(ADMIN, accountId);
    }

    /** A real, signed "Authorization: Bearer ..." header value, exactly as /auth/token would issue. */
    protected String bearer(String username, Role role) {
        return "Bearer " + tokenService.issue(username, role).value();
    }

    protected String bearerFor(Account account) {
        return bearer(account.getOwnerId(), Role.CUSTOMER);
    }

    protected String adminBearer() {
        return bearer(ADMIN.username(), Role.ADMIN);
    }
}
