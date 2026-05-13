package com.stockage;

import com.stockage.client.Client;
import com.stockage.server.Server;
import com.stockage.storage.StorageNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

public class IntegrationTest {

    private static final int SERVER_PORT = pickFreePort();
    private static final int NODE1_PORT = pickFreePort();
    private static final int NODE2_PORT = pickFreePort();
    private static final int NODE3_PORT = pickFreePort();

    private static ExecutorService executor;
    private static Path sharedTempDir;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        executor = Executors.newCachedThreadPool();
        sharedTempDir = java.nio.file.Files.createTempDirectory("stockage-test");

        Path node1Dir = sharedTempDir.resolve("node1");
        Path node2Dir = sharedTempDir.resolve("node2");
        Path node3Dir = sharedTempDir.resolve("node3");

        executor.submit(() -> {
            try {
                StorageNode.main(new String[]{String.valueOf(NODE1_PORT), node1Dir.toString(), "127.0.0.1:" + NODE2_PORT, "127.0.0.1:" + NODE3_PORT});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.submit(() -> {
            try {
                StorageNode.main(new String[]{String.valueOf(NODE2_PORT), node2Dir.toString(), "127.0.0.1:" + NODE1_PORT, "127.0.0.1:" + NODE3_PORT});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.submit(() -> {
            try {
                StorageNode.main(new String[]{String.valueOf(NODE3_PORT), node3Dir.toString(), "127.0.0.1:" + NODE1_PORT, "127.0.0.1:" + NODE2_PORT});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(500);

        executor.submit(() -> {
            try {
                Server.main(new String[]{String.valueOf(SERVER_PORT), "127.0.0.1:" + NODE1_PORT, "127.0.0.1:" + NODE2_PORT, "127.0.0.1:" + NODE3_PORT});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(500);
    }

    @AfterAll
    static void tearDown() throws Exception {
        executor.shutdownNow();
        if (sharedTempDir != null) {
            deleteDirectory(sharedTempDir);
        }
    }

    private static void deleteDirectory(Path path) throws Exception {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> {
                          try {
                              Files.delete(p);
                          } catch (Exception e) {
                              // Ignore
                          }
                      });
            }
        }
    }

    @Test
    void uploadAndDownloadRoundtrip() throws Exception {
        Path dir = tempDir.resolve("roundtrip");
        Files.createDirectories(dir);
        Path original = dir.resolve("original.txt");
        Files.writeString(original, "Hello distributed secure storage!");

        Path keystore = dir.resolve("client.ks");
        String storePassword = "password";

        Client.main(new String[]{"upload", "127.0.0.1", String.valueOf(SERVER_PORT), "alice", "alice", original.toString(), keystore.toString(), storePassword});

        String cid = readCidFromMetaDir(keystore);

        Path downloaded = dir.resolve("downloaded.txt");
        Client.main(new String[]{"download", "127.0.0.1", String.valueOf(SERVER_PORT), "alice", "alice", cid, downloaded.toString(), keystore.toString(), storePassword});

        assertEquals(Files.readString(original), Files.readString(downloaded));
    }

    @Test
    void unauthorizedAccessShouldFail() throws Exception {
        Path dir = tempDir.resolve("unauthorized");
        Files.createDirectories(dir);
        Path original = dir.resolve("secret.txt");
        Files.writeString(original, "Top secret");

        Path keystoreAlice = dir.resolve("alice.ks");
        Client.main(new String[]{"upload", "127.0.0.1", String.valueOf(SERVER_PORT), "alice", "alice", original.toString(), keystoreAlice.toString(), "password"});

        String cid = readCidFromMetaDir(keystoreAlice);

        Path keystoreBob = dir.resolve("bob.ks");
        Exception ex = assertThrows(Exception.class, () ->
                Client.main(new String[]{"download", "127.0.0.1", String.valueOf(SERVER_PORT), "bob", "bob", cid, dir.resolve("bob.txt").toString(), keystoreBob.toString(), "password"})
        );
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(msg.contains("Access denied") || msg.contains("Unknown CID") || msg.contains("Invalid client metadata")
                        || msg.contains("Unable to load key from keystore"),
                "Expected access failure but got: " + msg);
    }

    @Test
    void replicationSurvivesNodeFailure() throws Exception {
        Path dir = tempDir.resolve("replication");
        Files.createDirectories(dir);
        Path original = dir.resolve("big.txt");
        Files.writeString(original, "A".repeat(500 * 1024)); // >256KB => 2 blocks

        Path keystore = dir.resolve("client.ks");
        Client.main(new String[]{"upload", "127.0.0.1", String.valueOf(SERVER_PORT), "alice", "alice", original.toString(), keystore.toString(), "password"});

        String cid = readCidFromMetaDir(keystore);

        // Wait a bit for replication to complete
        Thread.sleep(1000);

        int nodesWithBlock0 = 0;
        for (Path d : List.of(sharedTempDir.resolve("node1"), sharedTempDir.resolve("node2"), sharedTempDir.resolve("node3"))) {
            if (Files.exists(d.resolve(cid).resolve("0.blk"))) {
                nodesWithBlock0++;
            }
        }
        assertTrue(nodesWithBlock0 >= 2, "Block 0 should be replicated on at least 2 nodes, found " + nodesWithBlock0);
    }

    private static String readCidFromMetaDir(Path keystorePath) throws Exception {
        Path metaDir = keystorePath.toAbsolutePath().getParent().resolve("client-meta");
        try (var stream = Files.list(metaDir)) {
            Path metaFile = stream.filter(p -> p.toString().endsWith(".json"))
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("No client metadata found in " + metaDir));
            String json = Files.readString(metaFile);
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.stockage.client.Client.ClientMetadata md = gson.fromJson(json, com.stockage.client.Client.ClientMetadata.class);
            return md.cid();
        }
    }

    private static int pickFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
