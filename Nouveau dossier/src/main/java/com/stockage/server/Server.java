package com.stockage.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.stockage.common.StreamUtils;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

import com.stockage.common.CryptoUtils;

public final class Server {
    private static final Gson GSON = new Gson();
    private static final Map<String, Integer> CID_TO_BLOCKCOUNT = new ConcurrentHashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Server <listenPort> [storageHost:port ...]");
            System.exit(2);
        }

        int listenPort = Integer.parseInt(args[0]);
        List<StorageTarget> targets = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            targets.add(StorageTarget.parse(args[i]));
        }

        try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
            System.out.println("Server listening on port " + listenPort);
            while (true) {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> {
                    try {
                        handleClient(client, targets);
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    } finally {
                        try {
                            client.close();
                        } catch (Exception ignored) {
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void handleClient(Socket client, List<StorageTarget> targets) throws Exception {
        InputStream in = new BufferedInputStream(client.getInputStream());
        var out = client.getOutputStream();

        String jwt = null;
        String username = null;

        String initLine = StreamUtils.readUtf8Line(in);
        if (initLine == null) {
            return;
        }

        JsonObject init = GSON.fromJson(initLine, JsonObject.class);
        String type = init.get("type").getAsString();

        if ("LOGIN_START".equals(type)) {
            username = init.get("username").getAsString();
            String password = init.get("password").getAsString();
            String publicKeyB64 = init.get("publicKeyB64").getAsString();

            AuthHandler.verifyCredentials(username, password);
            AuthHandler.registerPublicKey(username, publicKeyB64);

            byte[] challenge = new byte[32];
            RNG.nextBytes(challenge);
            String challengeB64 = Base64.getEncoder().encodeToString(challenge);

            JsonObject resp = new JsonObject();
            resp.addProperty("type", "CHALLENGE");
            resp.addProperty("challengeB64", challengeB64);
            StreamUtils.writeUtf8Line(out, GSON.toJson(resp));

            String proveLine = StreamUtils.readUtf8Line(in);
            if (proveLine == null) {
                return;
            }
            JsonObject prove = GSON.fromJson(proveLine, JsonObject.class);
            if (!"LOGIN_PROVE".equals(prove.get("type").getAsString())) {
                throw new IllegalStateException("Expected LOGIN_PROVE");
            }
            String sigB64 = prove.get("signatureB64").getAsString();
            byte[] signature = Base64.getDecoder().decode(sigB64);

            PublicKey pk = AuthHandler.getRegisteredPublicKey(username);
            boolean ok = CryptoUtils.verifySha256Rsa(pk, challenge, signature);
            if (!ok) {
                throw new IllegalArgumentException("PoP verification failed");
            }

            String token = AuthHandler.loginAndIssueJwt(username);
            JsonObject okResp = new JsonObject();
            okResp.addProperty("type", "LOGIN_OK");
            okResp.addProperty("jwt", token);
            StreamUtils.writeUtf8Line(out, GSON.toJson(okResp));
            jwt = token;

            initLine = StreamUtils.readUtf8Line(in);
            if (initLine == null) {
                return;
            }
            init = GSON.fromJson(initLine, JsonObject.class);
            type = init.get("type").getAsString();
        }

        if ("DOWNLOAD_INIT".equals(type)) {
            String providedJwt = init.has("jwt") ? init.get("jwt").getAsString() : jwt;
            String subject = AuthHandler.verifyAndGetSubject(providedJwt);
            verifyPop(init, subject, providedJwt);

            String cid = init.get("cid").getAsString();
            if (!AccessControl.isAuthorized(cid, subject)) {
                throw new IllegalStateException("Access denied for user: " + subject);
            }
            Integer blockCount = CID_TO_BLOCKCOUNT.get(cid);
            if (blockCount == null) {
                throw new IllegalStateException("Unknown CID: " + cid);
            }

            JsonObject info = new JsonObject();
            info.addProperty("type", "DOWNLOAD_INFO");
            info.addProperty("cid", cid);
            info.addProperty("blockCount", blockCount);
            StreamUtils.writeUtf8Line(out, GSON.toJson(info));

            for (int i = 0; i < blockCount; i++) {
                byte[] block = fetchBlockFromStorageNodes(targets, cid, i);
                String hashHex = HexFormat.of().formatHex(sha256(block));

                JsonObject frame = new JsonObject();
                frame.addProperty("type", "BLOCK");
                frame.addProperty("cid", cid);
                frame.addProperty("index", i);
                frame.addProperty("hashHex", hashHex);
                frame.addProperty("sizeBytes", block.length);
                StreamUtils.writeUtf8Line(out, GSON.toJson(frame));
                out.write(block);
                out.flush();
            }
            return;
        }

        if ("SHARE_INIT".equals(type)) {
            String providedJwt = init.has("jwt") ? init.get("jwt").getAsString() : jwt;
            String subject = AuthHandler.verifyAndGetSubject(providedJwt);
            verifyPop(init, subject, providedJwt);

            String cid = init.get("cid").getAsString();
            String target = init.get("target").getAsString();
            if (!subject.equals(AccessControl.getOwner(cid))) {
                throw new IllegalStateException("Only owner can share");
            }
            AccessControl.addReader(cid, target);
            JsonObject resp = new JsonObject();
            resp.addProperty("type", "SHARE_OK");
            resp.addProperty("cid", cid);
            resp.addProperty("target", target);
            StreamUtils.writeUtf8Line(out, GSON.toJson(resp));
            return;
        }

        if (!"UPLOAD_INIT".equals(type)) {
            throw new IllegalStateException("Expected UPLOAD_INIT or DOWNLOAD_INIT, got: " + type);
        }

        String providedJwt = init.has("jwt") ? init.get("jwt").getAsString() : jwt;
        String subject = AuthHandler.verifyAndGetSubject(providedJwt);
        verifyPop(init, subject, providedJwt);

        String cid = init.get("cid").getAsString();
        int blockCount = init.get("blockCount").getAsInt();
        CID_TO_BLOCKCOUNT.put(cid, blockCount);
        AccessControl.registerOwner(cid, subject);
        System.out.println("Incoming upload CID=" + cid + " blocks=" + blockCount);

        for (int i = 0; i < blockCount; i++) {
            String frameLine = StreamUtils.readUtf8Line(in);
            if (frameLine == null) {
                throw new IllegalStateException("Unexpected EOF while reading block frame");
            }
            JsonObject frame = GSON.fromJson(frameLine, JsonObject.class);
            if (!"BLOCK".equals(frame.get("type").getAsString())) {
                throw new IllegalStateException("Expected BLOCK frame");
            }

            String frameCid = frame.get("cid").getAsString();
            int index = frame.get("index").getAsInt();
            String hashHex = frame.get("hashHex").getAsString();
            int sizeBytes = frame.get("sizeBytes").getAsInt();

            if (!cid.equals(frameCid)) {
                throw new IllegalStateException("CID mismatch");
            }

            byte[] blockBytes = StreamUtils.readExactly(in, sizeBytes);

            forwardToStorageNodes(targets, cid, index, hashHex, blockBytes);
            System.out.println("Stored block index=" + index + " size=" + sizeBytes);
        }

        String doneLine = StreamUtils.readUtf8Line(in);
        if (doneLine != null) {
            JsonObject done = GSON.fromJson(doneLine, JsonObject.class);
            if (done.has("type") && "UPLOAD_DONE".equals(done.get("type").getAsString())) {
                System.out.println("Upload done CID=" + cid);
            }
        }
    }

    private static void forwardToStorageNodes(List<StorageTarget> targets, String cid, int index, String hashHex, byte[] blockBytes) {
        int replication = Math.min(3, targets.size());
        if (replication <= 0) {
            return;
        }
        for (int i = 0; i < replication; i++) {
            StorageTarget t = targets.get((index + i) % targets.size());
            try (Socket s = new Socket(t.host, t.port)) {
                var out = s.getOutputStream();
                String header = GSON.toJson(new StoreBlock("STORE_BLOCK", cid, index, hashHex, blockBytes.length));
                StreamUtils.writeUtf8Line(out, header);
                out.write(blockBytes);
                out.flush();
            } catch (Exception e) {
                System.err.println("Forward failed to " + t.host + ":" + t.port + " for block " + index + ": " + e.getMessage());
            }
        }
    }

    private static byte[] fetchBlockFromStorageNodes(List<StorageTarget> targets, String cid, int index) {
        if (targets.isEmpty()) {
            throw new IllegalStateException("No storage targets configured");
        }

        int attempts = Math.min(3, targets.size());
        for (int i = 0; i < attempts; i++) {
            StorageTarget t = targets.get((index + i) % targets.size());
            try (Socket s = new Socket(t.host, t.port)) {
                var out = s.getOutputStream();
                InputStream in = new BufferedInputStream(s.getInputStream());

                JsonObject req = new JsonObject();
                req.addProperty("type", "GET_BLOCK");
                req.addProperty("cid", cid);
                req.addProperty("index", index);
                StreamUtils.writeUtf8Line(out, GSON.toJson(req));

                String respLine = StreamUtils.readUtf8Line(in);
                if (respLine == null) {
                    continue;
                }
                JsonObject resp = GSON.fromJson(respLine, JsonObject.class);
                if (!"BLOCK_DATA".equals(resp.get("type").getAsString())) {
                    continue;
                }
                int sizeBytes = resp.get("sizeBytes").getAsInt();
                return StreamUtils.readExactly(in, sizeBytes);
            } catch (Exception e) {
                System.err.println("Fetch failed from " + t.host + ":" + t.port + " for block " + index + ": " + e.getMessage());
            }
        }

        throw new IllegalStateException("Unable to fetch block index=" + index + " for cid=" + cid);
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void verifyPop(JsonObject init, String username, String jwt) {
        if (!init.has("ts") || !init.has("popSigB64")) {
            throw new IllegalArgumentException("Missing PoP fields");
        }
        long ts = init.get("ts").getAsLong();
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > 30_000L) {
            throw new IllegalArgumentException("PoP timestamp out of window");
        }

        String type = init.get("type").getAsString();
        String cid = init.has("cid") ? init.get("cid").getAsString() : "";
        String msg = jwt + "|" + type + "|" + cid + "|" + ts;

        byte[] sig = Base64.getDecoder().decode(init.get("popSigB64").getAsString());
        PublicKey pk = AuthHandler.getRegisteredPublicKey(username);
        boolean ok = CryptoUtils.verifySha256Rsa(pk, CryptoUtils.utf8(msg), sig);
        if (!ok) {
            throw new IllegalArgumentException("Invalid PoP signature");
        }
    }

    private record StorageTarget(String host, int port) {
        static StorageTarget parse(String s) {
            int idx = s.lastIndexOf(':');
            if (idx <= 0 || idx == s.length() - 1) {
                throw new IllegalArgumentException("Invalid storage target: " + s);
            }
            return new StorageTarget(s.substring(0, idx), Integer.parseInt(s.substring(idx + 1)));
        }
    }

    private record StoreBlock(String type, String cid, int index, String hashHex, int sizeBytes) {
    }
}
