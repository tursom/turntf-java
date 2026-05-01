package io.github.tursom.turntf.java;

/**
 * User-scoped private metadata entry returned by turntf.
 *
 * <p>The SDK keeps {@code value} as raw bytes so callers can store arbitrary binary payloads
 * while HTTP transport details such as base64 remain an internal concern.
 */
public record UserMetadata(
    UserRef owner,
    String key,
    byte[] value,
    String updatedAt,
    String deletedAt,
    String expiresAt,
    long originNodeId
) {
}
