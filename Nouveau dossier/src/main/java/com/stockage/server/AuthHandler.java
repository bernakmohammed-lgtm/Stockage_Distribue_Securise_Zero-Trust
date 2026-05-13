package com.stockage.server;

import com.stockage.common.CryptoUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public final class AuthHandler {
    private static final Map<String, String> USERS = Map.of(
            "alice", "alice",
            "bob", "bob"
    );

    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(
            "change-me-to-a-long-random-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)
    );

    private static final Map<String, PublicKey> USER_PUBLIC_KEYS = new ConcurrentHashMap<>();

    private AuthHandler() {
    }

    public static void verifyCredentials(String username, String password) {
        String expected = USERS.get(username);
        if (expected == null || !expected.equals(password)) {
            throw new IllegalArgumentException("Invalid credentials");
        }
    }

    public static void registerPublicKey(String username, String publicKeyB64) {
        try {
            byte[] pub = Base64.getDecoder().decode(publicKeyB64);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(pub));
            USER_PUBLIC_KEYS.put(username, publicKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid public key", e);
        }
    }

    public static PublicKey getRegisteredPublicKey(String username) {
        PublicKey pk = USER_PUBLIC_KEYS.get(username);
        if (pk == null) {
            throw new IllegalStateException("No public key registered for user: " + username);
        }
        return pk;
    }

    public static String loginAndIssueJwt(String username) {
        PublicKey pk = getRegisteredPublicKey(username);
        String pkh = CryptoUtils.publicKeyHashHex(pk);

        Instant now = Instant.now();
        Instant exp = now.plusSeconds(5 * 60);

        return Jwts.builder()
                .subject(username)
                .claim("pkh", pkh)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(SIGNING_KEY)
                .compact();
    }

    public static Claims verifyAndGetClaims(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            throw new IllegalArgumentException("Missing token");
        }

        Jws<Claims> jws = Jwts.parser()
                .verifyWith(SIGNING_KEY)
                .build()
                .parseSignedClaims(jwt);

        return jws.getPayload();
    }

    public static String verifyAndGetSubject(String jwt) {
        return verifyAndGetClaims(jwt).getSubject();
    }
}
