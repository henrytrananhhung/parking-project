package com.parking.service;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.dto.response.AuthResponse;
import com.parking.entity.UserEntity;
import com.parking.entity.WalletEntity;
import com.parking.exception.BusinessException;
import com.parking.exception.Constraints;
import com.parking.exception.ErrorCode;
import com.parking.repository.UserRepository;
import com.parking.repository.WalletRepository;
import com.parking.security.PasswordCipher;
import com.parking.security.TokenService;

/**
 * Registration and login.
 * Stored password = PasswordCipher(BCrypt(password)), bound to the username.
 * BCrypt and KMS calls run outside the DB transaction so no connection is held during them;
 * the user row and its wallet are then created atomically in one transaction.
 */
@Service
public class AuthService {

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

    public AuthResponse register(String rawUsername, String password) {
        String username = normalize(rawUsername);
        long userId = createAccount(username, password);
        return new AuthResponse(tokenService.issue(userId, username), username);
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
                UserEntity user = users.saveAndFlush(new UserEntity(username, protectedHash));
                wallets.save(new WalletEntity(user));
                return user.getId();
            });
            return id;
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, UserEntity.UK_USERNAME)) {
                throw new BusinessException(ErrorCode.USERNAME_TAKEN);
            }
            throw e;
        }
    }

    public AuthResponse login(String rawUsername, String password) {
        String username = normalize(rawUsername);
        UserEntity user = users.findByUsername(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        String hash = passwordCipher.decrypt(user.getPasswordCipher(), context(username));
        if (!passwordEncoder.matches(password, hash)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return new AuthResponse(tokenService.issue(user.getId(), username), username);
    }

    static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    static String context(String username) {
        return "parking-user:" + username;
    }
}
