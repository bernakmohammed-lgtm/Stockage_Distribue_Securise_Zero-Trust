package com.stockage.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class BlockManager {
    private final Path baseDir;

    public BlockManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    public void putBlock(String cid, int index, byte[] bytes) throws IOException {
        Path p = blockPath(cid, index);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.writeString(tsPath(cid, index), String.valueOf(System.currentTimeMillis()), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public byte[] getBlock(String cid, int index) throws IOException {
        Path p = blockPath(cid, index);
        return Files.readAllBytes(p);
    }

    public boolean hasBlock(String cid, int index) {
        return Files.exists(blockPath(cid, index));
    }

    public long getBlockTimestamp(String cid, int index) {
        try {
            String s = Files.readString(tsPath(cid, index)).trim();
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    public List<String> listCids() throws IOException {
        if (!Files.exists(baseDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(baseDir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
        }
    }

    public List<Integer> listBlockIndices(String cid) throws IOException {
        Path dir = baseDir.resolve(cid);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".blk"))
                    .map(p -> Integer.parseInt(p.getFileName().toString().replace(".blk", "")))
                    .collect(Collectors.toList());
        }
    }

    private Path blockPath(String cid, int index) {
        return baseDir.resolve(cid).resolve(index + ".blk");
    }

    private Path tsPath(String cid, int index) {
        return baseDir.resolve(cid).resolve(index + ".ts");
    }
}
