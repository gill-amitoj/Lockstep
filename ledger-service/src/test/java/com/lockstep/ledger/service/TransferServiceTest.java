package com.lockstep.ledger.service;

import com.lockstep.ledger.IntegrationTest;
import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.domain.Entry;
import com.lockstep.ledger.domain.Transfer;
import com.lockstep.ledger.repository.EntryRepository;
import com.lockstep.ledger.security.Caller;
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

        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 2_500L);

        assertThat(balanceOf(alice.getId())).isEqualTo(7_500);
        assertThat(balanceOf(bob.getId())).isEqualTo(2_500);

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

        transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 1_234L);
        transferService.transfer(ownerOf(bob), bob.getId(), alice.getId(), 999L);
        transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 1L);

        for (Account account : List.of(alice, bob)) {
            long balance = balanceOf(account.getId());
            assertThat(balance).isEqualTo(entryRepository.sumAmountByAccountId(account.getId()));
        }
    }

    @Test
    void canTransferEntireBalance() {
        Account alice = openFundedAccount(500);
        Account bob = openFundedAccount(0);

        transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 500L);

        assertThat(balanceOf(alice.getId())).isZero();
    }

    @Test
    void rejectsInsufficientFundsAndWritesNothing() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        assertThatThrownBy(() -> transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 1_001L))
                .isInstanceOf(InsufficientFundsException.class);

        // The transaction rolled back: balances unchanged, no new entries.
        assertThat(balanceOf(alice.getId())).isEqualTo(1_000);
        assertThat(balanceOf(bob.getId())).isZero();
        assertThat(entriesOf(alice.getId())).hasSize(1); // only the deposit
        assertThat(entriesOf(bob.getId())).isEmpty();
    }

    @Test
    void rejectsSameAccountTransfer() {
        Account alice = openFundedAccount(1_000);

        assertThatThrownBy(() -> transferService.transfer(ownerOf(alice), alice.getId(), alice.getId(), 100L))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("same account");
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        for (long amount : new long[] {0, -1, -500}) {
            assertThatThrownBy(() -> transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), amount))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("positive");
        }
        assertThat(balanceOf(alice.getId())).isEqualTo(1_000);
    }

    @Test
    void rejectsMissingFields() {
        assertThatThrownBy(() -> transferService.transfer(ADMIN, null, 2L, 100L))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> transferService.transfer(ADMIN, 1L, 2L, null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsUnknownAccount() {
        Account alice = openFundedAccount(1_000);

        assertThatThrownBy(() -> transferService.transfer(ownerOf(alice), alice.getId(), Long.MAX_VALUE, 100L))
                .isInstanceOf(NotFoundException.class);
        assertThat(balanceOf(alice.getId())).isEqualTo(1_000);
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

    // ---- ownership (Phase 4) ----------------------------------------------

    @Test
    void customerCannotSendFromSomeoneElsesAccount() {
        Account alice = openFundedAccount(1_000);
        Account mallory = openFundedAccount(0);

        // Mallory tries to pull money out of Alice's account into her own.
        assertThatThrownBy(() -> transferService.transfer(ownerOf(mallory), alice.getId(), mallory.getId(), 500L))
                .isInstanceOf(NotFoundException.class); // 404, not 403: don't confirm the account exists

        assertThat(balanceOf(alice.getId())).isEqualTo(1_000);
        assertThat(balanceOf(mallory.getId())).isZero();
    }

    @Test
    void customerCanSendToAccountTheyDoNotOwn() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);

        transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 300L);

        assertThat(balanceOf(bob.getId())).isEqualTo(300);
    }

    /** Regression for the hole V3 closed: a customer named "system" must not own the funding account. */
    @Test
    void customerNamedSystemCannotDrainTheSystemAccount() {
        Account mallory = openFundedAccount(0);
        Long systemId = accountService.getSystemAccount().getId();

        assertThatThrownBy(() -> transferService.transfer(
                Caller.customer("system"), systemId, mallory.getId(), 1_000_000L))
                .isInstanceOf(NotFoundException.class);
        assertThat(accountService.getSystemAccount().getOwnerId()).isEqualTo("#system");
    }

    @Test
    void customerSeesOnlyTransfersTheyArePartyTo() {
        Account alice = openFundedAccount(1_000);
        Account bob = openFundedAccount(0);
        Account eve = openFundedAccount(0);
        Transfer transfer = transferService.transfer(ownerOf(alice), alice.getId(), bob.getId(), 100L);

        assertThat(transferService.getTransfer(ownerOf(alice), transfer.getId()).getId()).isEqualTo(transfer.getId());
        assertThat(transferService.getTransfer(ownerOf(bob), transfer.getId()).getId()).isEqualTo(transfer.getId());
        assertThatThrownBy(() -> transferService.getTransfer(ownerOf(eve), transfer.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> accountService.getAccount(ownerOf(eve), alice.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
