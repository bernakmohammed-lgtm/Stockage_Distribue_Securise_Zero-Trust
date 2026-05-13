package com.stockage.server;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy Re-Encryption module for the distributed storage system.
 *
 * LIMITATION (documented per requirements):
 * This is a simplified simulation. The server manages an ACL (owner + readers)
 * but does NOT perform real Proxy Re-Encryption. The AES file key remains
 * with the client owner; sharing the actual decryption key must be done
 * out-of-band (e.g. the owner sends it to the reader via a secure side-channel).
 * The server only authorizes the reader to fetch the encrypted blocks.
 *
 * In a full implementation, this would use cryptographic proxy re-encryption
 * schemes (e.g., ElGamal-based PRE) to transform ciphertext encrypted under
 * the owner's public key into ciphertext encrypted under the reader's public key,
 * without the server ever seeing the plaintext or the private keys.
 */
public final class ProxyReEncrypt {
    private static final Map<String, String> CID_OWNER = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> CID_READERS = new ConcurrentHashMap<>();

    private ProxyReEncrypt() {
    }

    /**
     * Registers the owner of a file identified by its CID.
     *
     * @param cid The Content Identifier (Merkle root hash) of the file
     * @param owner The username of the file owner
     */
    public static void registerOwner(String cid, String owner) {
        CID_OWNER.put(cid, owner);
        CID_READERS.computeIfAbsent(cid, k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Checks if a user is authorized to access a file.
     * Authorization is granted if the user is the owner or has been granted read access.
     *
     * @param cid The Content Identifier of the file
     * @param username The username to check authorization for
     * @return true if the user is authorized, false otherwise
     */
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

    /**
     * Retrieves the owner of a file.
     *
     * @param cid The Content Identifier of the file
     * @return The username of the owner, or null if not found
     */
    public static String getOwner(String cid) {
        return CID_OWNER.get(cid);
    }

    /**
     * Grants read access to a user for a file.
     * Only the owner can grant access to other users.
     *
     * In a full Proxy Re-Encryption implementation, this would:
     * 1. Generate a re-encryption key from owner's private key to reader's public key
     * 2. Store this re-encryption key on the server
     * 3. When the reader requests the file, the server would re-encrypt the AES key
     *    using the re-encryption key, without ever seeing the plaintext
     *
     * @param cid The Content Identifier of the file
     * @param reader The username to grant access to
     * @throws IllegalStateException if the file does not exist
     */
    public static void addReader(String cid, String reader) {
        if (CID_OWNER.get(cid) == null) {
            throw new IllegalStateException("File not found: " + cid);
        }
        CID_READERS.computeIfAbsent(cid, k -> ConcurrentHashMap.newKeySet()).add(reader);
    }

    /**
     * Removes read access from a user for a file.
     *
     * @param cid The Content Identifier of the file
     * @param reader The username to revoke access from
     */
    public static void removeReader(String cid, String reader) {
        Set<String> readers = CID_READERS.get(cid);
        if (readers != null) {
            readers.remove(reader);
        }
    }

    /**
     * Lists all readers of a file.
     *
     * @param cid The Content Identifier of the file
     * @return A set of usernames with read access
     */
    public static Set<String> listReaders(String cid) {
        return CID_READERS.getOrDefault(cid, Set.of());
    }
}
