package com.stockage.client;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;

public final class FileEncryptor {
    public static final int AES_KEY_BITS = 256;
    public static final int NONCE_BYTES = 12;
    public static final int GCM_TAG_BITS = 128;

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final SecureRandom RNG = new SecureRandom();

    private FileEncryptor() {
    }

    public static SecretKey generateFileKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_BITS, RNG);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate AES key", e);
        }
    }

    public static EncryptedData encrypt(byte[] plaintext, SecretKey key) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RNG.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext);
            return new EncryptedData(ciphertextWithTag, nonce);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] ciphertextWithTag, SecretKey key, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return cipher.doFinal(ciphertextWithTag);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public static EncryptedData encryptFile(Path inputFile, SecretKey fileKey) {
        try {
            byte[] plaintext = Files.readAllBytes(inputFile);
            return encrypt(plaintext, fileKey);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read input file", e);
        }
    }

    public static void saveKeyToKeystore(Path keystorePath, char[] storePassword, String alias, SecretKey fileKey) {
        try {
            KeyStore ks = KeyStore.getInstance("JCEKS");
            if (Files.exists(keystorePath)) {
                try (var in = Files.newInputStream(keystorePath)) {
                    ks.load(in, storePassword);
                }
            } else {
                ks.load(null, storePassword);
            }

            KeyStore.SecretKeyEntry entry = new KeyStore.SecretKeyEntry(fileKey);
            KeyStore.ProtectionParameter prot = new KeyStore.PasswordProtection(storePassword);
            ks.setEntry(alias, entry, prot);

            Files.createDirectories(keystorePath.getParent() == null ? Path.of(".") : keystorePath.getParent());
            try (var out = Files.newOutputStream(keystorePath)) {
                ks.store(out, storePassword);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save key to keystore", e);
        }
    }

    public static SecretKey loadKeyFromKeystore(Path keystorePath, char[] storePassword, String alias) {
        try {
            KeyStore ks = KeyStore.getInstance("JCEKS");
            try (var in = Files.newInputStream(keystorePath)) {
                ks.load(in, storePassword);
            }

            Key key = ks.getKey(alias, storePassword);
            if (key == null) {
                throw new IllegalStateException("No key found for alias: " + alias);
            }
            if (!(key instanceof SecretKey)) {
                throw new IllegalStateException("Key for alias is not a SecretKey: " + alias);
            }
            return (SecretKey) key;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load key from keystore", e);
        }
    }

    public record EncryptedData(byte[] ciphertextWithTag, byte[] nonce) {
    }
}
