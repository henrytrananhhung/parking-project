package com.parking.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    /** Cloud KMS (or local AES in dev) ciphertext of the BCrypt hash. Never logged. */
    @Column(name = "password_cipher", nullable = false)
    private String passwordCipher;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserEntity() {
    }

    public UserEntity(String username, String passwordCipher) {
        this.username = username;
        this.passwordCipher = passwordCipher;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordCipher() {
        return passwordCipher;
    }

    @Override
    public String toString() {
        return "UserEntity{id=" + id + ", username=" + username + "}";
    }
}
