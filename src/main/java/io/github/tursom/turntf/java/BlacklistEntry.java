package io.github.tursom.turntf.java;

public record BlacklistEntry(
    UserRef owner,
    UserRef blocked,
    String blockedAt,
    String deletedAt,
    long originNodeId
) {
}
