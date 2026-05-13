package com.stockage.client;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

public final class MerkleDAG {
    public static final int BLOCK_SIZE_BYTES = 256 * 1024;

    private MerkleDAG() {
    }

    public static Dag build(byte[] encryptedFileBytes) {
        List<Block> blocks = splitIntoBlocks(encryptedFileBytes);
        List<byte[]> leafHashes = blocks.stream().map(Block::hash).toList();
        byte[] root = merkleRoot(leafHashes);
        String cid = HexFormat.of().formatHex(root);
        return new Dag(cid, blocks);
    }

    public static List<Block> splitIntoBlocks(byte[] bytes) {
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        for (int offset = 0; offset < bytes.length; offset += BLOCK_SIZE_BYTES) {
            int len = Math.min(BLOCK_SIZE_BYTES, bytes.length - offset);
            byte[] data = Arrays.copyOfRange(bytes, offset, offset + len);
            byte[] hash = sha256(data);
            blocks.add(new Block(index++, hash, data));
        }
        return blocks;
    }

    public static byte[] merkleRoot(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            return sha256(new byte[0]);
        }

        List<byte[]> level = new ArrayList<>(leaves);
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : left;
                next.add(sha256(concat(left, right)));
            }
            level = next;
        }
        return level.get(0);
    }

    public static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public record Block(int index, byte[] hash, byte[] data) {
        public String hashHex() {
            return HexFormat.of().formatHex(hash);
        }
    }

    public record Dag(String cid, List<Block> blocks) {
    }
}
