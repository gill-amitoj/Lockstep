package com.lockstep.ledger.service;

import com.lockstep.ledger.domain.User;
import com.lockstep.ledger.repository.UserRepository;
import com.lockstep.ledger.security.Role;
import com.lockstep.ledger.security.TokenService;
import com.lockstep.ledger.security.TokenService.IssuedToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Registration and login. Passwords are only ever compared as BCrypt hashes. */
@Service
public class AuthService {

    /** Same rule as the users_username_valid CHECK constraint. No '#', so '#system' can never be a user. */
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,50}$");
    static final int MIN_PASSWORD_LENGTH = 12;
    /** BCrypt only looks at the first 72 bytes; Spring's encoder rejects longer input. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService audit;
    /**
     * Hash used when the username does not exist, so a failed login costs one
     * BCrypt check either way. Otherwise "unknown user" answers measurably faster
     * than "wrong password", and response times would reveal which usernames exist.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder,
                       TokenService tokenService, AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    /** Self-service sign-up always creates a CUSTOMER. Admins come only from AdminBootstrap. */
    @Transactional
    public User register(String username, String password) {
        return createUser(username, password, Role.CUSTOMER, null);
    }

    @Transactional
    public User createUser(String username, String password, Role role, String createdBy) {
        validateUsername(username);
        validatePassword(password);
        if (users.existsByUsername(username)) {
            throw new ConflictException("Username " + username + " is already taken");
        }
        // If two registrations race past the check above, the UNIQUE constraint
        // stops the second; ApiExceptionHandler turns that into a 409 too.
        User user = users.save(new User(username, passwordEncoder.encode(password), role));
        audit.record(createdBy == null ? username : createdBy, "USER_REGISTERED", "user", username,
                Map.of("role", role.name()));
        return user;
    }

    /**
     * Checks the password and issues a token. noRollbackFor keeps the
     * LOGIN_FAILED audit record even though the method then throws.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public IssuedToken login(String username, String password) {
        Optional<User> user = username == null ? Optional.empty() : users.findByUsername(username);
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = password != null && passwordEncoder.matches(password, hash);

        if (user.isEmpty() || !passwordMatches) {
            audit.record(safeActor(username), "LOGIN_FAILED", "user", safeActor(username), Map.of());
            throw new AuthenticationFailedException();
        }
        audit.record(username, "LOGIN_SUCCEEDED", "user", username, Map.of());
        return tokenService.issue(username, user.get().getRole());
    }

    private static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new InvalidRequestException(
                    "username must be 3-50 characters: lowercase letters, digits, '.', '_' or '-'");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidRequestException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRequestException("password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
    }

    /** Failed logins can carry anything as a username; keep the audit column within bounds. */
    private static String safeActor(String username) {
        if (username == null || username.isBlank()) {
            return "#anonymous";
        }
        return username.length() > 50 ? username.substring(0, 50) : username;
    }
}
