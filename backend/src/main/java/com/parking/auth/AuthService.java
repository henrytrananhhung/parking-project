package com.parking.auth;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.common.BusinessException;
import com.parking.common.Constraints;
import com.parking.common.ErrorCode;
import com.parking.wallet.Wallet;
import com.parking.wallet.WalletRepository;

/**
 * Registration and login.
 * Stored password = PasswordCipher(BCrypt(password)), bound to the username.
 * BCrypt and KMS calls run outside the DB transaction so no connection is held during them;
 * the user row and its wallet are then created atomically in one transaction.
 */
@Service
public class AuthService {

    public record AuthResult(long userId, String username, String token) {

        @Override
        public String toString() {
            return "AuthResult{userId=" + userId + ", username=" + username + "}";
        }
    }

    private final UserRepository users;
    private final WalletRepository wallets;
    private final PasswordEncoder passwordEncoder;
    private final PasswordCipher passwordCipher;
    private final TokenService tokenService;
    private final TransactionTemplate tx;
    // BCrypt hash of a random value: unknown usernames cost the same as wrong passwords.
    private final String dummyHash;

    public AuthService(UserRepository users, WalletRepository wallets, PasswordEncoder passwordEncoder,
            PasswordCipher passwordCipher, TokenService tokenService, TransactionTemplate tx) {
        this.users = users;
        this.wallets = wallets;
        this.passwordEncoder = passwordEncoder;
        this.passwordCipher = passwordCipher;
        this.tokenService = tokenService;
        this.tx = tx;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthResult register(String rawUsername, String password) {
        String username = normalize(rawUsername);
        long userId = createAccount(username, password);
        return new AuthResult(userId, username, tokenService.issue(userId, username));
    }

    /** Creates user + wallet atomically. Joins the caller's transaction if one is active (demo seeding). */
    public long createAccount(String rawUsername, String password) {
        String username = normalize(rawUsername);
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        String protectedHash = passwordCipher.encrypt(passwordEncoder.encode(password), context(username));
        try {
            Long id = tx.execute(status -> {
                User user = users.saveAndFlush(new User(username, protectedHash));
                wallets.save(new Wallet(user.getId()));
                return user.getId();
            });
            return id;
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, "uk_users_username")) {
                throw new BusinessException(ErrorCode.USERNAME_TAKEN);
            }
            throw e;
        }
    }

    public AuthResult login(String rawUsername, String password) {
        String username = normalize(rawUsername);
        User user = users.findByUsername(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        String hash = passwordCipher.decrypt(user.getPasswordCipher(), context(username));
        if (!passwordEncoder.matches(password, hash)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return new AuthResult(user.getId(), username, tokenService.issue(user.getId(), username));
    }

    static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    static String context(String username) {
        return "parking-user:" + username;
    }
}
