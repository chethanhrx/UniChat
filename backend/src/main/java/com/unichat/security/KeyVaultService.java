package com.unichat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class KeyVaultService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTE = 12;
    private static final int TAG_LENGTH_BIT = 128;

    private final SecretKeySpec keySpec;

    public KeyVaultService(@Value("${unichat.security.encryption-key}") String encryptionKey) {
        // Ensure 32 bytes for AES-256
        byte[] keyBytes = new byte[32];
        byte[] rawBytes = encryptionKey.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(rawBytes, 0, keyBytes, 0, Math.min(rawBytes.length, 32));
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plainApiKey) {
        if (plainApiKey == null || plainApiKey.isEmpty()) return plainApiKey;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] cipherText = cipher.doFinal(plainApiKey.getBytes(StandardCharsets.UTF_8));

            byte[] encryptedData = new byte[IV_LENGTH_BYTE + cipherText.length];
            System.arraycopy(iv, 0, encryptedData, 0, IV_LENGTH_BYTE);
            System.arraycopy(cipherText, 0, encryptedData, IV_LENGTH_BYTE, cipherText.length);

            return Base64.getEncoder().encodeToString(encryptedData);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt API key", e);
        }
    }

    public String decrypt(String encryptedApiKey) {
        if (encryptedApiKey == null || encryptedApiKey.isEmpty()) return encryptedApiKey;
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedApiKey);
            byte[] iv = new byte[IV_LENGTH_BYTE];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH_BYTE);

            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] cipherText = new byte[decoded.length - IV_LENGTH_BYTE];
            System.arraycopy(decoded, IV_LENGTH_BYTE, cipherText, 0, cipherText.length);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key", e);
        }
    }

    public String maskApiKey(String plainApiKey) {
        if (plainApiKey == null || plainApiKey.length() <= 8) {
            return "sk-••••••••";
        }
        String prefix = plainApiKey.substring(0, Math.min(6, plainApiKey.length() / 4));
        String suffix = plainApiKey.substring(plainApiKey.length() - 4);
        return prefix + "••••••••" + suffix;
    }
}
