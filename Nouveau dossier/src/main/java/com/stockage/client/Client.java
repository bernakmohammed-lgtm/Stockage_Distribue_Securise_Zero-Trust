package com.stockage.client;

import com.google.gson.Gson;
import com.stockage.common.StreamUtils;
import com.stockage.common.CryptoUtils;

import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

public final class Client {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Client upload <serverHost> <serverPort> <username> <password> <filePath> <keystorePath> <storePassword> | Client download <serverHost> <serverPort> <username> <password> <cid> <outFilePath> <keystorePath> <storePassword> | Client share <serverHost> <serverPort> <username> <password> <cid> <target>");
            System.exit(2);
        }

        String mode = args[0];
        if ("upload".equalsIgnoreCase(mode)) {
            if (args.length < 8) {
                System.err.println("Usage: Client upload <serverHost> <serverPort> <username> <password> <filePath> <keystorePath> <storePassword>");
                System.exit(2);
            }
            String serverHost = args[1];
            int serverPort = Integer.parseInt(args[2]);
            String username = args[3];
            String password = args[4];
            Path filePath = Path.of(args[5]);
            Path keystorePath = Path.of(args[6]);
            char[] storePassword = args[7].toCharArray();

            upload(serverHost, serverPort, username, password, filePath, keystorePath, storePassword);
            return;
        }

        if ("download".equalsIgnoreCase(mode)) {
            if (args.length < 9) {
                System.err.println("Usage: Client download <serverHost> <serverPort> <username> <password> <cid> <outFilePath> <keystorePath> <storePassword>");
                System.exit(2);
            }
            String serverHost = args[1];
            int serverPort = Integer.parseInt(args[2]);
            String username = args[3];
            String password = args[4];
            String cid = args[5];
            Path outFilePath = Path.of(args[6]);
            Path keystorePath = Path.of(args[7]);
            char[] storePassword = args[8].toCharArray();

            download(serverHost, serverPort, username, password, cid, outFilePath, keystorePath, storePassword);
            return;
        }

        if ("share".equalsIgnoreCase(mode)) {
            if (args.length < 7) {
                System.err.println("Usage: Client share <serverHost> <serverPort> <username> <password> <cid> <target>");
                System.exit(2);
            }
            String serverHost = args[1];
            int serverPort = Integer.parseInt(args[2]);
            String username = args[3];
            String password = args[4];
            String cid = args[5];
            String target = args[6];

            share(serverHost, serverPort, username, password, cid, target);
            return;
        }

        throw new IllegalArgumentException("Unknown mode: " + mode);
    }

    private static void upload(String serverHost, int serverPort, String username, String password, Path filePath, Path keystorePath, char[] storePassword) throws Exception {
        SecretKey fileKey = FileEncryptor.generateFileKey();
        FileEncryptor.EncryptedData encrypted = FileEncryptor.encryptFile(filePath, fileKey);

        MerkleDAG.Dag dag = MerkleDAG.build(encrypted.ciphertextWithTag());

        FileEncryptor.saveKeyToKeystore(keystorePath, storePassword, dag.cid(), fileKey);
        saveClientMetadata(keystorePath, dag.cid(), encrypted.nonce());

        try (Socket socket = new Socket(serverHost, serverPort)) {
            var out = socket.getOutputStream();
            InputStream in = new BufferedInputStream(socket.getInputStream());

            KeyPair kp = getOrCreateClientKeyPair(keystorePath, username);
            String jwt = login(in, out, username, password, kp);

            long ts = System.currentTimeMillis();
            String type = "UPLOAD_INIT";
            String cid = dag.cid();
            String nonceB64 = Base64.getEncoder().encodeToString(encrypted.nonce());
            int blockCount = dag.blocks().size();
            String filename = filePath.getFileName().toString();
            String sigB64 = createPopSigB64(kp, jwt, type, cid, ts);
            UploadInit initWithJwt = new UploadInit(type, filename, cid, nonceB64, blockCount, jwt, ts, sigB64);

            StreamUtils.writeUtf8Line(out, GSON.toJson(initWithJwt));

            for (MerkleDAG.Block block : dag.blocks()) {
                BlockFrame frame = new BlockFrame(
                        "BLOCK",
                        dag.cid(),
                        block.index(),
                        block.hashHex(),
                        block.data().length
                );

                StreamUtils.writeUtf8Line(out, GSON.toJson(frame));
                out.write(block.data());
                out.flush();
            }

            StreamUtils.writeUtf8Line(out, GSON.toJson(new SimpleMsg("UPLOAD_DONE", dag.cid())));
        }

        System.out.println("Upload finished. CID=" + dag.cid());
    }

    private static void download(String serverHost, int serverPort, String username, String password, String cid, Path outFilePath, Path keystorePath, char[] storePassword) throws Exception {
        byte[] nonce = loadClientMetadataNonce(keystorePath, cid);
        SecretKey fileKey = FileEncryptor.loadKeyFromKeystore(keystorePath, storePassword, cid);

        byte[] encryptedBytes;

        try (Socket socket = new Socket(serverHost, serverPort)) {
            var out = socket.getOutputStream();
            InputStream in = new BufferedInputStream(socket.getInputStream());

            KeyPair kp = getOrCreateClientKeyPair(keystorePath, username);
            String jwt = login(in, out, username, password, kp);

            long ts = System.currentTimeMillis();
            String sigB64 = createPopSigB64(kp, jwt, "DOWNLOAD_INIT", cid, ts);
            StreamUtils.writeUtf8Line(out, GSON.toJson(new DownloadInit("DOWNLOAD_INIT", cid, jwt, ts, sigB64)));

            String infoLine = StreamUtils.readUtf8Line(in);
            if (infoLine == null) {
                throw new IllegalStateException("No response from server");
            }
            DownloadInfo info = GSON.fromJson(infoLine, DownloadInfo.class);
            if (!"DOWNLOAD_INFO".equals(info.type) || !cid.equals(info.cid)) {
                throw new IllegalStateException("Unexpected server response: " + infoLine);
            }

            int blockCount = info.blockCount;
            byte[][] blocks = new byte[blockCount][];
            for (int i = 0; i < blockCount; i++) {
                String frameLine = StreamUtils.readUtf8Line(in);
                if (frameLine == null) {
                    throw new IllegalStateException("Unexpected EOF while reading block frame");
                }
                BlockFrame frame = GSON.fromJson(frameLine, BlockFrame.class);
                if (!"BLOCK".equals(frame.type) || !cid.equals(frame.cid) || frame.index != i) {
                    throw new IllegalStateException("Unexpected block frame: " + frameLine);
                }

                byte[] payload = StreamUtils.readExactly(in, frame.sizeBytes);
                String payloadHash = HexFormat.of().formatHex(sha256(payload));
                if (!payloadHash.equalsIgnoreCase(frame.hashHex)) {
                    throw new IllegalStateException("Block hash mismatch at index=" + i);
                }
                blocks[i] = payload;
            }

            int total = Arrays.stream(blocks).mapToInt(b -> b.length).sum();
            byte[] combined = new byte[total];
            int off = 0;
            for (byte[] b : blocks) {
                System.arraycopy(b, 0, combined, off, b.length);
                off += b.length;
            }
            encryptedBytes = combined;
        }

        String computedCid = MerkleDAG.build(encryptedBytes).cid();
        if (!cid.equalsIgnoreCase(computedCid)) {
            throw new IllegalStateException("CID mismatch: expected=" + cid + " computed=" + computedCid);
        }

        byte[] plaintext = FileEncryptor.decrypt(encryptedBytes, fileKey, nonce);
        Files.createDirectories(outFilePath.getParent() == null ? Path.of(".") : outFilePath.getParent());
        Files.write(outFilePath, plaintext);

        System.out.println("Download finished. Output=" + outFilePath.toAbsolutePath());
    }

    private static void share(String serverHost, int serverPort, String username, String password, String cid, String target) throws Exception {
        try (Socket socket = new Socket(serverHost, serverPort)) {
            var out = socket.getOutputStream();
            InputStream in = new BufferedInputStream(socket.getInputStream());

            // Use a dummy keystore path just to locate the client-keys directory for the keypair
            Path keystorePath = Path.of("client.keystore");
            KeyPair kp = getOrCreateClientKeyPair(keystorePath, username);
            String jwt = login(in, out, username, password, kp);

            long ts = System.currentTimeMillis();
            String sigB64 = createPopSigB64(kp, jwt, "SHARE_INIT", cid, ts);
            StreamUtils.writeUtf8Line(out, GSON.toJson(new ShareInit("SHARE_INIT", cid, target, jwt, ts, sigB64)));

            String respLine = StreamUtils.readUtf8Line(in);
            if (respLine == null) {
                throw new IllegalStateException("No share response from server");
            }
            ShareOk ok = GSON.fromJson(respLine, ShareOk.class);
            if (!"SHARE_OK".equals(ok.type)) {
                throw new IllegalStateException("Share failed: " + respLine);
            }
            System.out.println("Share OK: cid=" + ok.cid + " target=" + ok.target);
        }
    }

    private static String login(InputStream in, java.io.OutputStream out, String username, String password, KeyPair kp) throws Exception {
        String pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        StreamUtils.writeUtf8Line(out, GSON.toJson(new LoginStart("LOGIN_START", username, password, pubB64)));

        String challengeLine = StreamUtils.readUtf8Line(in);
        if (challengeLine == null) {
            throw new IllegalStateException("No challenge from server");
        }
        Challenge ch = GSON.fromJson(challengeLine, Challenge.class);
        if (!"CHALLENGE".equals(ch.type) || ch.challengeB64 == null) {
            throw new IllegalStateException("Unexpected challenge: " + challengeLine);
        }
        byte[] challenge = Base64.getDecoder().decode(ch.challengeB64);
        byte[] sig = CryptoUtils.signSha256Rsa(kp.getPrivate(), challenge);
        StreamUtils.writeUtf8Line(out, GSON.toJson(new LoginProve("LOGIN_PROVE", Base64.getEncoder().encodeToString(sig))));

        String respLine = StreamUtils.readUtf8Line(in);
        if (respLine == null) {
            throw new IllegalStateException("No login response from server");
        }
        LoginOk ok = GSON.fromJson(respLine, LoginOk.class);
        if (!"LOGIN_OK".equals(ok.type) || ok.jwt == null || ok.jwt.isBlank()) {
            throw new IllegalStateException("Login failed: " + respLine);
        }
        return ok.jwt;
    }

    private static KeyPair getOrCreateClientKeyPair(Path keystorePath, String username) {
        Path base = keystorePath.toAbsolutePath().getParent() == null ? Path.of("client-keys") : keystorePath.toAbsolutePath().getParent().resolve("client-keys");
        Path dir = base.resolve(username);
        if (Files.exists(dir.resolve("private.key")) && Files.exists(dir.resolve("public.key"))) {
            return CryptoUtils.loadKeyPair(dir);
        }
        KeyPair kp = CryptoUtils.generateRsaKeyPair();
        CryptoUtils.saveKeyPair(dir, kp);
        return kp;
    }

    private static String createPopSigB64(KeyPair kp, String jwt, String type, String cid, long ts) {
        String msg = jwt + "|" + type + "|" + cid + "|" + ts;
        byte[] sig = CryptoUtils.signSha256Rsa(kp.getPrivate(), CryptoUtils.utf8(msg));
        return Base64.getEncoder().encodeToString(sig);
    }

    private static void saveClientMetadata(Path keystorePath, String cid, byte[] nonce) throws Exception {
        Path metaDir = keystorePath.toAbsolutePath().getParent() == null
                ? Path.of("client-meta")
                : keystorePath.toAbsolutePath().getParent().resolve("client-meta");
        Files.createDirectories(metaDir);
        Path metaFile = metaDir.resolve(cid + ".json");
        ClientMetadata md = new ClientMetadata(cid, Base64.getEncoder().encodeToString(nonce));
        Files.writeString(metaFile, GSON.toJson(md), StandardCharsets.UTF_8);
    }

    private static byte[] loadClientMetadataNonce(Path keystorePath, String cid) throws Exception {
        Path metaDir = keystorePath.toAbsolutePath().getParent() == null
                ? Path.of("client-meta")
                : keystorePath.toAbsolutePath().getParent().resolve("client-meta");
        Path metaFile = metaDir.resolve(cid + ".json");
        String json = Files.readString(metaFile, StandardCharsets.UTF_8);
        ClientMetadata md = GSON.fromJson(json, ClientMetadata.class);
        if (md == null || md.nonceB64 == null || !cid.equals(md.cid)) {
            throw new IllegalStateException("Invalid client metadata for cid=" + cid);
        }
        return Base64.getDecoder().decode(md.nonceB64);
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record UploadInit(String type, String filename, String cid, String nonceB64, int blockCount, String jwt, long ts, String popSigB64) {
    }

    public record BlockFrame(String type, String cid, int index, String hashHex, int sizeBytes) {
    }

    public record SimpleMsg(String type, String cid) {
    }

    public record DownloadInit(String type, String cid, String jwt, long ts, String popSigB64) {
    }

    public record DownloadInfo(String type, String cid, int blockCount) {
    }

    public record ClientMetadata(String cid, String nonceB64) {
    }

    public record LoginStart(String type, String username, String password, String publicKeyB64) {
    }

    public record ShareInit(String type, String cid, String target, String jwt, long ts, String popSigB64) {
    }

    public record ShareOk(String type, String cid, String target) {
    }

    public record Challenge(String type, String challengeB64) {
    }

    public record LoginProve(String type, String signatureB64) {
    }

    public record LoginOk(String type, String jwt) {
    }
}
