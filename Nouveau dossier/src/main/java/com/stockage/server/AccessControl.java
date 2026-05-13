package com.stockage.server;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified access control for the distributed storage.
 *
 * LIMITATION (documented per requirements):
 * This is a simplified simulation. The server manages an ACL (owner + readers)
 * but does NOT perform real Proxy Re-Encryption. The AES file key remains
 * with the client owner; sharing the actual decryption key must be done
 * out-of-band (e.g. the owner sends it to the reader via a secure side-channel).
 * The server only authorizes the reader to fetch the encrypted blocks.
 */
public final class AccessControl {
    private static final Map<String, String> CID_OWNER = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> CID_READERS = new ConcurrentHashMap<>();

    private AccessControl() {
    }

    public static void registerOwner(String cid, String owner) {
        CID_OWNER.put(cid, owner);
        CID_READERS.computeIfAbsent(cid, k -> ConcurrentHashMap.newKeySet());
    }

    public static boolean isAuthorized(String cid, String username) {
        String owner = CID_OWNER.get(cid);
        if (owner == null) {
            return false;
        }
        if (owner.equals(username)) {
            return true;
        }
        Set<String> readers = CID_READERS.get(cid);
        return readers != null && readers.contains(username);
    }

    public static String getOwner(String cid) {
        return CID_OWNER.get(cid);
    }

    public static void addReader(String cid, String reader) {
        CID_READERS.computeIfAbsent(cid, k -> ConcurrentHashMap.newKeySet()).add(reader);
    }
}
