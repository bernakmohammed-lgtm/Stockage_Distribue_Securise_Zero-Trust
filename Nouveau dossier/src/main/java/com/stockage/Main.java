package com.stockage;

import com.stockage.client.Client;
import com.stockage.server.Server;
import com.stockage.storage.StorageNode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global launcher for the distributed storage system.
 *
 * This class provides a convenient way to start all components:
 * - Storage nodes (distributed block storage)
 * - Central server (authentication, access control, proxy re-encryption)
 * - Client (upload, download, share operations)
 *
 * Usage:
 *   java com.stockage.Main <mode> [args...]
 *
 * Modes:
 *   - storage-node <port> <dataDir> [peerHost:port ...]
 *   - server <port> [storageHost:port ...]
 *   - client upload <serverHost> <serverPort> <username> <password> <filePath> <keystorePath> <storePassword>
 *   - client download <serverHost> <serverPort> <username> <password> <cid> <outFilePath> <keystorePath> <storePassword>
 *   - client share <serverHost> <serverPort> <username> <password> <cid> <target>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String mode = args[0].toLowerCase();

        switch (mode) {
            case "storage-node":
                startStorageNode(args);
                break;
            case "server":
                startServer(args);
                break;
            case "client":
                startClient(args);
                break;
            case "demo":
                startDemo();
                break;
            default:
                System.err.println("Unknown mode: " + mode);
                printUsage();
                System.exit(1);
        }
    }

    private static void startStorageNode(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: Main storage-node <port> <dataDir> [peerHost:port ...]");
            System.exit(1);
        }

        int port = Integer.parseInt(args[1]);
        String dataDir = args[2];
        String[] peerArgs = new String[args.length - 3];
        System.arraycopy(args, 3, peerArgs, 0, peerArgs.length);

        String[] storageArgs = new String[2 + peerArgs.length];
        storageArgs[0] = String.valueOf(port);
        storageArgs[1] = dataDir;
        System.arraycopy(peerArgs, 0, storageArgs, 2, peerArgs.length);

        StorageNode.main(storageArgs);
    }

    private static void startServer(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Main server <port> [storageHost:port ...]");
            System.exit(1);
        }

        String[] serverArgs = new String[args.length - 1];
        System.arraycopy(args, 1, serverArgs, 0, serverArgs.length);

        Server.main(serverArgs);
    }

    private static void startClient(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Main client upload|download|share [args...]");
            System.exit(1);
        }

        String[] clientArgs = new String[args.length - 1];
        System.arraycopy(args, 1, clientArgs, 0, clientArgs.length);

        Client.main(clientArgs);
    }

    private static void startDemo() throws Exception {
        System.out.println("=== Distributed Storage Demo ===");
        System.out.println();

        ExecutorService executor = Executors.newCachedThreadPool();

        int serverPort = 9000;
        int node1Port = 9101;
        int node2Port = 9102;
        int node3Port = 9103;

        System.out.println("Starting storage nodes...");
        executor.submit(() -> {
            try {
                StorageNode.main(new String[]{
                        String.valueOf(node1Port),
                        "data/node1",
                        "127.0.0.1:" + node2Port,
                        "127.0.0.1:" + node3Port
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.submit(() -> {
            try {
                StorageNode.main(new String[]{
                        String.valueOf(node2Port),
                        "data/node2",
                        "127.0.0.1:" + node1Port,
                        "127.0.0.1:" + node3Port
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.submit(() -> {
            try {
                StorageNode.main(new String[]{
                        String.valueOf(node3Port),
                        "data/node3",
                        "127.0.0.1:" + node1Port,
                        "127.0.0.1:" + node2Port
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(2000);

        System.out.println("Starting central server...");
        executor.submit(() -> {
            try {
                Server.main(new String[]{
                        String.valueOf(serverPort),
                        "127.0.0.1:" + node1Port,
                        "127.0.0.1:" + node2Port,
                        "127.0.0.1:" + node3Port
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(2000);

        System.out.println();
        System.out.println("Demo environment ready!");
        System.out.println();
        System.out.println("To upload a file:");
        System.out.println("  java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client upload 127.0.0.1 " + serverPort + " alice alice <file> client.keystore password");
        System.out.println();
        System.out.println("To download a file:");
        System.out.println("  java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client download 127.0.0.1 " + serverPort + " alice alice <cid> <output> client.keystore password");
        System.out.println();
        System.out.println("To share a file:");
        System.out.println("  java -cp target/stockage-distribue-1.0.0-SNAPSHOT-all.jar com.stockage.Main client share 127.0.0.1 " + serverPort + " alice alice <cid> bob");
        System.out.println();
        System.out.println("Press Ctrl+C to stop all components.");
        System.out.println();

        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private static void printUsage() {
        System.err.println("Usage: Main <mode> [args...]");
        System.err.println();
        System.err.println("Modes:");
        System.err.println("  storage-node <port> <dataDir> [peerHost:port ...]");
        System.err.println("    Start a storage node with optional peer list for replication");
        System.err.println();
        System.err.println("  server <port> [storageHost:port ...]");
        System.err.println("    Start the central server with storage node targets");
        System.err.println();
        System.err.println("  client upload <serverHost> <serverPort> <username> <password> <filePath> <keystorePath> <storePassword>");
        System.err.println("    Upload a file to the distributed storage");
        System.err.println();
        System.err.println("  client download <serverHost> <serverPort> <username> <password> <cid> <outFilePath> <keystorePath> <storePassword>");
        System.err.println("    Download a file from the distributed storage");
        System.err.println();
        System.err.println("  client share <serverHost> <serverPort> <username> <password> <cid> <target>");
        System.err.println("    Share a file with another user");
        System.err.println();
        System.err.println("  demo");
        System.err.println("    Start a complete demo environment (3 nodes + server)");
    }
}
