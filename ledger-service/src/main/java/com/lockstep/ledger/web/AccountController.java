package com.lockstep.ledger.web;

import com.lockstep.ledger.domain.Account;
import com.lockstep.ledger.security.Caller;
import com.lockstep.ledger.service.AccountService;
import com.lockstep.ledger.web.dto.AccountResponse;
import com.lockstep.ledger.web.dto.EntryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * HTTP only: parse the request, call the service, map the result to JSON. No business logic here.
 * {@code @AuthenticationPrincipal Jwt} is the already-verified token for this request.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** No request body: the owner is always the authenticated caller. */
    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(@AuthenticationPrincipal Jwt jwt) {
        Account account = accountService.openAccount(Caller.from(jwt));
        return ResponseEntity
                .created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping
    public List<AccountResponse> listAccounts(@AuthenticationPrincipal Jwt jwt) {
        return accountService.listAccounts(Caller.from(jwt)).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return AccountResponse.from(accountService.getAccount(Caller.from(jwt), id));
    }

    @GetMapping("/{id}/entries")
    public List<EntryResponse> getEntries(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return accountService.getEntries(Caller.from(jwt), id).stream()
                .map(EntryResponse::from)
                .toList();
    }
}
