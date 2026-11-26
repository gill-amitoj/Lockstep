package com.lockstep.ledger.service;

/**
 * An HTTP response as the idempotency layer remembers it.
 *
 * @param status     HTTP status code of the original response
 * @param body       the exact JSON body of the original response
 * @param transferId the transfer it created (used to rebuild the Location header)
 * @param replayed   true if this is a stored response returned for a retry
 */
public record StoredResponse(int status, String body, Long transferId, boolean replayed) {
}
