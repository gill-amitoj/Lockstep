package com.lockstep.ledger.service;

import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Entry;
import com.lockstep.ledger.domain.Transfer;
import com.lockstep.ledger.repository.EntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Business rules of a transfer, tested against a real database. */
class TransferServiceTest extends IntegrationTest {

    @Autowired
    private EntryRepository entryRepository;

    @Test
    void transferMovesMoneyAndWritesTwoBalancingEntries() {
        Account alice = openFundedAccount(10_000);
        Account bob = openFundedAccount(0);

        Transfer transfer = transferService.transfer(alice.getId(), bob.getId(), 2_500L);

        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(7_500);
        assertThat(accountService.getAccount(bob.getId()).getBalanceCents()).isEqualTo(2_500);

        List<Entry> entries = entryRepository.findByTransferIdOrderByIdAsc(transfer.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(Entry::getAccountId).containsExactly(alice.getId(), bob.getId());
        assertThat(entries).extracting(Entry::getAmountCents).containsExactly(-2_500L, 2_500L);
        assertThat(entries.stream().mapToLong(Entry::getAmountCents).sum()).isZero();
    }

    @Test
    void balanceAlwaysEqualsSumOfEntries() {
        Account alice = openFundedAccount(10_000);
        Account bob = openFundedAccount(5_000);

        transferService.transfer(alice.getId(), bob.getId(), 1_234L);
        transferService.transfer(bob.getId(), alice.getId(), 999L);
        transferService.transfer(alice.getId(), bob.getId(), 1L);

        for (Account account : List.of(alice, bob)) {
            long balance = accountService.getAccount(account.getId()).getBalanceCents();
            assertThat(balance).isEqualTo(entryRepository.sumAmountByAccountId(account.getId()));
        }
    }

    @Test
    void canTransferEntireBalance() {
        Account alice = openFundedAccount(500);
        Account bob = openFundedAccount(0);

        transferService.transfer(alice.getId(), bob.getId(), 500L);

        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isZero();
    }

    @Test
    void rejectsInsufficientFundsAndWritesNothing() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        assertThatThrownBy(() -> transferService.transfer(alice.getId(), bob.getId(), 1_001L))
                .isInstanceOf(InsufficientFundsException.class);

        // The transaction rolled back: balances unchanged, no new entries.
        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(1_000);
        assertThat(accountService.getAccount(bob.getId()).getBalanceCents()).isZero();
        assertThat(accountService.getEntries(alice.getId())).hasSize(1); // only the deposit
        assertThat(accountService.getEntries(bob.getId())).isEmpty();
    }

    @Test
    void rejectsSameAccountTransfer() {
        Account alice = openFundedAccount(1_000);

        assertThatThrownBy(() -> transferService.transfer(alice.getId(), alice.getId(), 100L))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("same account");
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        for (long amount : new long[] {0, -1, -500}) {
            assertThatThrownBy(() -> transferService.transfer(alice.getId(), bob.getId(), amount))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("positive");
        }
        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(1_000);
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> transferService.transfer(null, 2L, 100L))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> transferService.transfer(1L, 2L, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsUnknownAccount() {
        Account alice = openFundedAccount(1_000);

        assertThatThrownBy(() -> transferService.transfer(alice.getId(), Long.MAX_VALUE, 100L))
                .isInstanceOf(NotFoundException.class);
        assertThat(accountService.getAccount(alice.getId()).getBalanceCents()).isEqualTo(1_000);
    }

    @Test
    void systemAccountFundsDepositsAndGoesNegative() {
        Account system = accountService.getSystemAccount();
        long systemBefore = system.getBalanceCents();

        Account alice = openFundedAccount(50_000);

        assertThat(alice.getBalanceCents()).isEqualTo(50_000);
        assertThat(accountService.getSystemAccount().getBalanceCents()).isEqualTo(systemBefore - 50_000);
        assertThat(accountService.getSystemAccount().getBalanceCents()).isNegative();
    }
}
