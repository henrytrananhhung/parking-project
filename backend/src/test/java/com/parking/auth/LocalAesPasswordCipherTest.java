package com.parking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class LocalAesPasswordCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final LocalAesPasswordCipher cipher = new LocalAesPasswordCipher(KEY);

    @Test
    void encryptThenDecryptReturnsOriginal() {
        String ct = cipher.encrypt("$2a$10$hash", "parking-user:alice");

        assertThat(ct).doesNotContain("$2a$10$hash");
        assertThat(cipher.decrypt(ct, "parking-user:alice")).isEqualTo("$2a$10$hash");
    }

    @Test
    void sameInputProducesDifferentCiphertexts() {
        assertThat(cipher.encrypt("x", "ctx")).isNotEqualTo(cipher.encrypt("x", "ctx"));
    }

    @Test
    void ciphertextBoundToContextCannotBeUsedForAnotherUser() {
        String ct = cipher.encrypt("$2a$10$hash", "parking-user:alice");

        assertThatThrownBy(() -> cipher.decrypt(ct, "parking-user:mallory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Password decryption failed");
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("secret", "ctx"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "ctx"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeyThatIsNot256Bits() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());

        assertThatThrownBy(() -> new LocalAesPasswordCipher(shortKey)).isInstanceOf(IllegalStateException.class);
    }
}
