package com.stockage.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stockage.common.StreamUtils;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StorageNode {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: StorageNode <listenPort> <dataDir> [peerHost:port ...]");
            System.exit(2);
        }

        int listenPort = Integer.parseInt(args[0]);
        Path dataDir = Path.of(args[1]);
        BlockManager blockManager = new BlockManager(dataDir);

        List<Peer> peers = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            peers.add(Peer.parse(args[i]));
        }

        if (!peers.isEmpty()) {
            Thread hb = new Thread(() -> heartbeatLoop(peers, blockManager, listenPort));
            hb.setDaemon(true);
            hb.start();
        }

        try (ServerSocket serverSocket = new ServerSocket(listenPort)) {
            System.out.println("StorageNode listening on port " + listenPort + " dataDir=" + dataDir.toAbsolutePath());
            while (true) {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> {
                    try {
                        handle(s, blockManager);
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    } finally {
                        try {
                            s.close();
                        } catch (Exception ignored) {
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void handle(Socket s, BlockManager blockManager) throws Exception {
        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
        var out = s.getOutputStream();

        String headerLine = StreamUtils.readUtf8Line(in);
        if (headerLine == null) {
            return;
        }

        JsonObject header = GSON.fromJson(headerLine, JsonObject.class);
        String type = header.get("type").getAsString();

        if ("STORE_BLOCK".equals(type)) {
            String cid = header.get("cid").getAsString();
            int index = header.get("index").getAsInt();
            int sizeBytes = header.get("sizeBytes").getAsInt();

            byte[] bytes = StreamUtils.readExactly(in, sizeBytes);
            blockManager.putBlock(cid, index, bytes);
            System.out.println("Stored cid=" + cid + " index=" + index + " bytes=" + sizeBytes);
            return;
        }

        if ("GET_BLOCK".equals(type)) {
            String cid = header.get("cid").getAsString();
            int index = header.get("index").getAsInt();
            byte[] bytes = blockManager.getBlock(cid, index);

            JsonObject resp = new JsonObject();
            resp.addProperty("type", "BLOCK_DATA");
            resp.addProperty("cid", cid);
            resp.addProperty("index", index);
            resp.addProperty("sizeBytes", bytes.length);

            StreamUtils.writeUtf8Line(out, GSON.toJson(resp));
            out.write(bytes);
            out.flush();
            return;
        }

        if ("HEARTBEAT".equals(type)) {
            JsonArray theirBlocks = header.getAsJsonArray("blocks");
            JsonArray myBlocks = buildBlockList(blockManager);
            JsonObject resp = new JsonObject();
            resp.addProperty("type", "HEARTBEAT_REPLY");
            resp.add("blocks", myBlocks);
            StreamUtils.writeUtf8Line(out, GSON.toJson(resp));

            // After reply, check if we are missing anything they have
            if (theirBlocks != null) {
                for (var el : theirBlocks) {
                    JsonObject b = el.getAsJsonObject();
                    String cid = b.get("cid").getAsString();
                    int index = b.get("index").getAsInt();
                    long theirTs = b.get("ts").getAsLong();
                    if (!blockManager.hasBlock(cid, index) || blockManager.getBlockTimestamp(cid, index) < theirTs) {
                        // mark for repair (will be done by our own heartbeat loop to this peer)
                    }
                }
            }
            return;
        }

        throw new IllegalStateException("Unsupported message type: " + type);
    }

    private static JsonArray buildBlockList(BlockManager bm) {
        JsonArray arr = new JsonArray();
        try {
            for (String cid : bm.listCids()) {
                for (int index : bm.listBlockIndices(cid)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("cid", cid);
                    o.addProperty("index", index);
                    o.addProperty("ts", bm.getBlockTimestamp(cid, index));
                    arr.add(o);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return arr;
    }

    private static void heartbeatLoop(List<Peer> peers, BlockManager bm, int myPort) {
        while (true) {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            for (Peer peer : peers) {
                try {
                    doHeartbeatWithPeer(peer, bm);
                } catch (Exception e) {
                    System.err.println("Heartbeat failed with peer " + peer.host + ":" + peer.port + " : " + e.getMessage());
                }
            }
        }
    }

    private static void doHeartbeatWithPeer(Peer peer, BlockManager bm) throws Exception {
        try (Socket s = new Socket(peer.host, peer.port)) {
            var out = s.getOutputStream();
            InputStream in = new BufferedInputStream(s.getInputStream());

            JsonObject req = new JsonObject();
            req.addProperty("type", "HEARTBEAT");
            req.add("blocks", buildBlockList(bm));
            StreamUtils.writeUtf8Line(out, GSON.toJson(req));

            String replyLine = StreamUtils.readUtf8Line(in);
            if (replyLine == null) {
                return;
            }
            JsonObject reply = GSON.fromJson(replyLine, JsonObject.class);
            if (!"HEARTBEAT_REPLY".equals(reply.get("type").getAsString())) {
                return;
            }
            JsonArray theirBlocks = reply.getAsJsonArray("blocks");
            if (theirBlocks == null) {
                return;
            }

            for (var el : theirBlocks) {
                JsonObject b = el.getAsJsonObject();
                String cid = b.get("cid").getAsString();
                int index = b.get("index").getAsInt();
                long theirTs = b.get("ts").getAsLong();

                if (!bm.hasBlock(cid, index) || bm.getBlockTimestamp(cid, index) < theirTs) {
                    // Fetch block from peer using existing GET_BLOCK protocol
                    byte[] blockData = fetchBlockFromPeer(peer, cid, index);
                    bm.putBlock(cid, index, blockData);
                    System.out.println("Repaired block from peer " + peer.host + ":" + peer.port + " cid=" + cid + " index=" + index);
                }
            }
        }
    }

    private static byte[] fetchBlockFromPeer(Peer peer, String cid, int index) throws Exception {
        try (Socket s = new Socket(peer.host, peer.port)) {
            var out = s.getOutputStream();
            InputStream in = new BufferedInputStream(s.getInputStream());

            JsonObject req = new JsonObject();
            req.addProperty("type", "GET_BLOCK");
            req.addProperty("cid", cid);
            req.addProperty("index", index);
            StreamUtils.writeUtf8Line(out, GSON.toJson(req));

            String respLine = StreamUtils.readUtf8Line(in);
            if (respLine == null) {
                throw new IllegalStateException("No reply for GET_BLOCK");
            }
            JsonObject resp = GSON.fromJson(respLine, JsonObject.class);
            if (!"BLOCK_DATA".equals(resp.get("type").getAsString())) {
                throw new IllegalStateException("Unexpected reply type");
            }
            int sizeBytes = resp.get("sizeBytes").getAsInt();
            return StreamUtils.readExactly(in, sizeBytes);
        }
    }

    private record Peer(String host, int port) {
        static Peer parse(String s) {
            int idx = s.lastIndexOf(':');
            if (idx <= 0 || idx == s.length() - 1) {
                throw new IllegalArgumentException("Invalid peer: " + s);
            }
            return new Peer(s.substring(0, idx), Integer.parseInt(s.substring(idx + 1)));
        }
    }
}
