package com.stockage.common;

/**
 * Network protocol messages for the distributed storage system.
 *
 * All messages are serialized to JSON using Gson and sent over TCP sockets.
 * Binary data (block contents) is sent after the JSON message header.
 */
public final class Message {

    private Message() {
    }

    public record LoginStart(String type, String username, String password, String publicKeyB64) {
    }

    public record Challenge(String type, String challengeB64) {
    }

    public record LoginProve(String type, String signatureB64) {
    }

    public record LoginOk(String type, String jwt) {
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

    public record ShareInit(String type, String cid, String target, String jwt, long ts, String popSigB64) {
    }

    public record ShareOk(String type, String cid, String target) {
    }

    public record StoreBlock(String type, String cid, int index, String hashHex, int sizeBytes) {
    }

    public record GetBlock(String type, String cid, int index) {
    }

    public record BlockData(String type, String cid, int index, int sizeBytes) {
    }

    public record Heartbeat(String type, BlockInfo[] blocks) {
    }

    public record HeartbeatReply(String type, BlockInfo[] blocks) {
    }

    public record BlockInfo(String cid, int index, long ts) {
    }

    public record ClientMetadata(String cid, String nonceB64) {
    }

    public record ErrorResponse(String type, String message) {
    }
}
