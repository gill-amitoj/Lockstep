package com.lockstep.ledger.web.dto;

/** Phase 4 removes ownerId: the owner will come from the authenticated user's JWT. */
public record CreateAccountRequest(String ownerId) {
}
