package com.stockage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileEncryptorTest {

    @Test
    void encryptDecryptRoundtrip() {
        byte[] plaintext = "Hello, distributed world!".getBytes();
        SecretKey key = FileEncryptor.generateFileKey();
        FileEncryptor.EncryptedData encrypted = FileEncryptor.encrypt(plaintext, key);

        assertNotNull(encrypted.ciphertextWithTag());
        assertNotNull(encrypted.nonce());
        assertEquals(FileEncryptor.NONCE_BYTES, encrypted.nonce().length);

        byte[] decrypted = FileEncryptor.decrypt(encrypted.ciphertextWithTag(), key, encrypted.nonce());
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void tamperedCiphertextFailsDecryption() {
        byte[] plaintext = "Sensitive data".getBytes();
        SecretKey key = FileEncryptor.generateFileKey();
        FileEncryptor.EncryptedData encrypted = FileEncryptor.encrypt(plaintext, key);

        // Tamper with the first byte of ciphertext
        encrypted.ciphertextWithTag()[0] ^= 0xFF;

        assertThrows(IllegalStateException.class, () ->
                FileEncryptor.decrypt(encrypted.ciphertextWithTag(), key, encrypted.nonce())
        );
    }

    @Test
    void keystoreRoundtrip(@TempDir Path tempDir) {
        Path ks = tempDir.resolve("test.ks");
        char[] pwd = "password".toCharArray();
        String alias = "file1";

        SecretKey original = FileEncryptor.generateFileKey();
        FileEncryptor.saveKeyToKeystore(ks, pwd, alias, original);

        SecretKey loaded = FileEncryptor.loadKeyFromKeystore(ks, pwd, alias);
        assertArrayEquals(original.getEncoded(), loaded.getEncoded());
    }
}
