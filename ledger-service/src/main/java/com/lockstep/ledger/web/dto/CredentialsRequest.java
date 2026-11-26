package com.lockstep.ledger.web.dto;

/** Body of POST /auth/register and POST /auth/token. */
public record CredentialsRequest(String username, String password) {

    /** Records print all fields in toString(); never let a password reach a log line. */
    @Override
    public String toString() {
        return "CredentialsRequest[username=" + username + ", password=***]";
    }
}
