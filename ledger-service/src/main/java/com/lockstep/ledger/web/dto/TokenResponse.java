package com.lockstep.ledger.web.dto;

/** Field names follow the OAuth 2.0 token response (RFC 6749 section 5.1), which clients already understand. */
public record TokenResponse(String access_token, String token_type, long expires_in) {
}
