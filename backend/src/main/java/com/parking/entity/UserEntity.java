package com.parking.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = UserEntity.UK_USERNAME, columnNames = "username"))
public class UserEntity extends AbstractEntity {

    public static final String UK_USERNAME = "uk_users_username";

    @Column(nullable = false, length = 50)
    private String username;

    /** Cloud KMS (or local AES in dev) ciphertext of the BCrypt hash. Never logged. */
    @Column(name = "password_cipher", nullable = false)
    private String passwordCipher;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserEntity() {
    }

    public UserEntity(String username, String passwordCipher) {
        this.username = username;
        this.passwordCipher = passwordCipher;
        this.createdAt = Instant.now();
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordCipher() {
        return passwordCipher;
    }

    @Override
    public String toString() {
        return "UserEntity{id=" + getId() + ", username=" + username + "}";
    }
}
