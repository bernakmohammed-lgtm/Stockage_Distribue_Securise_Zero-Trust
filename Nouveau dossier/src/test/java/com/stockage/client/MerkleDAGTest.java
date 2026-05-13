package com.stockage.client;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class MerkleDAGTest {

    @Test
    void splitAndRebuild() {
        byte[] data = new byte[300 * 1024]; // 300KB => 2 blocks
        Arrays.fill(data, (byte) 0xAB);

        MerkleDAG.Dag dag = MerkleDAG.build(data);
        assertNotNull(dag.cid());
        assertEquals(2, dag.blocks().size());

        // Reassemble
        int total = dag.blocks().stream().mapToInt(b -> b.data().length).sum();
        byte[] rebuilt = new byte[total];
        int off = 0;
        for (MerkleDAG.Block b : dag.blocks()) {
            System.arraycopy(b.data(), 0, rebuilt, off, b.data().length);
            off += b.data().length;
        }
        assertArrayEquals(data, rebuilt);
    }

    @Test
    void cidIsDeterministic() {
        byte[] data = "test data for cid".getBytes();
        String cid1 = MerkleDAG.build(data).cid();
        String cid2 = MerkleDAG.build(data).cid();
        assertEquals(cid1, cid2);
    }

    @Test
    void cidChangesWithData() {
        byte[] d1 = "a".getBytes();
        byte[] d2 = "b".getBytes();
        String cid1 = MerkleDAG.build(d1).cid();
        String cid2 = MerkleDAG.build(d2).cid();
        assertNotEquals(cid1, cid2);
    }
}
