package com.lockstep.ledger.web;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.service.AccountService;
import com.lockstep.ledger.web.dto.AccountResponse;
import com.lockstep.ledger.web.dto.CreateAccountRequest;
import com.lockstep.ledger.web.dto.EntryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** HTTP only: parse the request, call the service, map the result to JSON. No business logic here. */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(@RequestBody CreateAccountRequest request) {
        Account account = accountService.openAccount(request.ownerId());
        return ResponseEntity
                .created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable Long id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @GetMapping("/{id}/entries")
    public List<EntryResponse> getEntries(@PathVariable Long id) {
        return accountService.getEntries(id).stream()
                .map(EntryResponse::from)
                .toList();
    }
}
