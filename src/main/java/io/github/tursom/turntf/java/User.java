package io.github.tursom.turntf.java;

public record User(
    long nodeId,
    long userId,
    String username,
    String role,
    byte[] profileJson,
    boolean systemReserved,
    String createdAt,
    String updatedAt,
    long originNodeId
) {
}
