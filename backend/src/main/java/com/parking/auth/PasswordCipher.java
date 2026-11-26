package com.parking.auth;

/**
 * Encrypts the BCrypt password hash before it is stored (requirement: passwords encrypted with AES/RSA).
 * gcp profile: Cloud KMS. Other profiles: local AES-GCM, so development and tests need no GCP access.
 *
 * <p>{@code context} is bound as additional authenticated data, so a ciphertext copied to another user's
 * row cannot be decrypted.
 */
public interface PasswordCipher {

    /** @return base64 ciphertext */
    String encrypt(String plaintext, String context);

    /** @param ciphertext base64 ciphertext produced by {@link #encrypt} */
    String decrypt(String ciphertext, String context);
}
