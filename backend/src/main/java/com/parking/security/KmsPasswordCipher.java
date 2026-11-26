package com.parking.security;

import java.io.IOException;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.protobuf.ByteString;

/**
 * Cloud KMS symmetric encryption (AES-256-GCM inside KMS) of the BCrypt hash.
 * Authenticates with the Cloud Run service account (Application Default Credentials).
 * Fails fast at startup when KMS_KEY_NAME is missing: never falls back to the local cipher.
 */
@Component
@Profile("gcp")
public class KmsPasswordCipher implements PasswordCipher, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KmsPasswordCipher.class);

    private final String keyName;
    private final KeyManagementServiceClient client;

    public KmsPasswordCipher(@Value("${parking.kms.key-name:}") String keyName) throws IOException {
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalStateException("KMS_KEY_NAME must be set when the gcp profile is active");
        }
        this.keyName = keyName;
        this.client = KeyManagementServiceClient.create();
        log.info("Cloud KMS password cipher initialised for key {}", keyName);
    }

    @Override
    public String encrypt(String plaintext, String context) {
        EncryptRequest request = EncryptRequest.newBuilder()
                .setName(keyName)
                .setPlaintext(ByteString.copyFromUtf8(plaintext))
                .setAdditionalAuthenticatedData(ByteString.copyFromUtf8(context))
                .build();
        return Base64.getEncoder().encodeToString(client.encrypt(request).getCiphertext().toByteArray());
    }

    @Override
    public String decrypt(String ciphertext, String context) {
        DecryptRequest request = DecryptRequest.newBuilder()
                .setName(keyName)
                .setCiphertext(ByteString.copyFrom(Base64.getDecoder().decode(ciphertext)))
                .setAdditionalAuthenticatedData(ByteString.copyFromUtf8(context))
                .build();
        return client.decrypt(request).getPlaintext().toStringUtf8();
    }

    @Override
    public void destroy() {
        client.close();
    }
}
