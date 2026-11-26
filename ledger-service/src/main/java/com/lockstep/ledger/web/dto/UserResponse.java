package com.lockstep.ledger.web.dto;

import com.lockstep.ledger.domain.User;

import java.time.Instant;

/** Never includes the password hash. */
public record UserResponse(String username, String role, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getUsername(), user.getRole().name(), user.getCreatedAt());
    }
}
