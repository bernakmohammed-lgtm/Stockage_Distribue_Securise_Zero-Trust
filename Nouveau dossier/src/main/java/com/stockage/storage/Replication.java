package com.stockage.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stockage.common.StreamUtils;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.util.List;

/**
 * Replication manager for distributed storage nodes.
 *
 * This module handles:
 * - Heartbeat between peer nodes
 * - Automatic repair of missing blocks
 * - Conflict resolution using Last-Writer-Wins (LWW) based on timestamps
 *
 * Each node periodically exchanges block metadata with peers and repairs
 * any missing or outdated blocks.
 */
public final class Replication {
    private static final Gson GSON = new Gson();
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private Replication() {
    }

    /**
     * Starts the heartbeat loop that periodically exchanges metadata with peer nodes.
     *
     * @param peers List of peer nodes to communicate with
     * @param blockManager The block manager for this node
     * @param myPort The port this node is listening on
     */
    public static void startHeartbeatLoop(List<Peer> peers, BlockManager blockManager, int myPort) {
        Thread hb = new Thread(() -> heartbeatLoop(peers, blockManager, myPort));
        hb.setDaemon(true);
        hb.start();
    }

    /**
     * Main heartbeat loop that runs indefinitely.
     * Sleeps for HEARTBEAT_INTERVAL_MS between each heartbeat round.
     */
    private static void heartbeatLoop(List<Peer> peers, BlockManager blockManager, int myPort) {
        while (true) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            for (Peer peer : peers) {
                try {
                    doHeartbeatWithPeer(peer, blockManager);
                } catch (Exception e) {
                    System.err.println("Heartbeat failed with peer " + peer.host + ":" + peer.port + " : " + e.getMessage());
                }
            }
        }
    }

    /**
     * Performs a single heartbeat exchange with a peer node.
     * Sends this node's block list and receives the peer's block list.
     * Repairs any missing or outdated blocks from the peer.
     *
     * @param peer The peer node to exchange with
     * @param bm The block manager for this node
     */
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
                    byte[] blockData = fetchBlockFromPeer(peer, cid, index);
                    bm.putBlock(cid, index, blockData);
                    System.out.println("Repaired block from peer " + peer.host + ":" + peer.port + " cid=" + cid + " index=" + index);
                }
            }
        }
    }

    /**
     * Fetches a specific block from a peer node.
     *
     * @param peer The peer node to fetch from
     * @param cid The Content Identifier of the block
     * @param index The block index
     * @return The block data
     * @throws Exception if the fetch fails
     */
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

    /**
     * Builds a JSON array listing all blocks stored by this node.
     *
     * @param bm The block manager
     * @return A JSON array of block metadata
     */
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

    /**
     * Parses a peer specification string in the format "host:port".
     *
     * @param s The peer specification string
     * @return A Peer object
     */
    public static Peer parsePeer(String s) {
        int idx = s.lastIndexOf(':');
        if (idx <= 0 || idx == s.length() - 1) {
            throw new IllegalArgumentException("Invalid peer: " + s);
        }
        return new Peer(s.substring(0, idx), Integer.parseInt(s.substring(idx + 1)));
    }

    /**
     * Represents a peer storage node.
     */
    public record Peer(String host, int port) {
    }
}
