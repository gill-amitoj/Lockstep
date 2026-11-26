package com.settle.ledger;

import com.settle.ledger.domain.Account;
import com.settle.ledger.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Defence in depth: the database itself enforces the ledger rules, even for
 * raw SQL that bypasses the Java code. These tests talk to Postgres directly.
 */
class SchemaConstraintsTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void entriesCannotBeUpdated() {
        Transfer transfer = newTransfer();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE entries SET amount_cents = 1 WHERE transfer_id = ?", transfer.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void entriesCannotBeDeleted() {
        Transfer transfer = newTransfer();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM entries WHERE transfer_id = ?", transfer.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM entries WHERE transfer_id = ?", Long.class, transfer.getId()))
                .isEqualTo(2);
    }

    @Test
    void transfersCannotBeUpdatedOrDeleted() {
        Transfer transfer = newTransfer();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE transfers SET amount_cents = 1 WHERE id = ?", transfer.getId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transfers WHERE id = ?", transfer.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void customerBalanceCannotGoNegative() {
        Account alice = openFundedAccount(0);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE accounts SET balance_cents = -1 WHERE id = ?", alice.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("accounts_customer_non_negative");
    }

    @Test
    void transferToSameAccountIsRejectedByDatabase() {
        Account alice = openFundedAccount(0);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transfers (from_account_id, to_account_id, amount_cents, currency, created_at)
                VALUES (?, ?, 100, 'CAD', now())
                """, alice.getId(), alice.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("transfers_distinct_accounts");
    }

    @Test
    void nonPositiveTransferAmountIsRejectedByDatabase() {
        Account alice = openFundedAccount(0);
        Account bob = openFundedAccount(0);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transfers (from_account_id, to_account_id, amount_cents, currency, created_at)
                VALUES (?, ?, 0, 'CAD', now())
                """, alice.getId(), bob.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("transfers_amount_positive");
    }

    @Test
    void onlyOneSystemAccountAllowed() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO accounts (owner_id, type, currency, balance_cents, created_at)
                VALUES ('someone', 'SYSTEM', 'CAD', 0, now())
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("accounts_one_system_account");
    }

    @Test
    void onlyCadIsAllowed() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO accounts (owner_id, type, currency, balance_cents, created_at)
                VALUES ('someone', 'CUSTOMER', 'USD', 0, now())
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("accounts_currency_cad");
    }

    private Transfer newTransfer() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        return transferService.transfer(alice.getId(), bob.getId(), 100L);
    }
}
