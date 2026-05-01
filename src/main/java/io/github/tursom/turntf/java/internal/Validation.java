package io.github.tursom.turntf.java.internal;

import io.github.tursom.turntf.java.DeliveryMode;
import io.github.tursom.turntf.java.ProtocolError;
import io.github.tursom.turntf.java.SessionRef;
import io.github.tursom.turntf.java.UserRef;
import java.net.URI;
import java.net.URISyntaxException;

public final class Validation {
    private static final int USER_METADATA_KEY_MAX_LENGTH = 128;
    private static final int MAX_USER_METADATA_SCAN_LIMIT = 1000;

    private Validation() {
    }

    public static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
    }

    public static void validateUserRef(UserRef ref, String field) {
        requirePositive(ref.nodeId(), field + ".nodeId");
        requirePositive(ref.userId(), field + ".userId");
    }

    public static void validateSessionRef(SessionRef ref, String field) {
        requirePositive(ref.servingNodeId(), field + ".servingNodeId");
        if (ref.sessionId() == null || ref.sessionId().isEmpty()) {
            throw new IllegalArgumentException(field + ".sessionId is required");
        }
    }

    public static void validateDeliveryMode(DeliveryMode mode) {
        if (mode != DeliveryMode.BEST_EFFORT && mode != DeliveryMode.ROUTE_RETRY) {
            throw new IllegalArgumentException("invalid deliveryMode " + mode);
        }
    }

    public static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public static long requireUnsigned(long value, String field) {
        if (value < 0) {
            throw new ProtocolError(field + " exceeds signed long range");
        }
        return value;
    }

    public static byte[] copy(byte[] value) {
        return value == null ? new byte[0] : value.clone();
    }

    public static void validateUserMetadataKey(String key, String field) {
        validateUserMetadataKeyFragment(key, field, false);
    }

    public static void validateUserMetadataKeyFragment(String value, String field, boolean allowEmpty) {
        if (value == null || value.isEmpty()) {
            if (allowEmpty) {
                return;
            }
            throw new IllegalArgumentException(field + " is required");
        }
        if (value.length() > USER_METADATA_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + USER_METADATA_KEY_MAX_LENGTH + " characters");
        }
        for (int idx = 0; idx < value.length(); idx++) {
            char ch = value.charAt(idx);
            boolean allowed = (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '.'
                || ch == '_'
                || ch == ':'
                || ch == '-';
            if (!allowed) {
                throw new IllegalArgumentException(field + " contains unsupported character '" + ch + "'");
            }
        }
    }

    public static void validateUserMetadataScan(String prefix, String after, int limit) {
        validateUserMetadataKeyFragment(prefix, "prefix", true);
        validateUserMetadataKeyFragment(after, "after", true);
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (limit > MAX_USER_METADATA_SCAN_LIMIT) {
            throw new IllegalArgumentException("limit cannot exceed " + MAX_USER_METADATA_SCAN_LIMIT);
        }
        if (prefix != null && !prefix.isEmpty() && after != null && !after.isEmpty() && !after.startsWith(prefix)) {
            throw new IllegalArgumentException("after must use the same prefix");
        }
    }

    public static String websocketUrl(String baseUrl, boolean realtime) {
        try {
            URI uri = new URI(baseUrl);
            String scheme = switch (uri.getScheme()) {
                case "http" -> "ws";
                case "https" -> "wss";
                case "ws", "wss" -> uri.getScheme();
                default -> throw new IllegalArgumentException("unsupported base URL scheme \"" + uri.getScheme() + "\"");
            };
            String suffix = realtime ? "/ws/realtime" : "/ws/client";
            String basePath = uri.getPath();
            String path = (basePath == null || basePath.isBlank() || "/".equals(basePath))
                ? suffix
                : basePath.replaceAll("/+$", "") + suffix;
            return new URI(scheme, uri.getUserInfo(), uri.getHost(), uri.getPort(), path, null, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
