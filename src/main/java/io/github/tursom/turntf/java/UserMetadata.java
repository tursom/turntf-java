package io.github.tursom.turntf.java;

/**
 * User-scoped metadata entry returned by turntf.
 *
 * <p>The SDK always keeps {@link #value()} as raw bytes so callers can share one metadata model
 * across HTTP JSON and websocket/protobuf transports. When the entry comes from the HTTP API, the
 * server may also expose a stable {@link #typedValue()} view for JSON-friendly reads. Realtime
 * websocket/protobuf responses never carry {@code typed_value}, so their {@link #typedValue()}
 * stays {@code null} even though {@link #value()} may still contain bytes such as {@code true} or
 * a JSON document.
 */
public record UserMetadata(
    UserRef owner,
    String key,
    byte[] value,
    UserMetadataTypedValue typedValue,
    String updatedAt,
    String deletedAt,
    String expiresAt,
    long originNodeId
) {
    /**
     * Backward-compatible constructor for raw-byte-only callers.
     */
    public UserMetadata(
        UserRef owner,
        String key,
        byte[] value,
        String updatedAt,
        String deletedAt,
        String expiresAt,
        long originNodeId
    ) {
        this(owner, key, value, null, updatedAt, deletedAt, expiresAt, originNodeId);
    }

    /**
     * Returns whether the HTTP API supplied a stable typed view for {@link #value()}.
     */
    public boolean hasTypedValue() {
        return typedValue != null;
    }
}
