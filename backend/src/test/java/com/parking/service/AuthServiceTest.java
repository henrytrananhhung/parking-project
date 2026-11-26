package com.parking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.parking.exception.BusinessException;
import com.parking.exception.ErrorCode;
import com.parking.dto.response.AuthResponse;
import com.parking.entity.UserEntity;
import com.parking.entity.WalletEntity;
import com.parking.repository.UserRepository;
import com.parking.repository.WalletRepository;
import com.parking.security.PasswordCipher;
import com.parking.security.TokenService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository users;
    @Mock
    WalletRepository wallets;
    @Mock
    PasswordCipher passwordCipher; // stands in for Cloud KMS
    @Mock
    TokenService tokenService;

    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // low cost: fast tests
    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, wallets, encoder, passwordCipher, tokenService, new TransactionTemplate(txManager));
    }

    @Test
    void registerStoresEncryptedBcryptHashAndCreatesWalletInOneTransaction() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(passwordCipher.encrypt(anyString(), eq("parking-user:alice"))).thenReturn("CIPHERTEXT");
        when(users.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> withId(inv.getArgument(0), 7L));
        when(tokenService.issue(7L, "alice")).thenReturn("jwt");

        AuthResponse result = service.register("  Alice ", "password123");

        verify(tokenService).issue(7L, "alice"); // the token is issued for the new user's id
        assertThat(result.username()).isEqualTo("alice");
        assertThat(result.token()).isEqualTo("jwt");

        // The value given to the cipher is a BCrypt hash of the password, never the password itself.
        ArgumentCaptor<String> plaintext = ArgumentCaptor.forClass(String.class);
        verify(passwordCipher).encrypt(plaintext.capture(), eq("parking-user:alice"));
        assertThat(plaintext.getValue()).startsWith("$2a$").isNotEqualTo("password123");
        assertThat(encoder.matches("password123", plaintext.getValue())).isTrue();

        ArgumentCaptor<UserEntity> savedUser = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(savedUser.capture());
        assertThat(savedUser.getValue().getPasswordCipher()).isEqualTo("CIPHERTEXT");

        ArgumentCaptor<WalletEntity> wallet = ArgumentCaptor.forClass(WalletEntity.class);
        verify(wallets).save(wallet.capture());
        assertThat(wallet.getValue().getUser().getId()).isEqualTo(7L);
        assertThat(wallet.getValue().getBalanceCents()).isZero();

        verify(txManager).commit(any());
    }

    @Test
    void registerRejectsExistingUsername() {
        when(users.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.register("alice", "password123"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.USERNAME_TAKEN);
        verify(passwordCipher, never()).encrypt(anyString(), anyString());
        verify(users, never()).saveAndFlush(any());
        verify(wallets, never()).save(any());
    }

    @Test
    void registerMapsConcurrentDuplicateToUsernameTakenAndRollsBack() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(passwordCipher.encrypt(anyString(), anyString())).thenReturn("CIPHERTEXT");
        when(users.saveAndFlush(any(UserEntity.class))).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_users_username\""));

        assertThatThrownBy(() -> service.register("alice", "password123"))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.USERNAME_TAKEN);
        verify(wallets, never()).save(any());
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    @Test
    void loginWithCorrectPasswordIssuesToken() {
        String hash = encoder.encode("password123");
        when(users.findByUsername("alice")).thenReturn(Optional.of(withId(new UserEntity("alice", "CIPHERTEXT"), 7L)));
        when(passwordCipher.decrypt("CIPHERTEXT", "parking-user:alice")).thenReturn(hash);
        when(tokenService.issue(7L, "alice")).thenReturn("jwt");

        AuthResponse result = service.login("ALICE", "password123");

        assertThat(result.token()).isEqualTo("jwt");
        verify(tokenService).issue(7L, "alice"); // the token is issued for the user's id
    }

    @Test
    void loginWithWrongPasswordIsRejected() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(withId(new UserEntity("alice", "CIPHERTEXT"), 7L)));
        when(passwordCipher.decrypt("CIPHERTEXT", "parking-user:alice")).thenReturn(encoder.encode("password123"));

        assertThatThrownBy(() -> service.login("alice", "wrong-password"))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(tokenService, never()).issue(anyLong(), anyString());
    }

    @Test
    void loginWithUnknownUserIsRejectedWithSameError() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost", "password123"))
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(passwordCipher, never()).decrypt(anyString(), anyString());
    }

    private static UserEntity withId(UserEntity user, long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
