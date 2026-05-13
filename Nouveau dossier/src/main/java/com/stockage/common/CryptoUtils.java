package com.stockage.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public final class CryptoUtils {
    private CryptoUtils() {
    }

    public static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate RSA keypair", e);
        }
    }

    public static byte[] signSha256Rsa(PrivateKey privateKey, byte[] message) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Signature failed", e);
        }
    }

    public static boolean verifySha256Rsa(PublicKey publicKey, byte[] message, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Verification failed", e);
        }
    }

    public static String publicKeyHashHex(PublicKey publicKey) {
        return HexFormat.of().formatHex(sha256(publicKey.getEncoded()));
    }

    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static void saveKeyPair(Path dir, KeyPair kp) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("private.key"), Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("public.key"), Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save keypair", e);
        }
    }

    public static KeyPair loadKeyPair(Path dir) {
        try {
            String privB64 = Files.readString(dir.resolve("private.key"), StandardCharsets.UTF_8).trim();
            String pubB64 = Files.readString(dir.resolve("public.key"), StandardCharsets.UTF_8).trim();

            byte[] priv = Base64.getDecoder().decode(privB64);
            byte[] pub = Base64.getDecoder().decode(pubB64);

            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(priv));
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(pub));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load keypair", e);
        }
    }
}
