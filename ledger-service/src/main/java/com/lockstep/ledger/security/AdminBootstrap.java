package com.lockstep.ledger.security;

import com.lockstep.ledger.repository.UserRepository;
import com.lockstep.ledger.service.AuditService;
import com.lockstep.ledger.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN at startup from the ADMIN_USERNAME / ADMIN_PASSWORD
 * environment variables, if set and the user doesn't exist yet. There is no API
 * to create admins, so this is the only way one can exist.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthService authService;
    private final UserRepository users;
    private final String username;
    private final String password;

    public AdminBootstrap(AuthService authService, UserRepository users,
                          @Value("${lockstep.admin.username:}") String username,
                          @Value("${lockstep.admin.password:}") String password) {
        this.authService = authService;
        this.users = users;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) {
            log.info("ADMIN_USERNAME/ADMIN_PASSWORD not set; no admin user bootstrapped");
            return;
        }
        if (users.existsByUsername(username)) {
            return;
        }
        authService.createUser(username, password, Role.ADMIN, AuditService.BOOTSTRAP_ACTOR);
        log.info("Bootstrapped admin user {}", username); // never log the password
    }
}
