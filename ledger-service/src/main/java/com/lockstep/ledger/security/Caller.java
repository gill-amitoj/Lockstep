package com.lockstep.ledger.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Who is making the current request. Controllers build it from the verified JWT
 * and pass it explicitly to the services, which make the ownership decisions.
 *
 * Passing it as a parameter (instead of reading a global security context deep
 * inside the services) keeps every authorization check visible in the method
 * signature and trivial to test.
 */
public record Caller(String username, Role role) {

    public static final String ROLES_CLAIM = "roles";

    public static Caller from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        Role role = roles != null && roles.contains(Role.ADMIN.name()) ? Role.ADMIN : Role.CUSTOMER;
        return new Caller(jwt.getSubject(), role);
    }

    public static Caller customer(String username) {
        return new Caller(username, Role.CUSTOMER);
    }

    public static Caller admin(String username) {
        return new Caller(username, Role.ADMIN);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Admins can access everything; customers only what they own. */
    public boolean canAccess(String ownerId) {
        return isAdmin() || username.equals(ownerId);
    }
}
