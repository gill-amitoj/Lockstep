package com.lockstep.ledger.web;

import com.lockstep.ledger.domain.User;
import com.lockstep.ledger.security.TokenService.IssuedToken;
import com.lockstep.ledger.service.AuthService;
import com.lockstep.ledger.web.dto.CredentialsRequest;
import com.lockstep.ledger.web.dto.TokenResponse;
import com.lockstep.ledger.web.dto.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only endpoints that work without a token.
 * Trade-off: there is no rate limiting or lockout on /auth/token yet, so
 * passwords could be brute-forced. A real deployment would add both, plus MFA.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@RequestBody CredentialsRequest request) {
        User user = authService.register(request.username(), request.password());
        return UserResponse.from(user);
    }

    @PostMapping("/token")
    public TokenResponse token(@RequestBody CredentialsRequest request) {
        IssuedToken token = authService.login(request.username(), request.password());
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }
}
